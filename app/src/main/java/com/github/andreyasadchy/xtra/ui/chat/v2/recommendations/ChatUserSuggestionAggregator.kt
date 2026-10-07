package com.github.andreyasadchy.xtra.ui.chat.v2.recommendations

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUser
import java.util.LinkedHashMap
import java.util.Locale

/** Builds the user index from the bounded V2 timeline and the current chatter snapshot. */
class ChatUserSuggestionAggregator {
    private data class Contribution(val user: ChatUser, val login: String, val timestamp: Long) {
        val key = login.lowercase(Locale.ROOT)
    }
    private class UserMessages {
        val messages = java.util.TreeMap<Long, Contribution>()
        var suggestion: ChatUserSuggestion? = null
        var newestTimestamp = 0L
    }
    private val timeline = java.util.ArrayDeque<Long>()
    private val contributions = HashMap<Long, Contribution>()
    private val users = HashMap<String, UserMessages>()
    private var sequence = 0L

    fun clear() {
        timeline.clear()
        contributions.clear()
        users.clear()
        sequence = 0L
    }

    /** Applies validated head evictions/tail appends without revisiting retained messages. */
    fun reconcile(
        messages: List<ChatMessage>,
        previous: Map<String, ChatUserSuggestion>,
        presence: Collection<ChatUserSuggestion>,
        avatarFor: (String?, String) -> String?,
        evictedHeadCount: Int? = null,
        appendedCount: Int? = null,
    ): List<ChatUserSuggestion> {
        val incremental = evictedHeadCount != null && appendedCount != null &&
                evictedHeadCount in 0..timeline.size && appendedCount in 0..messages.size &&
                timeline.size - evictedHeadCount + appendedCount == messages.size
        val changed = HashSet<String>()
        if (incremental) {
            repeat(requireNotNull(evictedHeadCount)) {
                val ordinal = timeline.removeFirst()
                contributions.remove(ordinal)?.let { entry ->
                    users[entry.key]?.messages?.remove(ordinal)
                    changed.add(entry.key)
                }
            }
        } else {
            clear()
        }
        val start = if (incremental) messages.size - requireNotNull(appendedCount) else 0
        for (index in start until messages.size) {
            val message = messages[index]
            val ordinal = sequence++
            timeline.addLast(ordinal)
            if (message.kind != ChatMessageKind.CHAT && message.kind != ChatMessageKind.ACTION) continue
            val author = message.user ?: continue
            val login = author.login?.trim()?.takeIf(String::isNotBlank) ?: continue
            val entry = Contribution(author, login, message.timestampMs)
            contributions[ordinal] = entry
            users.getOrPut(entry.key, ::UserMessages).messages[ordinal] = entry
            changed.add(entry.key)
        }
        users.entries.removeAll { it.value.messages.isEmpty() }
        val records = LinkedHashMap<String, ChatUserSuggestion>()
        val origin = timeline.peekFirst() ?: 0L
        users.entries.sortedBy { it.value.messages.firstKey() }.forEach { (key, bucket) ->
            if (key in changed || bucket.suggestion == null) {
                var userId: String? = null
                var displayName: String? = null
                var profileImageUrl: String? = null
                var login = key
                var newestTimestamp = 0L
                bucket.messages.values.forEach { entry ->
                    userId = entry.user.id ?: userId ?: previous[key]?.userId
                    login = entry.login
                    displayName = entry.user.displayName?.trim()?.takeIf(String::isNotBlank)
                        ?: displayName ?: previous[key]?.displayName ?: login
                    profileImageUrl = profileImageUrl ?: previous[key]?.profileImageUrl
                    newestTimestamp = maxOf(newestTimestamp, entry.timestamp)
                }
                profileImageUrl = profileImageUrl ?: avatarFor(userId, login)
                bucket.suggestion = ChatUserSuggestion(
                    userId = userId,
                    login = login,
                    displayName = requireNotNull(displayName),
                    profileImageUrl = profileImageUrl,
                    messageCount = bucket.messages.size,
                    lastSeenAt = 0L,
                )
                bucket.newestTimestamp = newestTimestamp
            }
            val record = requireNotNull(bucket.suggestion)
            records[key] = record.copy(
                profileImageUrl = previous[key]?.profileImageUrl ?: record.profileImageUrl,
                lastSeenAt = maxOf(bucket.newestTimestamp, bucket.messages.lastKey() - origin),
            )
        }
        presence.forEach { presentUser ->
            val key = presentUser.login.lowercase(Locale.ROOT)
            val aggregate = records[key]
            val existing = aggregate ?: previous[key]
            records[key] = ChatUserSuggestion(
                userId = existing?.userId ?: presentUser.userId,
                login = existing?.login ?: presentUser.login,
                displayName = existing?.displayName?.takeIf(String::isNotBlank)
                    ?: presentUser.displayName.takeIf(String::isNotBlank) ?: presentUser.login,
                profileImageUrl = existing?.profileImageUrl ?: presentUser.profileImageUrl
                    ?: if (aggregate == null) avatarFor(presentUser.userId, presentUser.login) else null,
                messageCount = aggregate?.messageCount ?: presentUser.messageCount,
                lastSeenAt = maxOf(aggregate?.lastSeenAt ?: 0L, presentUser.lastSeenAt),
            )
        }
        return records.values.toList()
    }

    fun aggregate(
        messages: List<ChatMessage>,
        previous: Map<String, ChatUserSuggestion> = emptyMap(),
        presence: Collection<ChatUserSuggestion> = emptyList(),
        avatarFor: (userId: String?, login: String) -> String? = { _, _ -> null },
    ): List<ChatUserSuggestion> = reconcile(messages, previous, presence, avatarFor)
}
