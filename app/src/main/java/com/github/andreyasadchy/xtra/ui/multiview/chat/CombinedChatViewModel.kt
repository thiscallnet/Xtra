package com.github.andreyasadchy.xtra.ui.multiview.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.multiview.CombinedChatPresentationPolicy
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ActiveChatSession
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ChatSessionHandle
import com.github.andreyasadchy.xtra.ui.chat.v2.session.LiveChatSessionSpec
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.TreeSet

class CombinedChatViewModel(
    private val applicationContext: Context,
    private val createSession: (LiveChatSessionSpec) -> ChatSessionHandle,
    private val keepChatOpen: () -> Boolean = {
        applicationContext.prefs().getBoolean(C.PLAYER_KEEP_CHAT_OPEN, false)
    },
    private val sessionScope: CoroutineScope? = null,
) : ViewModel() {
    private val sessions = linkedMapOf<String, ChannelSession>()
    private val messages = TreeSet<CombinedChatMessage>(
        compareBy<CombinedChatMessage> { it.message.timestampMs }.thenBy { it.sequence },
    )
    private var sequence = 0L
    private var lifecycleStarted = false
    private val _updates = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val updates: SharedFlow<Unit> = _updates
    private val _streamInfoUpdates = MutableStateFlow<Map<String, CombinedChatStreamInfo>>(emptyMap())
    val streamInfoUpdates: StateFlow<Map<String, CombinedChatStreamInfo>> = _streamInfoUpdates
    private val ownerScope: CoroutineScope
        get() = sessionScope ?: viewModelScope

    fun ensureStreams(streams: List<Stream>) {
        val desired = streams.mapNotNull { stream ->
            stableIdentity(stream)?.let { it to stream }
        }.toMap()
        sessions.keys.toList().filterNot(desired::containsKey).forEach { identity ->
            sessions.remove(identity)?.release()
            _streamInfoUpdates.update { it - identity }
            synchronized(messages) {
                messages.removeAll { it.identity == identity }
            }
        }
        desired.forEach { (identity, stream) ->
            val session = sessions[identity]
            if (session == null) {
                val channelId = stream.channelId?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                val channelLogin = stream.channelLogin?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                val created = ChannelSession(
                    identity = identity,
                    stream = stream,
                    handle = createSession(
                        LiveChatSessionSpec(
                            channelId = channelId,
                            channelLogin = channelLogin,
                            streamId = stream.id?.takeIf { it.isNotBlank() },
                        ),
                    ),
                )
                sessions[identity] = created
                observe(created)
                if (lifecycleStarted) start(created)
            } else {
                session.stream = stream
            }
        }
        _streamInfoUpdates.update {
            desired.mapValues { (identity, stream) ->
                CombinedChatStreamInfo(
                    identity = identity,
                    title = stream.title,
                    categoryId = stream.gameId,
                    categoryName = stream.gameName,
                )
            }
        }
        _updates.tryEmit(Unit)
    }

    fun onStart() {
        lifecycleStarted = true
        sessions.values.filterNot(ChannelSession::networkActive).forEach(::start)
    }

    fun onStop() {
        lifecycleStarted = false
        if (keepChatOpen()) return
        sessions.values.forEach { it.pause(ownerScope) }
    }

    fun snapshot(filterIdentity: String? = null): List<CombinedChatMessage> {
        return synchronized(messages) {
            messages
                .filter { filterIdentity == null || it.identity == filterIdentity }
                .map { message ->
                    message.copy(renderGeneration = sessions[message.identity]?.renderGeneration ?: message.renderGeneration)
                }
        }
    }

    override fun onCleared() {
        sessions.values.forEach(ChannelSession::release)
        sessions.clear()
    }

    private fun observe(session: ChannelSession) {
        session.jobs += ownerScope.launch {
            // The v2 batcher publishes a complete, ordered snapshot. Keeping one collector per
            // handle means a second Multiview channel cannot stop or overwrite the first one.
            session.handle.active.session.attachUi().collect { snapshot ->
                replaceSession(session, snapshot.messages)
            }
        }
        session.jobs += ownerScope.launch {
            combine(session.handle.active.catalog.state, session.handle.active.rewardCatalog) { catalog, rewards ->
                catalog.hydrated to rewards
            }.collect { (hydrated, rewards) ->
                session.rewardCatalog = rewards
                if (!hydrated) return@collect
                session.renderGeneration = CombinedChatPresentationPolicy.nextRenderGeneration(session.renderGeneration)
                _updates.tryEmit(Unit)
            }
        }
    }

    private fun start(session: ChannelSession) {
        if (session.networkActive) return
        session.networkActive = true
        session.controlJob?.cancel()
        session.controlJob = ownerScope.launch(Dispatchers.Default) {
            runCatching { session.handle.start() }
                .onFailure { session.networkActive = false }
        }
    }

    private fun replaceSession(session: ChannelSession, incoming: List<ChatMessage>) {
        synchronized(messages) {
            // Session snapshots are complete, but most publications are a one-message append.
            // Keep the channel index and mutate only IDs that changed so Multiview does not
            // remove/reinsert and globally sort every retained row on each message.
            val incomingById = incoming.associateBy { it.id }
            val previous = session.renderedMessages
            previous.keys.toList().filterNot(incomingById::containsKey).forEach { id ->
                messages.remove(previous.remove(id))
            }
            incomingById.forEach { (id, message) ->
                val old = previous[id]
                if (old == null) {
                    val added = CombinedChatMessage(
                        identity = session.identity,
                        channelName = displayName(session.stream),
                        message = message,
                        sequence = sequence++,
                    )
                    previous[id] = added
                    messages.add(added)
                } else if (old.message != message) {
                    val wasVisible = messages.remove(old)
                    val updated = old.copy(message = message, channelName = displayName(session.stream))
                    previous[id] = updated
                    if (wasVisible) messages.add(updated)
                }
            }
            while (messages.size > MAX_MESSAGES) {
                // Keep the complete per-channel snapshot indexed. The row may still be present
                // in that session's 600-message timeline and must not be rediscovered as new on
                // every subsequent publication.
                messages.pollFirst()
            }
        }
        _updates.tryEmit(Unit)
    }

    private fun sessionFor(identity: String): ChannelSession? = sessions[identity]

    fun session(identity: String): ActiveChatSession? = sessions[identity]?.handle?.active

    fun channelId(identity: String): String? = sessions[identity]?.stream?.channelId

    fun rewardCatalog(identity: String): Map<String, com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReward> =
        sessions[identity]?.rewardCatalog.orEmpty()

    fun invalidateRendering(identity: String?) {
        if (identity == null || sessions[identity] == null) return
        val session = sessions.getValue(identity)
        session.renderGeneration = CombinedChatPresentationPolicy.nextRenderGeneration(session.renderGeneration)
        _updates.tryEmit(Unit)
    }

    fun channelNames(): List<Pair<String, String>> = sessions.values.map { it.identity to displayName(it.stream) }

    private fun displayName(stream: Stream): String {
        return stream.channelName?.takeIf { it.isNotBlank() } ?: stream.channelLogin.orEmpty()
    }

    private fun stableIdentity(stream: Stream): String? {
        return stream.channelId?.takeIf { it.isNotBlank() }?.let { "id:${it.lowercase()}" }
            ?: stream.channelLogin?.trim()?.takeIf { it.isNotBlank() }?.let { "login:${it.lowercase()}" }
            ?: stream.id?.takeIf { it.isNotBlank() }?.let { "stream:${it.lowercase()}" }
    }

    private class ChannelSession(
        val identity: String,
        @Volatile var stream: Stream,
        val handle: ChatSessionHandle,
    ) {
        val jobs = mutableListOf<Job>()
        var renderGeneration: Long = 0L
        var networkActive = false
        var controlJob: Job? = null
        var rewardCatalog: Map<String, com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReward> = emptyMap()
        val renderedMessages = linkedMapOf<com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId, CombinedChatMessage>()

        fun pause(scope: kotlinx.coroutines.CoroutineScope) {
            if (!networkActive) return
            networkActive = false
            controlJob?.cancel()
            controlJob = null
            controlJob = scope.launch(Dispatchers.Default) {
                runCatching { handle.stop() }
            }
        }

        fun release() {
            jobs.forEach(Job::cancel)
            jobs.clear()
            controlJob?.cancel()
            controlJob = null
            renderedMessages.clear()
            handle.closeAsync()
        }

    }

    companion object {
        private const val MAX_MESSAGES = 500

        val Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as XtraApp
                val module = application.xtraModule
                CombinedChatViewModel(
                    applicationContext = application.applicationContext,
                    createSession = module.chatSessionManager::createLive,
                )
            }
        }
    }
}

data class CombinedChatMessage(
    val identity: String,
    val channelName: String,
    val message: ChatMessage,
    val sequence: Long,
    val renderGeneration: Long = 0L,
)

data class CombinedChatStreamInfo(
    val identity: String,
    val title: String?,
    val categoryId: String?,
    val categoryName: String?,
)
