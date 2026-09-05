package com.github.andreyasadchy.xtra.ui.chat.v2.session

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEvent
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatModeration
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatModerationDisplayMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs

sealed interface TimelineOperation {
    data class Append(val items: List<ChatMessage>) : TimelineOperation
    data class Prepend(val items: List<ChatMessage>) : TimelineOperation
    data class Delete(val id: ChatMessageId, val atMs: Long) : TimelineOperation
    data class ClearUser(
        val userId: String?,
        val userLogin: String?,
        val atMs: Long,
        val moderation: ChatModeration,
        val displayMode: ChatModerationDisplayMode,
    ) : TimelineOperation
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
            val clearedUsers = ArrayDeque<UserModeration>(MODERATION_TOMBSTONE_LIMIT)
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
                        if (isSuppressed(item, deletedMessageIds, clearedUsers, globallyClearedAt)) continue
                        if (isDuplicateReward(item, items)) continue
                        if (ids.add(item.id)) items.addLast(decorate(item, clearedUsers))
                    }
                    is TimelineOperation.Prepend -> operation.items.asReversed().forEach { item ->
                        if (isSuppressed(item, deletedMessageIds, clearedUsers, globallyClearedAt)) continue
                        if (isDuplicateReward(item, items)) continue
                        if (ids.add(item.id)) items.addFirst(decorate(item, clearedUsers))
                    }
                    is TimelineOperation.Delete -> {
                        deletedMessageIds.add(operation.id)
                        trimModerationTombstones(deletedMessageIds, clearedUsers)
                        if (ids.remove(operation.id)) items.removeIf { it.id == operation.id }
                    }
                    is TimelineOperation.ClearUser -> {
                        if (operation.userId == null && operation.userLogin == null) continue
                        if (operation.displayMode == ChatModerationDisplayMode.NOTICE) continue
                        clearedUsers.addLast(
                            UserModeration(
                                userId = operation.userId,
                                userLogin = operation.userLogin,
                                atMs = operation.atMs,
                                moderation = operation.moderation,
                                displayMode = operation.displayMode,
                            ),
                        )
                        trimModerationTombstones(deletedMessageIds, clearedUsers)
                        when (operation.displayMode) {
                            ChatModerationDisplayMode.NOTICE -> Unit
                            ChatModerationDisplayMode.HIDE -> items.removeIf { item ->
                                val removed = operation.matches(item)
                                if (removed) ids.remove(item.id)
                                removed
                            }
                            ChatModerationDisplayMode.STRIKETHROUGH -> {
                                val decorated = items.map { item ->
                                    if (operation.matches(item) && item.moderation == null) {
                                        item.copy(moderation = operation.moderation)
                                    } else {
                                        item
                                    }
                                }
                                items.clear()
                                items.addAll(decorated)
                            }
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
                        clearedUsers.clear()
                        globallyClearedAt = null
                        operation.items.takeLast(maxSize).forEach { if (ids.add(it.id)) items.addLast(it) }
                    }
                    is TimelineOperation.Reconcile -> {
                        val merged = (items.toList() + operation.recent.mapNotNull {
                            if (isSuppressed(it, deletedMessageIds, clearedUsers, globallyClearedAt)) null
                            else decorate(it, clearedUsers)
                        })
                            .distinctBy { it.id }
                            .sortedBy { it.timestampMs }
                        items.clear(); ids.clear()
                        merged.fold(ArrayList<ChatMessage>()) { result, item ->
                            if (!isDuplicateReward(item, result)) result += item
                            result
                        }.takeLast(maxSize).forEach { if (ids.add(it.id)) items.addLast(it) }
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
        clearedUsers: Collection<UserModeration>,
        globallyClearedAt: Long?,
    ): Boolean {
        if (message.id in deletedMessageIds) return true
        if (globallyClearedAt?.let { message.timestampMs <= it } == true) return true
        return clearedUsers.any {
            it.displayMode == ChatModerationDisplayMode.HIDE && it.matches(message)
        }
    }

    private fun decorate(message: ChatMessage, clearedUsers: Collection<UserModeration>): ChatMessage {
        if (message.moderation != null) return message
        val moderation = clearedUsers.asSequence()
            .filter { it.displayMode == ChatModerationDisplayMode.STRIKETHROUGH }
            .lastOrNull { it.matches(message) }
            ?.moderation
        return moderation?.let { message.copy(moderation = it) } ?: message
    }

    /** Hermes and chat.message can describe the same redemption without sharing an ID. */
    private fun isDuplicateReward(message: ChatMessage, existing: Collection<ChatMessage>): Boolean {
        val rewardId = message.rewardId ?: return false
        val userId = message.user?.id ?: return false
        return existing.any { other ->
            other.rewardId == rewardId &&
                other.user?.id == userId &&
                abs(other.timestampMs - message.timestampMs) <= REWARD_DUPLICATE_WINDOW_MS &&
                other.rawText.orEmpty() == message.rawText.orEmpty() &&
                when {
                    other.rewardRedemptionId != null && message.rewardRedemptionId != null ->
                        other.rewardRedemptionId == message.rewardRedemptionId
                    // Only correlate in the normal delivery direction: chat.message first,
                    // followed by the Hermes redemption event. If Hermes arrives first, keep a
                    // later ID-less reward because it may be a separate rapid redemption.
                    other.rewardRedemptionId == null && message.rewardRedemptionId != null -> true
                    else -> false
                }
        }
    }

    private fun trimModerationTombstones(
        deletedMessageIds: LinkedHashSet<ChatMessageId>,
        clearedUsers: ArrayDeque<UserModeration>,
    ) {
        while (deletedMessageIds.size > MODERATION_TOMBSTONE_LIMIT) {
            deletedMessageIds.remove(deletedMessageIds.first())
        }
        while (clearedUsers.size > MODERATION_TOMBSTONE_LIMIT) clearedUsers.removeFirst()
    }

    private data class UserModeration(
        val userId: String?,
        val userLogin: String?,
        val atMs: Long,
        val moderation: ChatModeration,
        val displayMode: ChatModerationDisplayMode,
    ) {
        fun matches(message: ChatMessage): Boolean =
            message.timestampMs <= atMs &&
                ((userId != null && message.user?.id == userId) ||
                    (userLogin != null && message.user?.login?.equals(userLogin, ignoreCase = true) == true))
    }

    private fun TimelineOperation.ClearUser.matches(message: ChatMessage): Boolean =
        message.timestampMs <= atMs &&
            ((userId != null && message.user?.id == userId) ||
                (userLogin != null && message.user?.login?.equals(userLogin, ignoreCase = true) == true))

    private companion object {
        const val MODERATION_TOMBSTONE_LIMIT = 4096
        const val REWARD_DUPLICATE_WINDOW_MS = 3_000L
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
            // Notice-only moderation keeps the timeline unchanged. Other display modes are
            // applied here so append/reconcile and live events share the same user boundary.
            is ChatEvent.ClearUser -> apply(
                TimelineOperation.ClearUser(
                    userId = event.userId,
                    userLogin = event.userLogin,
                    atMs = event.receivedAtMs,
                    moderation = ChatModeration(event.reason, event.timeoutSeconds),
                    displayMode = event.displayMode,
                ),
            )
            is ChatEvent.Clear -> apply(TimelineOperation.Clear(event.receivedAtMs))
            is ChatEvent.SettingsUpdated -> Unit
            is ChatEvent.DecorationUpdated -> Unit
            is ChatEvent.CommunityGift -> Unit
            is ChatEvent.TransportDisconnected -> Unit
        }
    }

}
