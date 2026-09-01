package com.github.andreyasadchy.xtra.ui.chat.v2.session

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEvent
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

sealed interface TimelineOperation {
    data class Append(val items: List<ChatMessage>) : TimelineOperation
    data class Prepend(val items: List<ChatMessage>) : TimelineOperation
    data class Delete(val id: ChatMessageId) : TimelineOperation
    data class ClearUser(val userId: String) : TimelineOperation
    data object Clear : TimelineOperation
    data class Replace(val items: List<ChatMessage>) : TimelineOperation
    class RequestSnapshot(val result: CompletableDeferred<List<ChatMessage>>) : TimelineOperation
    class RequestVersionedSnapshot(val result: CompletableDeferred<VersionedTimelineSnapshot>) : TimelineOperation
    data class Reconcile(val recent: List<ChatMessage>) : TimelineOperation
}

data class VersionedTimelineSnapshot(val version: Long, val messages: List<ChatMessage>)

/** The only mutable owner of the live message tail. Asset work is deliberately absent here. */
class ChatTimelineStore(
    scope: CoroutineScope,
    private val maxSize: Int = 600,
) {
    private val operations = Channel<TimelineOperation>(capacity = 4096)
    private val _version = MutableStateFlow(0L)
    val versions: Flow<Long> = _version.asStateFlow()

    init {
        scope.launch {
            val items = ArrayDeque<ChatMessage>(maxSize)
            val ids = HashSet<com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId>(maxSize * 2)
            for (operation in operations) {
                when (operation) {
                    is TimelineOperation.RequestSnapshot -> {
                        operation.result.complete(items.toList())
                        continue
                    }
                    is TimelineOperation.RequestVersionedSnapshot -> {
                        operation.result.complete(VersionedTimelineSnapshot(_version.value, items.toList()))
                        continue
                    }
                    is TimelineOperation.Append -> operation.items.forEach { item ->
                        if (ids.add(item.id)) items.addLast(item)
                    }
                    is TimelineOperation.Prepend -> operation.items.asReversed().forEach { item ->
                        if (ids.add(item.id)) items.addFirst(item)
                    }
                    is TimelineOperation.Delete -> if (ids.remove(operation.id)) items.removeIf { it.id == operation.id }
                    is TimelineOperation.ClearUser -> items.removeIf { item ->
                        val removed = item.user?.id == operation.userId
                        if (removed) ids.remove(item.id)
                        removed
                    }
                    TimelineOperation.Clear -> { items.clear(); ids.clear() }
                    is TimelineOperation.Replace -> {
                        items.clear(); ids.clear()
                        operation.items.takeLast(maxSize).forEach { if (ids.add(it.id)) items.addLast(it) }
                    }
                    is TimelineOperation.Reconcile -> {
                        val merged = (items.toList() + operation.recent)
                            .distinctBy { it.id }
                            .sortedBy { it.timestampMs }
                        items.clear(); ids.clear()
                        merged.takeLast(maxSize).forEach { if (ids.add(it.id)) items.addLast(it) }
                    }
                }
                while (items.size > maxSize) items.removeFirst().also { ids.remove(it.id) }
                _version.value++
            }
        }
    }

    suspend fun apply(operation: TimelineOperation) = operations.send(operation)

    suspend fun snapshot(): List<ChatMessage> {
        val result = CompletableDeferred<List<ChatMessage>>()
        operations.send(TimelineOperation.RequestSnapshot(result))
        return result.await()
    }

    suspend fun versionedSnapshot(): VersionedTimelineSnapshot {
        val result = CompletableDeferred<VersionedTimelineSnapshot>()
        operations.send(TimelineOperation.RequestVersionedSnapshot(result))
        return result.await()
    }

    suspend fun accept(event: ChatEvent) {
        when (event) {
            is ChatEvent.Message -> apply(TimelineOperation.Append(listOf(event.message)))
            is ChatEvent.Notice -> apply(TimelineOperation.Append(listOf(event.message)))
            is ChatEvent.Delete -> apply(TimelineOperation.Delete(event.messageId))
            is ChatEvent.ClearUser -> apply(TimelineOperation.ClearUser(event.userId))
            is ChatEvent.Clear -> apply(TimelineOperation.Clear)
            is ChatEvent.SettingsUpdated -> Unit
        }
    }
}
