package com.github.andreyasadchy.xtra.ui.chat.v2.recommendations

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import java.util.LinkedHashMap
import java.util.Locale

/** Builds the user index from the authoritative, bounded V2 timeline window. */
class ChatUserSuggestionAggregator {
    fun aggregate(
        messages: List<ChatMessage>,
        previous: Map<String, ChatUserSuggestion> = emptyMap(),
        avatarFor: (userId: String?, login: String) -> String? = { _, _ -> null },
    ): List<ChatUserSuggestion> {
        val records = LinkedHashMap<String, ChatUserSuggestion>()
        messages.forEachIndexed { index, message ->
            if (message.kind != ChatMessageKind.CHAT && message.kind != ChatMessageKind.ACTION) return@forEachIndexed
            val user = message.user ?: return@forEachIndexed
            val login = user.login?.trim()?.takeIf(String::isNotBlank) ?: return@forEachIndexed
            val key = login.lowercase(Locale.ROOT)
            val aggregate = records[key]
            val previousUser = previous[key]
            records[key] = ChatUserSuggestion(
                userId = user.id ?: aggregate?.userId ?: previousUser?.userId,
                login = login,
                displayName = user.displayName?.trim()?.takeIf(String::isNotBlank)
                    ?: aggregate?.displayName
                    ?: previousUser?.displayName
                    ?: login,
                profileImageUrl = aggregate?.profileImageUrl
                    ?: previousUser?.profileImageUrl
                    ?: avatarFor(user.id, login),
                messageCount = (aggregate?.messageCount ?: 0) + 1,
                lastSeenAt = maxOf(
                    aggregate?.lastSeenAt ?: 0L,
                    message.timestampMs,
                    index.toLong(),
                ),
            )
        }
        return records.values.toList()
    }
}
