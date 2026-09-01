package com.github.andreyasadchy.xtra.ui.chat.v2

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatBadgeRef
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReply
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUser
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.TwitchChatMessageType
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPiece
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowCompiler
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPresentationResolver
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatColorResolver
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogEmote
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDomainPresentationTest {
    private val base = ChatAssetSpec(ChatAssetKey("base"), 20, 20, 28)

    @Test
    fun gifRetainsExactUrlAndFixedGeometry() {
        val url = "https://cdn.example.test/gifs/a?format=gif&x=1"
        val row = ChatRowCompiler().compile(message(ChatSegment.Gif("gif-1", url, "party")))
        val gif = row.pieces.filterIsInstance<ChatPiece.Gif>().single()
        assertEquals(url, gif.url)
        assertEquals(url, gif.asset.key.value)
        assertEquals(28, gif.asset.targetHeight)
    }

    @Test
    fun nativeTypeAndCompleteReplySurviveCompilation() {
        val reply = ChatReply(
            parentMessageId = ChatMessageId("parent"), parentMessageBody = "hello",
            parentUserId = "u1", parentUserName = "Parent", parentUserLogin = "parent",
            threadMessageId = ChatMessageId("thread"), threadUserId = "u2",
            threadUserName = "Thread", threadUserLogin = "thread",
        )
        val message = message(ChatSegment.Text("hi")).copy(
            reply = reply,
            twitchType = TwitchChatMessageType.GigantifiedEmote,
        )
        val row = ChatRowCompiler().compile(message)
        assertEquals(TwitchChatMessageType.GigantifiedEmote, row.twitchType)
        assertEquals(reply, row.reply)
    }

    @Test
    fun cheerUsesNumericBitsAndAccessibilityAmount() {
        val row = ChatRowCompiler().compile(message(ChatSegment.Cheermote(base, "Cheer100", 100, 0xffff0000.toInt())))
        val cheer = row.pieces.filterIsInstance<ChatPiece.Cheermote>().single()
        assertEquals(100, cheer.bits)
        assertTrue(row.accessibilityText.contains("100 Bits"))
        assertTrue(!row.accessibilityText.contains("Cheer100 100"))
    }

    @Test
    fun overlaysRetainCompositionGeometryAndScaleTogether() {
        val overlay = ChatAssetSpec(ChatAssetKey("wide"), 80, 20, 12)
        val nested = ChatAssetSpec(ChatAssetKey("tall"), 10, 40, 12)
        val spec = base.copy(overlays = listOf(overlay.copy(overlays = listOf(nested))))
        val row = ChatRowCompiler().compile(message(ChatSegment.Emote(spec, ":x:", false)))
        val compiled = row.pieces.filterIsInstance<ChatPiece.Emote>().single().asset
        assertEquals(28, compiled.targetHeight)
        assertTrue(compiled.overlays.all { it.targetHeight == 28 })
        assertTrue(compiled.compositionWidth >= compiled.overlays[0].computedWidth)
        assertTrue(compiled.compositionHeight >= compiled.overlays[0].compositionHeight)
    }

    @Test
    fun thirdPartyTextUsesCurrentCatalogAndCanBeRecompiledWithoutTransportReplay() {
        val message = message(ChatSegment.Text("hello Party time"))
        val resolver = ChatPresentationResolver()
        val noCatalog = resolver.resolve(message, ChatCatalogSnapshot(1))
        assertTrue(noCatalog.pieces.any { it is ChatPiece.Text && it.value.contains("Party") })

        val catalog = ChatCatalogSnapshot(
            revision = 2,
            sevenTv = mapOf("Party" to emote("Party", ChatAssetProvider.SEVEN_TV)),
        )
        val withCatalog = resolver.resolve(message, catalog)
        assertTrue(withCatalog.pieces.any { it is ChatPiece.Emote && it.fallback == "Party" })
        assertEquals(message.id, withCatalog.id)
    }

    @Test
    fun zeroWidthModifierComposesAfterNativeOrThirdPartyEmote() {
        val modifier = emote("modifier", ChatAssetProvider.SEVEN_TV).copy(zeroWidth = true)
        val catalog = ChatCatalogSnapshot(
            revision = 1,
            sevenTv = mapOf("ZW" to modifier),
        )
        val nativeThenModifier = message(ChatSegment.Emote(base, "Kappa", false)).copy(
            segments = listOf(ChatSegment.Emote(base, "Kappa", false), ChatSegment.Text(" ZW")),
        )
        val nativeRow = ChatPresentationResolver().resolve(nativeThenModifier, catalog)
        val nativeEmote = nativeRow.pieces.filterIsInstance<ChatPiece.Emote>().single()
        assertEquals(1, nativeEmote.asset.overlays.size)

        val thirdPartyRow = ChatPresentationResolver().resolve(message(ChatSegment.Text("Party ZW")), catalog.copy(
            sevenTv = catalog.sevenTv + ("Party" to emote("Party", ChatAssetProvider.SEVEN_TV)),
        ))
        assertEquals(1, thirdPartyRow.pieces.filterIsInstance<ChatPiece.Emote>().single().asset.overlays.size)
    }

    @Test
    fun providerPrecedenceIsDeterministicAndCatalogReplacementTakesEffect() {
        val message = message(ChatSegment.Text("same"))
        val seven = emote("same", ChatAssetProvider.SEVEN_TV)
        val bttv = emote("same", ChatAssetProvider.BTTV)
        val ffz = emote("same", ChatAssetProvider.FFZ)
        val first = ChatPresentationResolver().resolve(message, ChatCatalogSnapshot(1, sevenTv = mapOf("same" to seven), bttv = mapOf("same" to bttv), ffz = mapOf("same" to ffz)))
        assertEquals(seven.asset.key, (first.pieces.single() as ChatPiece.Emote).asset.key)
        val replaced = ChatPresentationResolver().resolve(message, ChatCatalogSnapshot(2, ffz = mapOf("same" to ffz)))
        assertEquals(ffz.asset.key, (replaced.pieces.single() as ChatPiece.Emote).asset.key)
    }

    @Test
    fun badgeIdentityAndGeometryExistBeforeBadgeCatalogLoads() {
        val badge = ChatBadgeRef("subscriber", "12", "Subscriber")
        val row = ChatPresentationResolver().resolve(message(ChatSegment.Text("hi")).copy(badges = listOf(badge)), ChatCatalogSnapshot(0))
        val piece = row.pieces.filterIsInstance<ChatPiece.Badge>().single()
        assertEquals("twitch-badge:subscriber:12", piece.asset.key.value)
        assertEquals(18, piece.asset.targetHeight)
    }

    @Test
    fun usernameFallbackIsExplicitStableContrastingAndIdentityBased() {
        val colors = ChatColorResolver(background = 0xFF101010.toInt())
        val missing = colors.resolve(null, "user-a")
        assertTrue(missing != 0xFFFFFFFF.toInt())
        assertEquals(missing, colors.resolve("", "user-a"))
        assertTrue(missing != colors.resolve(null, "user-b"))
        assertEquals(missing, colors.resolve("#000000", "user-a"))
        assertEquals(missing, colors.resolve("#FFFFFF", "user-a"))
    }

    @Test
    fun unknownFutureMessageTypeRemainsRepresentable() {
        val type = TwitchChatMessageType.Unknown("future_power_up")
        val row = ChatPresentationResolver().resolve(message(ChatSegment.Text("hello")).copy(twitchType = type), ChatCatalogSnapshot(1))
        assertEquals(type, row.twitchType)
    }

    private fun emote(name: String, provider: ChatAssetProvider) = ChatCatalogEmote(name, base.copy(key = ChatAssetKey(name)), provider, animated = false)

    private fun message(segment: ChatSegment) = ChatMessage(
        id = ChatMessageId("id"), channelId = "channel", timestampMs = 1,
        user = null, badges = emptyList(),
        segments = listOf(segment), kind = ChatMessageKind.CHAT,
    )
}
