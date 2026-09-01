package com.github.andreyasadchy.xtra.ui.chat.v2.session

import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEvent
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey
import com.github.andreyasadchy.xtra.ui.chat.v2.transport.ChatTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LiveChatSessionSpec(
    val channelId: String,
    val channelLogin: String,
    val recentMessagesUrl: String? = null,
)

data class ActiveChatSession(
    val spec: LiveChatSessionSpec,
    val key: ChatSessionKey,
    val session: ChatSession,
    val catalog: ChatCatalogRepository,
)

/**
 * Playback-owned live chat coordinator. Its parent is the application/playback
 * scope, never a Fragment or a Fragment view lifecycle.
 */
class ChatSessionManager(
    parentScope: CoroutineScope,
    private val transportFactory: (LiveChatSessionSpec) -> ChatTransport,
    private val catalogFactory: (LiveChatSessionSpec, CoroutineScope) -> ChatCatalogRepository,
    private val recentHistory: suspend (LiveChatSessionSpec) -> List<ChatMessage> = { emptyList() },
    private val initialSettings: suspend (LiveChatSessionSpec) -> ChatEvent.SettingsUpdated? = { null },
    private val maxTimelineSize: Int = 600,
) {
    private val managerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + managerJob)
    private val transitionMutex = Mutex()
    private val _active = MutableStateFlow<ActiveChatSession?>(null)
    val active: StateFlow<ActiveChatSession?> = _active.asStateFlow()
    private var generation = 0L
    private var closed = false

    suspend fun start(spec: LiveChatSessionSpec): ActiveChatSession = transitionMutex.withLock {
        check(!closed) { "ChatSessionManager is closed" }
        val current = _active.value
        if (current?.spec == spec && current.session.isActive) return@withLock current

        current?.let {
            it.session.close()
            it.catalog.close()
        }
        val key = ChatSessionKey(spec.channelId, ++generation)
        val session = ChatSession(
            parentScope = scope,
            transport = transportFactory(spec),
            maxTimelineSize = maxTimelineSize,
            onTransportDisconnected = { disconnectedKey, _ ->
                _active.value
                    ?.takeIf { it.key == disconnectedKey }
                    ?.let(::scheduleRecentHistory)
            },
        )
        val catalog = catalogFactory(spec, scope)
        session.start(key)
        val active = ActiveChatSession(spec, key, session, catalog)
        _active.value = active
        catalog.refresh()
        // Reconcile startup/reconstruction history independently of transport startup. Live
        // events can arrive while this request is in flight; ChatEventProcessor serializes the
        // atomic merge with those events and rejects the work if this generation is no longer
        // active.
        scheduleRecentHistory(active)
        scope.launch {
            val settings = runCatching { initialSettings(spec) }.getOrNull() ?: return@launch
            val stillActive = _active.value?.key == key
            if (stillActive) session.submit(key, settings)
        }
        active
    }

    suspend fun stop(key: ChatSessionKey? = null) = transitionMutex.withLock {
        val active = _active.value ?: return@withLock
        if (key != null && key != active.key) return@withLock
        _active.value = null
        active.session.close()
        active.catalog.close()
    }

    private fun scheduleRecentHistory(active: ActiveChatSession) {
        scope.launch {
            val recent = try {
                recentHistory(active.spec)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
            if (_active.value?.key != active.key) return@launch
            active.session.reconcile(active.key, recent)
        }
    }

    suspend fun close() = transitionMutex.withLock {
        if (closed) return@withLock
        closed = true
        val active = _active.value
        _active.value = null
        active?.session?.close()
        active?.catalog?.close()
        managerJob.cancel()
    }
}
