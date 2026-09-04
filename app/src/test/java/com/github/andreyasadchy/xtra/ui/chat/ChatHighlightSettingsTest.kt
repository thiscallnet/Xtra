package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.ChatMessage as LegacyChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHighlightSettingsTest {

    private val settings = ChatHighlightSettings(viewerLogin = "someuser")

    @Test
    fun `plain text mentions highlight in both renderers`() {
        assertTrue(shouldHighlightLegacyChatMessage(legacyMessage("hello someuser"), settings))
        assertTrue(shouldHighlightV2ChatMessage(v2Message(ChatSegment.Text("hello someuser")), settings))
    }

    @Test
    fun `mentions inside urls do not highlight`() {
        listOf(
            "https://twitch.tv/someuser",
            "example.com/someuser",
        ).forEach { text ->
            assertFalse(shouldHighlightLegacyChatMessage(legacyMessage(text), settings))
            assertFalse(shouldHighlightV2ChatMessage(v2Message(ChatSegment.Text(text)), settings))
        }
    }

    @Test
    fun `emote fallback text is not treated as a mention in v2`() {
        val emote = ChatSegment.Emote(
            asset = ChatAssetSpec(ChatAssetKey("test-emote"), sourceWidth = 1, sourceHeight = 1, targetHeight = 1),
            fallbackText = "someuser",
            animated = false,
        )

        assertFalse(shouldHighlightV2ChatMessage(v2MessageWithRawText("someuser", emote), settings))
        assertTrue(shouldHighlightV2ChatMessage(v2Message(emote, ChatSegment.Text("someuser")), settings))
    }

    @Test
    fun `bare name matching can be disabled`() {
        val noBareNames = settings.copy(matchMentionsWithoutAt = false)

        assertFalse(shouldHighlightLegacyChatMessage(legacyMessage("hello someuser"), noBareNames))
        assertFalse(shouldHighlightV2ChatMessage(v2Message(ChatSegment.Text("hello someuser")), noBareNames))
        assertTrue(shouldHighlightV2ChatMessage(v2Message(ChatSegment.Text("hello @someuser")), noBareNames))
    }

    private fun legacyMessage(text: String) = LegacyChatMessage(
        type = LegacyChatMessage.USER_MESSAGE,
        userId = "other-id",
        userLogin = "other-user",
        message = text,
    )

    private fun v2Message(vararg segments: ChatSegment) = ChatMessage(
        id = ChatMessageId("message-id"),
        channelId = "channel-id",
        timestampMs = 0L,
        user = ChatUser(id = "other-id", login = "other-user", displayName = "Other User", color = null),
        badges = emptyList(),
        segments = segments.toList(),
        kind = ChatMessageKind.CHAT,
    )

    private fun v2MessageWithRawText(rawText: String, vararg segments: ChatSegment) =
        v2Message(*segments).copy(rawText = rawText)
}
