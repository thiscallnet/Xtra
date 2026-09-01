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
    data class Delete(val id: ChatMessageId, val atMs: Long) : TimelineOperation
    data class ClearUser(val userId: String, val atMs: Long) : TimelineOperation
    data class Clear(val atMs: Long) : TimelineOperation
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
            // These tombstones belong to the active generation. Replace(emptyList()) is the
            // processor's generation boundary and resets them before a new channel is accepted.
            val deletedMessageIds = LinkedHashSet<ChatMessageId>(MODERATION_TOMBSTONE_LIMIT)
            val clearedUsersAt = LinkedHashMap<String, Long>(MODERATION_TOMBSTONE_LIMIT, 0.75f, true)
            var globallyClearedAt: Long? = null
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
                        if (isSuppressed(item, deletedMessageIds, clearedUsersAt, globallyClearedAt)) continue
                        if (ids.add(item.id)) items.addLast(item)
                    }
                    is TimelineOperation.Prepend -> operation.items.asReversed().forEach { item ->
                        if (isSuppressed(item, deletedMessageIds, clearedUsersAt, globallyClearedAt)) continue
                        if (ids.add(item.id)) items.addFirst(item)
                    }
                    is TimelineOperation.Delete -> {
                        deletedMessageIds.add(operation.id)
                        trimModerationTombstones(deletedMessageIds, clearedUsersAt)
                        if (ids.remove(operation.id)) items.removeIf { it.id == operation.id }
                    }
                    is TimelineOperation.ClearUser -> {
                        val previous = clearedUsersAt[operation.userId]
                        if (previous == null || operation.atMs > previous) {
                            clearedUsersAt[operation.userId] = operation.atMs
                        }
                        trimModerationTombstones(deletedMessageIds, clearedUsersAt)
                        items.removeIf { item ->
                            val removed = item.user?.id == operation.userId
                            if (removed) ids.remove(item.id)
                            removed
                        }
                    }
                    is TimelineOperation.Clear -> {
                        val previous = globallyClearedAt
                        if (previous == null || operation.atMs > previous) {
                            globallyClearedAt = operation.atMs
                        }
                        items.clear(); ids.clear()
                    }
                    is TimelineOperation.Replace -> {
                        items.clear(); ids.clear()
                        deletedMessageIds.clear()
                        clearedUsersAt.clear()
                        globallyClearedAt = null
                        operation.items.takeLast(maxSize).forEach { if (ids.add(it.id)) items.addLast(it) }
                    }
                    is TimelineOperation.Reconcile -> {
                        val merged = (items.toList() + operation.recent.filterNot {
                            isSuppressed(it, deletedMessageIds, clearedUsersAt, globallyClearedAt)
                        })
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

    private fun isSuppressed(
        message: ChatMessage,
        deletedMessageIds: Set<ChatMessageId>,
        clearedUsersAt: Map<String, Long>,
        globallyClearedAt: Long?,
    ): Boolean {
        if (message.id in deletedMessageIds) return true
        if (globallyClearedAt?.let { message.timestampMs <= it } == true) return true
        val userId = message.user?.id ?: return false
        return clearedUsersAt[userId]?.let { message.timestampMs <= it } == true
    }

    private fun trimModerationTombstones(
        deletedMessageIds: LinkedHashSet<ChatMessageId>,
        clearedUsersAt: LinkedHashMap<String, Long>,
    ) {
        while (deletedMessageIds.size > MODERATION_TOMBSTONE_LIMIT) {
            deletedMessageIds.remove(deletedMessageIds.first())
        }
        while (clearedUsersAt.size > MODERATION_TOMBSTONE_LIMIT) {
            clearedUsersAt.remove(clearedUsersAt.entries.iterator().next().key)
        }
    }

    private companion object {
        const val MODERATION_TOMBSTONE_LIMIT = 4096
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
            is ChatEvent.Delete -> apply(TimelineOperation.Delete(event.messageId, event.receivedAtMs))
            is ChatEvent.ClearUser -> apply(TimelineOperation.ClearUser(event.userId, event.receivedAtMs))
            is ChatEvent.Clear -> apply(TimelineOperation.Clear(event.receivedAtMs))
            is ChatEvent.SettingsUpdated -> Unit
            is ChatEvent.TransportDisconnected -> Unit
        }
    }
}
