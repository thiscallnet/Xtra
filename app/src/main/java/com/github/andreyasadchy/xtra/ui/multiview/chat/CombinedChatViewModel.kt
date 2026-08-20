package com.github.andreyasadchy.xtra.ui.multiview.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.chat.ChatViewModel
import com.github.andreyasadchy.xtra.ui.multiview.CombinedChatPresentationPolicy
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CombinedChatViewModel(
    private val applicationContext: Context,
    private val chatViewModelFactory: () -> ChatViewModel,
) : ViewModel() {
    private val sessions = linkedMapOf<String, ChannelSession>()
    private val messages = mutableListOf<CombinedChatMessage>()
    private var sequence = 0L
    private var lifecycleStarted = false
    private val _updates = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val updates: SharedFlow<Unit> = _updates
    private val _streamInfoUpdates = MutableStateFlow<Map<String, CombinedChatStreamInfo>>(emptyMap())
    val streamInfoUpdates: StateFlow<Map<String, CombinedChatStreamInfo>> = _streamInfoUpdates

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
                val created = ChannelSession(identity, stream, chatViewModelFactory())
                sessions[identity] = created
                observe(created)
                if (lifecycleStarted) start(created)
            } else {
                session.stream = stream
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
        if (applicationContext.prefs().getBoolean(C.PLAYER_KEEP_CHAT_OPEN, false)) return
        sessions.values.forEach(ChannelSession::pause)
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
        // ChatViewModel.onMessage waits for this collector. Keep the work off Main,
        // but subscribe before startLive can deliver the first message.
        session.jobs += viewModelScope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            session.viewModel.newMessage.collect { result ->
                if (result.third > 0) {
                    session.viewModel.trimMessageOverflow()
                }
                append(session, result.first)
            }
        }
        session.jobs += viewModelScope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            session.viewModel.addMessages.collect { result ->
                prependHistory(session, result.first)
            }
        }
        session.jobs += viewModelScope.launch {
            session.viewModel.removeMessages.collect {
                synchronized(messages) {
                    var remaining = it
                    val iterator = messages.listIterator()
                    while (iterator.hasNext() && remaining > 0) {
                        if (iterator.next().identity == session.identity) {
                            iterator.remove()
                            remaining--
                        }
                    }
                }
                _updates.tryEmit(Unit)
            }
        }
        session.jobs += viewModelScope.launch {
            session.viewModel.reloadMessages.collect { reload ->
                if (reload) {
                    session.renderGeneration = CombinedChatPresentationPolicy.nextRenderGeneration(session.renderGeneration)
                    session.viewModel.reloadMessages.value = false
                    _updates.tryEmit(Unit)
                }
            }
        }
        session.jobs += viewModelScope.launch {
            session.viewModel.thirdPartyEmotesUpdated.collect {
                session.renderGeneration = CombinedChatPresentationPolicy.nextRenderGeneration(session.renderGeneration)
                _updates.tryEmit(Unit)
            }
        }
        session.jobs += viewModelScope.launch {
            session.viewModel.userEmotesUpdated.collect {
                session.renderGeneration = CombinedChatPresentationPolicy.nextRenderGeneration(session.renderGeneration)
                _updates.tryEmit(Unit)
            }
        }
        session.jobs += viewModelScope.launch {
            session.viewModel.streamInfo.collect { info ->
                info?.let {
                    _streamInfoUpdates.update { updates ->
                        updates + (session.identity to CombinedChatStreamInfo(
                            identity = session.identity,
                            title = it.title,
                            categoryId = it.gameId,
                            categoryName = it.gameName,
                        ))
                    }
                }
            }
        }
    }

    private fun start(session: ChannelSession) {
        val stream = session.stream
        val channelLogin = stream.channelLogin?.trim()?.takeIf { it.isNotBlank() } ?: return
        val preferences = applicationContext.prefs()
        if (!session.hasStarted) {
            session.viewModel.startLive(
                networkLibrary = preferences.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                recentMessagesUrl = "https://recent-messages.robotty.de/api/v2/recent-messages/\$channel",
                channelId = stream.channelId,
                channelLogin = channelLogin,
                channelName = stream.channelName,
                streamId = stream.id,
                readOnly = true,
            )
            session.hasStarted = true
        } else {
            // Match ChatFragment.reconnect(): restart the live transport and
            // rehydrate recent messages without recreating the ViewModel.
            session.viewModel.startLiveChat(stream.channelId, channelLogin, readOnly = true)
            if (preferences.getBoolean(C.CHAT_RECENT, true)) {
                session.viewModel.loadRecentMessages(
                    preferences.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    "https://recent-messages.robotty.de/api/v2/recent-messages/\$channel",
                    channelLogin,
                )
            }
        }
        session.networkActive = true
    }

    private fun append(session: ChannelSession, message: ChatMessage) {
        synchronized(messages) {
            if (message.id != null && messages.any { it.identity == session.identity && it.message.id == message.id }) return
            messages += CombinedChatMessage(session.identity, displayName(session.stream), message, sequence++)
            while (messages.size > MAX_MESSAGES) messages.removeAt(0)
        }
        _updates.tryEmit(Unit)
    }

    private fun prependHistory(session: ChannelSession, history: List<ChatMessage>) {
        synchronized(messages) {
            history.forEach { message ->
                if (message.id == null || messages.none { it.identity == session.identity && it.message.id == message.id }) {
                    messages += CombinedChatMessage(session.identity, displayName(session.stream), message, sequence++)
                }
            }
            messages.sortWith(compareBy<CombinedChatMessage> { it.message.timestamp ?: Long.MAX_VALUE }.thenBy { it.sequence })
            while (messages.size > MAX_MESSAGES) messages.removeAt(0)
        }
        _updates.tryEmit(Unit)
    }

    fun session(identity: String): ChatViewModel? = sessions[identity]?.viewModel

    fun channelId(identity: String): String? = sessions[identity]?.stream?.channelId

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
        val viewModel: ChatViewModel,
    ) {
        val jobs = mutableListOf<Job>()
        var renderGeneration: Long = 0L
        var hasStarted = false
        var networkActive = false

        fun pause() {
            if (!networkActive) return
            viewModel.stopLiveChat()
            viewModel.stopReplayChat()
            networkActive = false
        }

        fun release() {
            jobs.forEach(Job::cancel)
            jobs.clear()
            viewModel.releaseForMultiview()
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
                    chatViewModelFactory = {
                        ChatViewModel(
                            application.applicationContext,
                            module.graphQLRepository,
                            module.helixRepository,
                            module.playerRepository,
                            module.trustManager,
                            module.json,
                        )
                    },
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
