package com.github.andreyasadchy.xtra.ui.chat.v2.session

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEvent
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/** Lossless canonical event ingress. Only UI snapshots may be conflated. */
class ChatEventProcessor(scope: CoroutineScope, private val store: ChatTimelineStore) {
    private data class Envelope(val key: ChatSessionKey, val event: ChatEvent)
    private sealed interface Command {
        data class Activate(val key: ChatSessionKey, val acknowledged: CompletableDeferred<Unit>) : Command
        data class Event(val value: Envelope) : Command
        data class Reconcile(val key: ChatSessionKey, val recent: List<com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage>, val acknowledged: CompletableDeferred<Unit>) : Command
        data class Deactivate(val acknowledged: CompletableDeferred<Unit>) : Command
    }
    private val events = Channel<Command>(capacity = 4096)
    private val seen = LinkedHashSet<String>(4096)
    private val desiredKey = AtomicReference<ChatSessionKey?>(null)
    private val processorJob: Job

    init {
        processorJob = scope.launch {
            var activeKey: ChatSessionKey? = null
            for (command in events) {
                val desired = desiredKey.get()
                if (desired != null && desired != activeKey) {
                    activeKey = desired
                    seen.clear()
                    store.apply(TimelineOperation.Replace(emptyList()))
                }
                when (command) {
                    is Command.Activate -> command.acknowledged.complete(Unit)
                    is Command.Deactivate -> {
                        activeKey = null
                        seen.clear()
                        store.apply(TimelineOperation.Replace(emptyList()))
                        command.acknowledged.complete(Unit)
                    }
                    is Command.Event -> {
                        if (command.value.key != activeKey) continue
                        val event = command.value.event
                        val id = event.eventId
                        if (id != null && !seen.add(id)) continue
                        if (seen.size > 4096) seen.remove(seen.first())
                        store.accept(event)
                    }
                    is Command.Reconcile -> {
                        if (command.key == activeKey) store.apply(TimelineOperation.Reconcile(command.recent))
                        command.acknowledged.complete(Unit)
                    }
                }
            }
        }
        // Wake control senders and their acknowledgements if the parent/session dies before the
        // actor can consume their command. In particular, close() must not wait forever on an
        // acknowledgement from an actor that was already cancelled with its parent.
        processorJob.invokeOnCompletion {
            events.close()
        }
    }

    val isActive: Boolean
        get() = processorJob.isActive

    private fun acknowledgement(): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { acknowledgement ->
            processorJob.invokeOnCompletion { acknowledgement.complete(Unit) }
        }

    private suspend fun sendControl(command: Command, acknowledgement: CompletableDeferred<Unit>) {
        if (!processorJob.isActive) return
        try {
            events.send(command)
        } catch (_: ClosedSendChannelException) {
            if (processorJob.isActive) throw IllegalStateException("Chat event processor closed")
            return
        } catch (e: CancellationException) {
            if (processorJob.isActive) throw e
            return
        }
        acknowledgement.await()
    }

    suspend fun activate(key: ChatSessionKey) {
        desiredKey.set(key)
        val acknowledged = acknowledgement()
        sendControl(Command.Activate(key, acknowledged), acknowledged)
    }

    suspend fun deactivate() {
        desiredKey.set(null)
        if (!processorJob.isActive) return
        val acknowledged = acknowledgement()
        sendControl(Command.Deactivate(acknowledged), acknowledged)
    }

    suspend fun submit(key: ChatSessionKey, event: ChatEvent) {
        if (desiredKey.get() == key) events.send(Command.Event(Envelope(key, event)))
    }

    suspend fun reconcile(key: ChatSessionKey, recent: List<com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage>) {
        if (desiredKey.get() != key) return
        if (!processorJob.isActive) return
        val acknowledged = acknowledgement()
        sendControl(Command.Reconcile(key, recent, acknowledged), acknowledged)
    }
}
