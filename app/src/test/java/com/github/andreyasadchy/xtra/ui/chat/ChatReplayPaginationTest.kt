package com.github.andreyasadchy.xtra.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReplayPaginationTest {
    private data class Message(val id: String, val offsetSeconds: Int)

    @Test
    fun cursorFailureSticksToOffsetPagination() {
        val pagination = ChatReplayPagination()
        val firstPage = listOf(Message("a", 10), Message("b", 10))

        pagination.acceptPage(
            request = ChatReplayPageRequest.Offset(10),
            messages = firstPage,
            hasNextPage = true,
            nextCursor = "cursor-2",
            messageId = Message::id,
            contentOffsetSeconds = Message::offsetSeconds,
        )
        assertEquals(ChatReplayPageRequest.Cursor("cursor-2"), pagination.request(null))

        pagination.onCursorFailure()

        assertTrue(pagination.offsetPagination)
        assertEquals(ChatReplayPageRequest.Offset(10), pagination.request(null))

        pagination.acceptPage(
            request = ChatReplayPageRequest.Offset(10),
            messages = listOf(Message("b", 10), Message("c", 10)),
            hasNextPage = true,
            nextCursor = "cursor-3",
            messageId = Message::id,
            contentOffsetSeconds = Message::offsetSeconds,
        )
        assertEquals(ChatReplayPageRequest.Offset(10), pagination.request(null))
        assertTrue(pagination.hasMorePages)
    }

    @Test
    fun sameSecondBoundaryRetainsOverlapAndUsesInclusiveOffset() {
        val pagination = ChatReplayPagination()
        val firstPage = listOf(Message("a", 20), Message("b", 20))
        val secondPage = listOf(Message("b", 20), Message("c", 20))

        assertEquals(
            firstPage,
            pagination.acceptPage(
                request = ChatReplayPageRequest.Offset(20),
                messages = firstPage,
                hasNextPage = true,
                nextCursor = "cursor-2",
                messageId = Message::id,
                contentOffsetSeconds = Message::offsetSeconds,
            ),
        )
        pagination.onCursorFailure()
        assertEquals(ChatReplayPageRequest.Offset(20), pagination.request(null))
        assertEquals(
            listOf(Message("c", 20)),
            pagination.acceptPage(
                request = ChatReplayPageRequest.Offset(20),
                messages = secondPage,
                hasNextPage = false,
                nextCursor = null,
                messageId = Message::id,
                contentOffsetSeconds = Message::offsetSeconds,
            ),
        )
        assertFalse(pagination.hasMorePages)
    }

    @Test
    fun overlappingCursorPageWithAdvancingCursorContinues() {
        val pagination = ChatReplayPagination()
        val page = listOf(Message("a", 30), Message("b", 30))

        pagination.acceptPage(
            request = ChatReplayPageRequest.Offset(30),
            messages = page,
            hasNextPage = true,
            nextCursor = "cursor-2",
            messageId = Message::id,
            contentOffsetSeconds = Message::offsetSeconds,
        )
        assertEquals(
            emptyList<Message>(),
            pagination.acceptPage(
                request = ChatReplayPageRequest.Cursor("cursor-2"),
                messages = listOf(Message("b", 30)),
                hasNextPage = true,
                nextCursor = "cursor-3",
                messageId = Message::id,
                contentOffsetSeconds = Message::offsetSeconds,
            ),
        )
        assertTrue(pagination.hasMorePages)
        assertEquals(ChatReplayPageRequest.Cursor("cursor-3"), pagination.request(null))
    }

    @Test
    fun unchangedCursorFallsBackToInclusiveOffsetPagination() {
        val pagination = ChatReplayPagination()
        val page = listOf(Message("a", 30), Message("b", 30))

        pagination.acceptPage(
            request = ChatReplayPageRequest.Offset(30),
            messages = page,
            hasNextPage = true,
            nextCursor = "cursor-2",
            messageId = Message::id,
            contentOffsetSeconds = Message::offsetSeconds,
        )
        assertEquals(ChatReplayPageRequest.Cursor("cursor-2"), pagination.request(null))

        assertEquals(
            emptyList<Message>(),
            pagination.acceptPage(
                request = ChatReplayPageRequest.Cursor("cursor-2"),
                messages = page,
                hasNextPage = true,
                nextCursor = "cursor-2",
                messageId = Message::id,
                contentOffsetSeconds = Message::offsetSeconds,
            ),
        )
        assertTrue(pagination.offsetPagination)
        assertTrue(pagination.hasMorePages)
        assertEquals(ChatReplayPageRequest.Offset(30), pagination.request(null))
    }

    @Test
    fun repeatedOffsetBoundaryEscapesOnceAndContinuesWhenNewMessagesAppear() {
        val pagination = ChatReplayPagination()
        val page = listOf(Message("a", 30), Message("b", 30))
        val escapedOffsets = mutableListOf<Pair<Int, Int>>()

        pagination.acceptPage(
            request = ChatReplayPageRequest.Offset(30),
            messages = page,
            hasNextPage = true,
            nextCursor = "cursor-2",
            messageId = Message::id,
            contentOffsetSeconds = Message::offsetSeconds,
        )
        pagination.onCursorFailure()

        pagination.acceptPage(
            request = ChatReplayPageRequest.Offset(30),
            messages = page,
            hasNextPage = true,
            nextCursor = null,
            messageId = Message::id,
            contentOffsetSeconds = Message::offsetSeconds,
            onForcedBoundaryEscape = { from, to -> escapedOffsets += from to to },
        )
        assertEquals(listOf(30 to 31), escapedOffsets)
        assertEquals(ChatReplayPageRequest.Offset(31), pagination.request(null))

        assertEquals(
            listOf(Message("c", 31)),
            pagination.acceptPage(
                request = ChatReplayPageRequest.Offset(31),
                messages = listOf(Message("b", 30), Message("c", 31)),
                hasNextPage = true,
                nextCursor = null,
                messageId = Message::id,
                contentOffsetSeconds = Message::offsetSeconds,
            ),
        )
        assertTrue(pagination.hasMorePages)
    }

    @Test
    fun repeatedOffsetBoundaryStopsAfterTheControlledEscape() {
        val pagination = ChatReplayPagination()
        val page = listOf(Message("a", 30), Message("b", 30))

        pagination.acceptPage(
            request = ChatReplayPageRequest.Offset(30),
            messages = page,
            hasNextPage = true,
            nextCursor = null,
            messageId = Message::id,
            contentOffsetSeconds = Message::offsetSeconds,
        )
        pagination.onCursorFailure()
        pagination.acceptPage(
            request = ChatReplayPageRequest.Offset(30),
            messages = page,
            hasNextPage = true,
            nextCursor = null,
            messageId = Message::id,
            contentOffsetSeconds = Message::offsetSeconds,
        )
        assertEquals(ChatReplayPageRequest.Offset(31), pagination.request(null))

        pagination.acceptPage(
            request = ChatReplayPageRequest.Offset(31),
            messages = page,
            hasNextPage = true,
            nextCursor = null,
            messageId = Message::id,
            contentOffsetSeconds = Message::offsetSeconds,
        )
        assertFalse(pagination.hasMorePages)
    }
}
