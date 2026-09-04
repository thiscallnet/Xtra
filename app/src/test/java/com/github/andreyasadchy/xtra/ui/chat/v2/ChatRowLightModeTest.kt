package com.github.andreyasadchy.xtra.ui.chat.v2

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReply
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatColorResolver
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPiece
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ChatRowLightModeTest {
    private val lightBackground = 0xFFFEF7FF.toInt()
    private val darkBackground = 0xFF101010.toInt()
    private val resolver = ChatColorResolver()

    @Test
    fun mutedTextAdaptsToRowBackground() {
        assertEquals(0xFF5F5B66.toInt(), resolver.mutedTextColor(lightBackground))
        assertEquals(0xFFC4BEC9.toInt(), resolver.mutedTextColor(darkBackground))
    }

    @Test
    fun brightHeadingTextAdaptsToRowBackground() {
        assertEquals(0xFF1F1B24.toInt(), resolver.brightTextColor(lightBackground))
        assertEquals(0xFFE8E4EC.toInt(), resolver.brightTextColor(darkBackground))
    }

    @Test
    fun lightModeSecondaryTextKeepsReadableContrast() {
        assertTrue(contrast(resolver.mutedTextColor(lightBackground), lightBackground) >= 4.5)
        assertTrue(contrast(resolver.brightTextColor(lightBackground), lightBackground) >= 4.5)
    }

    @Test
    fun replyPreviewUsesMutedColorForTheRowBackground() {
        val lightRow = ChatRowCompiler(background = { lightBackground }).compile(
            message(ChatSegment.Text("hello")).copy(reply = reply()),
        )
        val darkRow = ChatRowCompiler(background = { darkBackground }).compile(
            message(ChatSegment.Text("hello")).copy(reply = reply()),
        )

        assertEquals(
            0xFF5F5B66.toInt(),
            lightRow.pieces.filterIsInstance<ChatPiece.Reply>().single().color,
        )
        assertEquals(
            0xFFC4BEC9.toInt(),
            darkRow.pieces.filterIsInstance<ChatPiece.Reply>().single().color,
        )
    }

    @Test
    fun sharedChatSourceUsesMutedColorForTheRowBackground() {
        val lightRow = ChatRowCompiler(background = { lightBackground }).compile(
            message(ChatSegment.Text("hello")).copy(
                source = com.github.andreyasadchy.xtra.ui.chat.v2.domain.SharedChatSource(
                    broadcasterId = "source",
                    broadcasterLogin = "source_login",
                    broadcasterName = "Source",
                    messageId = null,
                    badges = emptyList(),
                    sourceOnly = false,
                ),
            ),
        )

        assertEquals(
            0xFF5F5B66.toInt(),
            lightRow.pieces.filterIsInstance<ChatPiece.Source>().single().color,
        )
    }

    private fun message(segment: ChatSegment) = ChatMessage(
        id = ChatMessageId("id"), channelId = "channel", timestampMs = 1,
        user = null, badges = emptyList(),
        segments = listOf(segment), kind = ChatMessageKind.CHAT,
    )

    private fun reply() = ChatReply(
        parentMessageId = ChatMessageId("parent"),
        parentMessageBody = "original",
        parentUserId = "user",
        parentUserName = ".somebody",
        parentUserLogin = "somebody",
        threadMessageId = null,
        threadUserId = null,
        threadUserName = null,
        threadUserLogin = null,
    )

    private fun contrast(foreground: Int, background: Int): Double {
        val foregroundLuminance = luminance(foreground)
        val backgroundLuminance = luminance(background)
        return (maxOf(foregroundLuminance, backgroundLuminance) + 0.05) /
            (minOf(foregroundLuminance, backgroundLuminance) + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.03928) normalized / 12.92 else ((normalized + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color ushr 16 and 0xff) +
            0.7152 * channel(color ushr 8 and 0xff) +
            0.0722 * channel(color and 0xff)
    }
}
