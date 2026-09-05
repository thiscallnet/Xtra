package com.github.andreyasadchy.xtra.ui.chat.v2.session

import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEvent
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatRewardCatalog
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey
import com.github.andreyasadchy.xtra.ui.chat.v2.transport.ChatTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

data class LiveChatSessionSpec(
    val channelId: String,
    val channelLogin: String,
    val streamId: String? = null,
    val recentMessagesUrl: String? = null,
    /** The legacy player owns these supplemental sockets and bridges their events into v2. */
    val legacySupplementalSockets: Boolean = false,
    val onCommunityGift: suspend (String, Long, String, Int) -> Unit = { _, _, _, _ -> },
)

data class ActiveChatSession(
    val spec: LiveChatSessionSpec,
    val key: ChatSessionKey,
    val session: ChatSession,
    val catalog: ChatCatalogRepository,
    val rewardCatalog: Flow<ChatRewardCatalog> = flowOf(ChatRewardCatalog()),
)

/** Creates independent live sessions. The player and Multiview use separate handles. */
class ChatSessionFactory(
    private val parentScope: CoroutineScope,
    private val transportFactory: (LiveChatSessionSpec) -> ChatTransport,
    private val catalogFactory: (LiveChatSessionSpec, CoroutineScope) -> ChatCatalogRepository,
    private val recentHistory: suspend (LiveChatSessionSpec) -> List<ChatMessage> = { emptyList() },
    private val initialSettings: suspend (LiveChatSessionSpec) -> ChatEvent.SettingsUpdated? = { null },
    private val rewardCatalogFactory: (LiveChatSessionSpec, CoroutineScope) -> Flow<ChatRewardCatalog> = { _, _ -> flowOf(ChatRewardCatalog()) },
    private val maxTimelineSize: Int = 600,
) {
    private var generation = 0L

    fun createLive(spec: LiveChatSessionSpec): ChatSessionHandle {
        val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
        val key = ChatSessionKey(spec.channelId, ++generation)
        val catalog = catalogFactory(spec, scope)
        lateinit var session: ChatSession
        suspend fun reconcileRecent() {
            val history = try {
                recentHistory(spec)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                emptyList()
            }
            session.reconcile(key, history)
        }
        session = ChatSession(
            parentScope = scope,
            transport = transportFactory(spec),
            maxTimelineSize = maxTimelineSize,
            onTransportDisconnected = { _, _ -> reconcileRecent() },
            onDecorationUpdated = catalog::applyDecorationUpdate,
        )
        // Keep the public flow stable for renderers, but defer the actual provider construction
        // until the owner explicitly starts this handle.
        val rewardCatalog = MutableStateFlow(ChatRewardCatalog())
        val active = ActiveChatSession(spec, key, session, catalog, rewardCatalog)
        return ChatSessionHandle(
            active = active,
            scope = scope,
            reconcileRecent = ::reconcileRecent,
            startRewardCatalog = { rewardScope -> rewardCatalogFactory(spec, rewardScope) },
            initialSettings = { initialSettings(spec) },
            rewardCatalogState = rewardCatalog,
        )
    }
}

class ChatSessionHandle internal constructor(
    val active: ActiveChatSession,
    private val scope: CoroutineScope,
    private val reconcileRecent: suspend () -> Unit,
    private val startRewardCatalog: (CoroutineScope) -> Flow<ChatRewardCatalog>,
    private val initialSettings: suspend () -> ChatEvent.SettingsUpdated?,
    private val rewardCatalogState: MutableStateFlow<ChatRewardCatalog>,
) {
    private val lifecycleMutex = Mutex()
    private var started = false
    private var closed = false
    private var startupWork: Job? = null

    suspend fun start() = withContext(NonCancellable) {
        lifecycleMutex.withLock {
            if (closed || started) return@withLock
            active.session.start(active.key)
            started = true
            active.catalog.refresh()
            startupWork = scope.launch {
                // Transport, catalog, history, settings, and reward metadata are independent.
                launch { reconcileRecent() }
                launch {
                    try {
                        initialSettings()?.let { active.session.submit(active.key, it) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        // Optional room settings must not take chat down.
                    }
                }
                launch {
                    try {
                        startRewardCatalog(this).collect { value ->
                            rewardCatalogState.value = value
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        // Reward metadata is presentation-only and best effort.
                    }
                }
            }
        }
    }

    suspend fun stop() = withContext(NonCancellable) {
        lifecycleMutex.withLock {
            if (!started) return@withLock
            started = false
            startupWork?.cancelAndJoin()
            startupWork = null
            active.catalog.pause()
            active.session.stop(active.key)
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        lifecycleMutex.withLock {
            if (closed) return@withLock
            closed = true
            started = false
            startupWork?.cancelAndJoin()
            startupWork = null
            active.session.close()
            active.catalog.close()
            scope.coroutineContext[Job]?.cancel()
        }
    }

    /** Used by owners whose synchronous clear callback cannot suspend. */
    fun closeAsync() {
        scope.launch {
            close()
        }
    }
}

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
    private val rewardCatalogFactory: (LiveChatSessionSpec, CoroutineScope) -> Flow<ChatRewardCatalog> = { _, _ -> flowOf(ChatRewardCatalog()) },
    private val maxTimelineSize: Int = 600,
) {
    private val managerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + managerJob)
    private val transitionMutex = Mutex()
    private val _active = MutableStateFlow<ActiveChatSession?>(null)
    val active: StateFlow<ActiveChatSession?> = _active.asStateFlow()
    private var generation = 0L
    private var closed = false
    private val factory = ChatSessionFactory(
        parentScope = scope,
        transportFactory = transportFactory,
        catalogFactory = catalogFactory,
        recentHistory = recentHistory,
        initialSettings = initialSettings,
        rewardCatalogFactory = rewardCatalogFactory,
        maxTimelineSize = maxTimelineSize,
    )

    /** Multiview entry point. It does not touch [_active]. */
    fun createLive(spec: LiveChatSessionSpec): ChatSessionHandle = factory.createLive(spec)

    suspend fun start(spec: LiveChatSessionSpec): ActiveChatSession = transitionMutex.withLock {
        check(!closed) { "ChatSessionManager is closed" }
        val current = _active.value
        if (current?.spec == spec && current.session.isActive) return@withLock current

        current?.let {
            it.session.close()
            it.catalog.close()
        }
        val key = ChatSessionKey(spec.channelId, ++generation)
        val catalog = catalogFactory(spec, scope)
        val session = ChatSession(
            parentScope = scope,
            transport = transportFactory(spec),
            maxTimelineSize = maxTimelineSize,
            onTransportDisconnected = { disconnectedKey, _ ->
                _active.value
                    ?.takeIf { it.key == disconnectedKey }
                    ?.let(::scheduleRecentHistory)
            },
            onDecorationUpdated = catalog::applyDecorationUpdate,
        )
        session.start(key)
        val active = ActiveChatSession(spec, key, session, catalog, rewardCatalogFactory(spec, scope))
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
