package com.github.andreyasadchy.xtra.ui.chat.v2.session

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEvent
import com.github.andreyasadchy.xtra.ui.chat.v2.transport.ChatTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.cancelAndJoin

/** Owns transport and timeline state. A Fragment can subscribe and disappear without replaying UI work. */
class ChatSession(
    parentScope: CoroutineScope,
    private val transport: ChatTransport,
    maxTimelineSize: Int = 600,
    private val onTransportDisconnected: suspend (ChatSessionKey, String?) -> Unit = { _, _ -> },
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    val timeline = ChatTimelineStore(scope, maxTimelineSize)
    private val processor = ChatEventProcessor(scope, timeline)
    private val transitionMutex = Mutex()
    private var transportJob: Job? = null
    private var desiredKey: ChatSessionKey? = null
    private var highestAcceptedGeneration = Long.MIN_VALUE
    private var highestAcceptedKey: ChatSessionKey? = null
    private var closed = false

    val isActive: Boolean
        get() = job.isActive && !closed

    suspend fun start(key: ChatSessionKey) {
        transitionMutex.withLock {
            check(!closed && job.isActive) { "ChatSession is closed" }
            if (key.generation < highestAcceptedGeneration) return
            if (key.generation == highestAcceptedGeneration &&
                highestAcceptedGeneration != Long.MIN_VALUE &&
                key != highestAcceptedKey
            ) return
            if (key == desiredKey && transportJob?.isActive == true) return
            highestAcceptedGeneration = maxOf(highestAcceptedGeneration, key.generation)
            highestAcceptedKey = key
            desiredKey = key
            transportJob?.cancelAndJoin()
            transportJob = null
            processor.activate(key)
            if (desiredKey == key) {
                transportJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        transport.events(key).collect { event ->
                            if (event is ChatEvent.TransportDisconnected) {
                                launch { onTransportDisconnected(key, event.reason) }
                            } else {
                                processor.submit(key, event)
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        onTransportDisconnected(key, e.message)
                    }
                }
            }
        }
    }

    suspend fun stop(key: ChatSessionKey? = null) {
        transitionMutex.withLock {
            if (closed) return
            if (key != null && key != desiredKey) return
            desiredKey = null
            transportJob?.cancelAndJoin()
            transportJob = null
            processor.deactivate()
        }
    }

    suspend fun snapshot(): List<com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage> = timeline.snapshot()

    fun attachUi() = ChatUiBatcher(timeline.versions, timeline::versionedSnapshot, VersionedTimelineSnapshot::version).flow()

    suspend fun reconcile(key: ChatSessionKey, recent: List<com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage>) = processor.reconcile(key, recent)

    /** Injects non-message session state through the same generation-aware writer. */
    suspend fun submit(key: ChatSessionKey, event: ChatEvent) = processor.submit(key, event)

    suspend fun close() {
        transitionMutex.withLock {
            if (closed) return
            closed = true
            desiredKey = null
            transportJob?.cancelAndJoin()
            transportJob = null
            // The parent may already have cancelled the processor actor. Its control queue cannot
            // be acknowledged in that state, so deactivate only while the session is alive.
            if (job.isActive && processor.isActive) processor.deactivate()
            job.cancel()
        }
    }
}

interface ChatClock { fun nowMs(): Long }

class WallChatClock : ChatClock { override fun nowMs(): Long = System.currentTimeMillis() }
