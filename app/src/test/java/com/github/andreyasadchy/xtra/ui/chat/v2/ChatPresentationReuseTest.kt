package com.github.andreyasadchy.xtra.ui.chat.v2

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPiece
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowBackground
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel
import com.github.andreyasadchy.xtra.ui.chat.v2.ui.ChatPresentationReuseIndex
import com.github.andreyasadchy.xtra.ui.chat.v2.ui.ChatAppendInfo
import com.github.andreyasadchy.xtra.ui.chat.v2.ui.compileChatRows
import com.github.andreyasadchy.xtra.ui.chat.v2.ui.compileChatRowAppend
import com.github.andreyasadchy.xtra.ui.chat.v2.ui.findChatAppendInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPresentationReuseTest {
    @Test
    fun appendCompilationVisitsAndAllocatesOnlyTheAppendedRows() {
        val appended = listOf(message(600), message(601))
        val result = compileChatRowAppend(
            messages = appended,
            startIndex = 600,
            retainedRows = 600,
            resolve = { message, _ -> row(message) },
        )

        assertEquals(2, result.rowsVisited)
        assertEquals(2, result.rowsAllocated)
        assertEquals(2, result.rowsCompiled)
        assertEquals(600, result.rowsReused)
        assertEquals(appended.map { it.id }, result.rows.map { it.id })
    }

    @Test
    fun normalAppendCompilesOnlyTheChangedRow() {
        val previous = (0 until 600).map(::message)
        val previousRows = previous.map(::row)
        val index = ChatPresentationReuseIndex()
        index.replace(previous, previousRows)

        var resolveCalls = 0
        val current = previous + message(600)
        val result = compileChatRows(
            messages = current,
            reuseIndex = index,
            resolve = { message, _ ->
                resolveCalls++
                row(message)
            },
        )

        assertEquals(1, result.messagesChanged)
        assertEquals(1, result.rowsCompiled)
        assertEquals(600, result.rowsReused)
        assertEquals(1, resolveCalls)
        previousRows.forEachIndexed { index, oldRow -> assertSame(oldRow, result.rows[index]) }
    }

    @Test
    fun equivalentPublicationReusesEveryRow() {
        val messages = (0 until 600).map(::message)
        val rows = messages.map(::row)
        val index = ChatPresentationReuseIndex()
        index.replace(messages, rows)

        val result = compileChatRows(
            messages = messages.toList(),
            reuseIndex = index,
            resolve = { message, _ -> row(message) },
        )

        assertEquals(0, result.messagesChanged)
        assertEquals(0, result.rowsCompiled)
        assertEquals(600, result.rowsReused)
        rows.forEachIndexed { index, oldRow -> assertSame(oldRow, result.rows[index]) }
    }

    @Test
    fun appendIndexUpdatesOnlyTheNewTailAndEvictedHead() {
        val previous = (0 until 600).map(::message)
        val previousRows = previous.map(::row)
        val index = ChatPresentationReuseIndex()
        index.replace(previous, previousRows)
        val current = previous.drop(1) + message(600)
        val currentRows = current.map(::row)

        assertTrue(index.append(current, currentRows, appendedCount = 1, evictedCount = 1))
        val result = compileChatRows(
            messages = current,
            reuseIndex = index,
            resolve = { message, _ -> row(message) },
        )

        assertEquals(0, result.rowsCompiled)
        assertEquals(600, result.rowsReused)
        assertSame(previousRows[1], result.rows.first())
        assertSame(currentRows.last(), result.rows.last())
    }

    @Test
    fun appendIndexRefreshesAChangedRetainedMessage() {
        val previous = (0 until 3).map(::message)
        val previousRows = previous.map(::row)
        val index = ChatPresentationReuseIndex()
        index.replace(previous, previousRows)
        val changed = message(1).copy(systemText = "updated")
        val current = listOf(previous[0], changed, previous[2], message(3))
        val result = compileChatRows(
            messages = current,
            reuseIndex = index,
            resolve = { message, _ -> row(message) },
        )

        assertEquals(2, result.rowsCompiled)
        assertTrue(index.append(current, result.rows, appendedCount = 1, evictedCount = 0))
        val next = compileChatRows(
            messages = current,
            reuseIndex = index,
            resolve = { message, _ -> row(message) },
        )
        assertEquals(0, next.rowsCompiled)
        assertEquals(4, next.rowsReused)
    }

    @Test
    fun appendShapeRejectsReplacementAndReconciliation() {
        val previous = (0 until 600).map(::message)
        assertEquals(
            ChatAppendInfo(appendedCount = 1, evictedCount = 1),
            findChatAppendInfo(previous, previous.drop(1) + message(600)),
        )
        val replaced = previous.drop(1).toMutableList().apply { this[100] = message(10_000) } + message(600)
        assertEquals(null, findChatAppendInfo(previous, replaced))
        assertEquals(null, findChatAppendInfo(previous, previous.take(599)))
    }

    @Test
    fun tenThousandSuccessiveAppendPublicationsCompileOnlyTheNewRow() {
        var messages = (0 until 600).map(::message)
        var rows = messages.map(::row)
        val index = ChatPresentationReuseIndex()
        index.replace(messages, rows)

        repeat(10_000) { publication ->
            val nextMessages = messages.drop(1) + message(600 + publication)
            val result = compileChatRows(
                messages = nextMessages,
                reuseIndex = index,
                resolve = { message, _ -> row(message) },
            )

            assertEquals(1, result.rowsCompiled)
            assertEquals(599, result.rowsReused)
            assertTrue(index.append(nextMessages, result.rows, appendedCount = 1, evictedCount = 1))
            rows = result.rows
            messages = nextMessages
        }
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

    private fun row(message: ChatMessage) = ChatRowUiModel(
        id = message.id,
        channelId = message.channelId,
        timestampText = null,
        pieces = listOf(ChatPiece.Text(message.id.value)),
        background = 0,
        backgroundStyle = ChatRowBackground.NORMAL,
        accessibilityText = message.id.value,
        reply = null,
        source = null,
        isAction = false,
    )
}
