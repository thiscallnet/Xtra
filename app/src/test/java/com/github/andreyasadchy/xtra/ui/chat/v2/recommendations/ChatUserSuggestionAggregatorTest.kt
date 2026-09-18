package com.github.andreyasadchy.xtra.ui.chat.v2.recommendations

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUser
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatUserSuggestionAggregatorTest {
    private val aggregator = ChatUserSuggestionAggregator()

    @Test
    fun `rebuilding from the current window drops evicted users and does not double count`() {
        val initial = aggregator.aggregate(
            listOf(
                message("alice-1", "alice", "Alice", 100),
                message("bob-1", "bob", "Bob", 200),
            ),
        )
        val current = listOf(
            message("bob-1", "bob", "Bob", 200),
            message("bob-2", "bob", "Bob", 300),
        )

        val rebuilt = aggregator.aggregate(current, initial.associateBy { it.login })
        val repeated = aggregator.aggregate(current, rebuilt.associateBy { it.login })

        assertEquals(listOf("bob"), rebuilt.map { it.login })
        assertEquals(2, rebuilt.single().messageCount)
        assertEquals(300L, rebuilt.single().lastSeenAt)
        assertEquals(rebuilt, repeated)
    }

    @Test
    fun `requires a login and keeps the newest timestamp in the window`() {
        val result = aggregator.aggregate(
            listOf(
                message("new", "viewer", "Viewer", 300),
                message("old", "viewer", "Viewer", 100),
                message("notice", null, "System", 400, ChatMessageKind.NOTICE),
            ),
        )

        assertEquals(listOf("viewer"), result.map { it.login })
        assertEquals(2, result.single().messageCount)
        assertEquals(300L, result.single().lastSeenAt)
    }

    private fun message(
        id: String,
        login: String?,
        displayName: String,
        timestamp: Long,
        kind: ChatMessageKind = ChatMessageKind.CHAT,
    ) = ChatMessage(
        id = ChatMessageId(id),
        channelId = "channel",
        timestampMs = timestamp,
        user = login?.let { ChatUser(id = it, login = it, displayName = displayName, color = null) },
        badges = emptyList(),
        segments = listOf(ChatSegment.Text("message")),
        kind = kind,
    )
}
