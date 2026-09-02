package com.github.andreyasadchy.xtra.ui.chat.v2

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.ui.countNewLiveMessages
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatV2PublicationSemanticsTest {
    @Test
    fun recoveredOlderHistoryDoesNotCountAsNewLiveMessages() {
        val visible = (100..200).map(::message)
        val reconciled = (50..200).map(::message)

        assertEquals(
            0,
            countNewLiveMessages(
                previousIds = visible.map { it.id }.toSet(),
                previousTailId = visible.last().id,
                messages = reconciled,
            ),
        )
    }

    @Test
    fun messageAfterPreviousTailCountsAsNew() {
        val visible = (100..200).map(::message)
        val appended = (100..201).map(::message)

        assertEquals(
            1,
            countNewLiveMessages(
                previousIds = visible.map { it.id }.toSet(),
                previousTailId = visible.last().id,
                messages = appended,
            ),
        )
    }

    @Test
    fun messageWithSameTimestampAfterPreviousTailCountsAsNew() {
        val previous = message("A", 1_000L)
        val current = listOf(previous, message("B", 1_000L))

        assertEquals(
            1,
            countNewLiveMessages(
                previousIds = setOf(previous.id),
                previousTailId = previous.id,
                messages = current,
            ),
        )
    }

    private fun message(index: Int) = ChatMessage(
        id = ChatMessageId(index.toString()),
        channelId = "channel",
        timestampMs = index.toLong(),
        user = null,
        badges = emptyList(),
        segments = emptyList(),
        kind = ChatMessageKind.CHAT,
    )

    private fun message(id: String, timestampMs: Long) = ChatMessage(
        id = ChatMessageId(id),
        channelId = "channel",
        timestampMs = timestampMs,
        user = null,
        badges = emptyList(),
        segments = emptyList(),
        kind = ChatMessageKind.CHAT,
    )
}
