package com.github.andreyasadchy.xtra.ui.chat.v2

import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot
import com.github.andreyasadchy.xtra.ui.chat.ChatHighlightSettings
import com.github.andreyasadchy.xtra.ui.chat.DEFAULT_CHAT_HIGHLIGHT_COLOR
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReply
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReward
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUser
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.HIGHLIGHTED_MESSAGE_REWARD_TYPE
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.TwitchChatMessageType
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
    fun personalHighlightCompositesBeforeChoosingSecondaryText() {
        val row = ChatRowCompiler(
            background = { lightBackground },
            highlightSettings = ChatHighlightSettings(
                color = DEFAULT_CHAT_HIGHLIGHT_COLOR,
                viewerLogin = "somebody",
            ),
        ).compile(
            message(ChatSegment.Text("hello")).copy(reply = reply()),
        )
        val renderedBackground = composite(DEFAULT_CHAT_HIGHLIGHT_COLOR, lightBackground)
        val replyColor = row.pieces.filterIsInstance<ChatPiece.Reply>().single().color

        assertEquals(0xFFB28286.toInt(), renderedBackground)
        assertTrue(replyColor != 0xFF5F5B66.toInt())
        assertTrue(contrast(replyColor, renderedBackground) >= 4.5)
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

    @Test
    fun highlightedHeadingAdaptsToRowBackground() {
        val catalog = ChatCatalogSnapshot(
            0,
            automaticChannelPointRewards = mapOf(
                HIGHLIGHTED_MESSAGE_REWARD_TYPE to ChatReward("Highlight My Message", 2_000, null),
            ),
        )
        val lightRow = ChatRowCompiler(background = { lightBackground }).compile(
            highlightedMessage(),
            catalog,
        )
        val darkRow = ChatRowCompiler(background = { darkBackground }).compile(
            highlightedMessage(),
            catalog,
        )

        val lightHeading = lightRow.pieces.filterIsInstance<ChatPiece.Text>()
            .filter { it.value.contains("Redeemed") || it.value.contains("Highlight My Message") || it.value.filter(Char::isDigit).contains("2000") }
        assertTrue(lightHeading.isNotEmpty())
        lightHeading.forEach { assertEquals(0xFF1F1B24.toInt(), it.color) }
        assertEquals(
            0xFF1F1B24.toInt(),
            lightRow.pieces.filterIsInstance<ChatPiece.Icon>().single().tint,
        )
        assertTrue(
            darkRow.pieces.filterIsInstance<ChatPiece.Text>()
                .filter { it.value.contains("Highlight My Message") }
                .all { it.color == 0xFFE8E4EC.toInt() },
        )
    }

    private fun highlightedMessage() = message(ChatSegment.Text("Lock them up!")).copy(
        user = ChatUser("user", "viewer", "Viewer", null),
        rewardId = null,
        twitchType = TwitchChatMessageType.Highlighted,
    )

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

    private fun composite(foreground: Int, background: Int): Int {
        val foregroundAlpha = foreground ushr 24 and 0xff
        val backgroundAlpha = background ushr 24 and 0xff
        val outputAlpha = 255 - ((255 - foregroundAlpha) * (255 - backgroundAlpha) / 255)
        if (outputAlpha == 0) return 0

        fun component(foregroundComponent: Int, backgroundComponent: Int): Int =
            (foregroundComponent * foregroundAlpha * 255 +
                backgroundComponent * backgroundAlpha * (255 - foregroundAlpha)) /
                (outputAlpha * 255)

        return (outputAlpha shl 24) or
            (component(foreground ushr 16 and 0xff, background ushr 16 and 0xff) shl 16) or
            (component(foreground ushr 8 and 0xff, background ushr 8 and 0xff) shl 8) or
            component(foreground and 0xff, background and 0xff)
    }
}
