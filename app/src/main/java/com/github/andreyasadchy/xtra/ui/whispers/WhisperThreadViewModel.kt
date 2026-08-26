package com.github.andreyasadchy.xtra.ui.whispers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.model.twitchinbox.LocalSendState
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxError
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxException
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchUserSummary
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperMessage
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperThreadDetails
import com.github.andreyasadchy.xtra.repository.WhispersRepository
import com.github.andreyasadchy.xtra.util.sanitizeLiveNotificationTechnicalMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/** Exposed for maintainer-run two-account qualification; this agent never sends messages. */
internal const val WHISPER_SEND_ENABLED = true
private val WHISPER_THREAD_DISCOVERY_DELAYS_MILLIS = longArrayOf(0L, 500L, 1_000L, 2_000L)

data class WhisperThreadUiState(
    val peer: TwitchUserSummary,
    val messages: List<WhisperMessage> = emptyList(),
    val initialLoading: Boolean = false,
    val loadingOlder: Boolean = false,
    val hasOlder: Boolean = false,
    val composer: String = "",
    val error: TwitchInboxError? = null,
)

class WhisperThreadViewModel(
    private val repository: WhispersRepository,
    peer: TwitchUserSummary,
    initialThreadId: String?,
) : ViewModel() {
    private val _uiState = MutableStateFlow(WhisperThreadUiState(peer))
    val uiState: StateFlow<WhisperThreadUiState> = _uiState.asStateFlow()
    private var threadId: String? = initialThreadId
    private var accountId = repository.currentUserId()
    private var loadJob: Job? = null
    private val pending = mutableMapOf<String, WhisperMessage>()
    private var olderCursor: String? = null
    private var hasLoadedOlderHistory = false

    init { loadInitial() }

    fun setComposer(value: String) { _uiState.value = _uiState.value.copy(composer = value) }

    fun loadInitial() {
        if (threadId == null) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(initialLoading = true, error = null)
            runCatching { repository.getThread(threadId!!) }.onSuccess { details ->
                mergeMessages(details.messages, replace = true)
                olderCursor = details.nextCursor
                hasLoadedOlderHistory = false
                _uiState.value = _uiState.value.copy(initialLoading = false, hasOlder = olderCursor != null && details.hasOlderMessages, error = null)
                details.messages.lastOrNull()?.id?.let { runCatching { repository.markThreadRead(threadId!!, it) } }
            }.onFailure { error -> _uiState.value = _uiState.value.copy(initialLoading = false, error = error.toInboxError()) }
        }
    }

    fun refreshLatest() {
        val currentAccountId = repository.currentUserId()
        if (currentAccountId != accountId) {
            accountId = currentAccountId
            threadId = null
            olderCursor = null
            hasLoadedOlderHistory = false
            pending.clear()
            _uiState.value = _uiState.value.copy(messages = emptyList(), hasOlder = false, error = TwitchInboxError.SignedOut)
            return
        }
        val id = threadId ?: return
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            runCatching { repository.getThread(id) }.onSuccess { details ->
                mergeMessages(details.messages, replace = false)
                if (!hasLoadedOlderHistory) olderCursor = details.nextCursor
                _uiState.value = _uiState.value.copy(hasOlder = olderCursor != null && details.hasOlderMessages, error = null)
                details.messages.lastOrNull()?.id?.let { runCatching { repository.markThreadRead(id, it) } }
            }.onFailure { error -> _uiState.value = _uiState.value.copy(error = error.toInboxError()) }
        }
    }

    fun loadOlder() {
        val id = threadId ?: return
        if (loadJob?.isActive == true || !_uiState.value.hasOlder) return
        val cursor = olderCursor ?: return
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loadingOlder = true)
            runCatching { repository.getThread(id, cursor) }.onSuccess { details ->
                mergeMessages(details.messages, replace = false)
                olderCursor = nextOlderCursor(details, cursor)
                hasLoadedOlderHistory = true
                _uiState.value = _uiState.value.copy(hasOlder = olderCursor != null && details.hasOlderMessages, error = null)
            }.onFailure { error -> _uiState.value = _uiState.value.copy(error = error.toInboxError()) }
            _uiState.value = _uiState.value.copy(loadingOlder = false)
        }
    }

    fun send() {
        if (!WHISPER_SEND_ENABLED) return
        val text = _uiState.value.composer
        if (text.trim().isEmpty()) return
        val localId = "local-${System.nanoTime()}"
        val pendingMessage = WhisperMessage(localId, repository.createWhisperNonce(), currentUserId(), text, Instant.now(), true, cursor = null, localState = LocalSendState.SENDING)
        pending[localId] = pendingMessage
        _uiState.value = _uiState.value.copy(composer = "", messages = (_uiState.value.messages + pendingMessage).distinctBy { it.id })
        sendPending(pendingMessage)
    }

    fun retry(message: WhisperMessage) {
        val retried = message.copy(localState = LocalSendState.SENDING, sendError = null)
        pending[message.id] = retried
        _uiState.value = _uiState.value.copy(messages = _uiState.value.messages.map { if (it.id == message.id) retried else it })
        sendPending(retried)
    }

    private fun sendPending(message: WhisperMessage) {
        viewModelScope.launch {
            runCatching { repository.sendWhisper(_uiState.value.peer.id, message.text, message.nonce ?: repository.createWhisperNonce()) }.onSuccess { result ->
                val confirmed = message.copy(nonce = result.nonce, localState = LocalSendState.CONFIRMED, sendError = null)
                pending[message.id] = confirmed
                _uiState.value = _uiState.value.copy(messages = _uiState.value.messages.map { if (it.id == message.id) confirmed else it }, error = null)
                if (threadId == null) threadId = discoverThreadWithRetry(findThread = { repository.findRecentThreadByPeer(_uiState.value.peer.id) })
                if (threadId != null) refreshLatest()
            }.onFailure { error ->
                val failed = message.copy(localState = LocalSendState.FAILED, sendError = error.sendDebugDetails())
                pending[message.id] = failed
                _uiState.value = _uiState.value.copy(messages = _uiState.value.messages.map { if (it.id == message.id) failed else it }, error = error.toInboxError())
            }
        }
    }

    private fun mergeMessages(incoming: List<WhisperMessage>, replace: Boolean) {
        val merged = mergeWhisperMessages(_uiState.value.messages, incoming, pending, replace)
        pending.clear()
        pending.putAll(merged.pending)
        _uiState.value = _uiState.value.copy(messages = merged.messages)
    }

    private fun currentUserId() = repository.currentUserId().orEmpty()

    companion object {
        fun factory(repository: WhispersRepository, peer: TwitchUserSummary, threadId: String?) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WhisperThreadViewModel(repository, peer, threadId) as T
        }
    }
}

internal fun nextOlderCursor(details: WhisperThreadDetails, requestedCursor: String?): String? =
    details.nextCursor?.takeIf { it.isNotBlank() && it != requestedCursor }

internal suspend fun discoverThreadWithRetry(
    findThread: suspend () -> String?,
    delaysMillis: LongArray = WHISPER_THREAD_DISCOVERY_DELAYS_MILLIS,
): String? {
    delaysMillis.forEach { waitMillis ->
        if (waitMillis > 0) delay(waitMillis)
        findThread()?.let { return it }
    }
    return null
}

internal data class WhisperMessageMergeResult(
    val messages: List<WhisperMessage>,
    val pending: Map<String, WhisperMessage>,
)

internal fun mergeWhisperMessages(
    current: List<WhisperMessage>,
    incoming: List<WhisperMessage>,
    pending: Map<String, WhisperMessage>,
    replace: Boolean,
): WhisperMessageMergeResult {
    val incomingById = incoming.associateBy { it.id }
    val incomingByNonce = incoming.mapNotNull { message -> message.nonce?.takeIf { it.isNotBlank() }?.let { it to message } }.toMap()
    val reconciledLocalIds = pending.values.filter { local ->
        incomingById.containsKey(local.id) || local.nonce?.let(incomingByNonce::containsKey) == true
    }.map { it.id }.toSet()
    val remainingPending = pending.filterKeys { it !in reconciledLocalIds }
    val existing = if (replace) emptyList() else current.filterNot { it.id in reconciledLocalIds }
    val candidates = incoming + existing + remainingPending.values
    val seenIds = mutableSetOf<String>()
    val deduplicated = candidates.filter { message ->
        seenIds.add(message.id)
    }
    return WhisperMessageMergeResult(
        deduplicated.sortedWith(compareBy<WhisperMessage> { it.sentAt ?: Instant.MIN }.thenBy { it.id }),
        remainingPending,
    )
}

private fun Throwable.toInboxError(): TwitchInboxError = (this as? TwitchInboxException)?.error ?: TwitchInboxError.Network

private fun Throwable.sendDebugDetails(): String {
    val inboxError = (this as? TwitchInboxException)?.error
    val category = when (inboxError) {
        TwitchInboxError.SignedOut -> "signed_out"
        TwitchInboxError.RequiresReauth -> "requires_reauth"
        TwitchInboxError.Network -> "network"
        is TwitchInboxError.RateLimited -> "rate_limited"
        TwitchInboxError.TwitchServerError -> "twitch_server"
        is TwitchInboxError.GraphQl -> "graphql:${inboxError.operation}"
        is TwitchInboxError.PrivateApiChanged -> "private_api_changed:${inboxError.operation}"
        TwitchInboxError.Unknown -> "unknown"
        null -> this::class.simpleName ?: "unknown"
    }
    val detail = when (inboxError) {
        is TwitchInboxError.GraphQl -> inboxError.safeMessage
        else -> cause?.message ?: takeIf { it !is TwitchInboxException }?.message
    }
    return listOfNotNull(category, sanitizeLiveNotificationTechnicalMessage(detail))
        .joinToString("; ")
        .take(300)
}
