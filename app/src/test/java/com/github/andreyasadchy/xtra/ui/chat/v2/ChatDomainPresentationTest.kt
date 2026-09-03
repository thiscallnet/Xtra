package com.github.andreyasadchy.xtra.ui.chat.v2

import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatBadgeRef
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEmoteInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReply
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReward
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUser
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.TwitchChatMessageType
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPiece
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowBackground
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowCompiler
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPresentationResolver
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatColorResolver
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogEmote
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogBadge
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatEmoteScope
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatNamePaint
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatUserDecoration
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ScopedEmoteCatalog
import com.github.andreyasadchy.xtra.ui.chat.ChatGifDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
        assertEquals(180, gif.asset.targetHeight)
        assertEquals("gif-1", gif.interaction?.id)
        assertEquals("party", gif.interaction?.description)
        assertEquals(url, gif.interaction?.url)
    }

    @Test
    fun gifDisplayModeCanBeCompactOrLinkOnly() {
        val url = "https://cdn.example.test/gifs/a.gif"
        val message = message(ChatSegment.Gif("gif-1", url, "party"))
        val compact = ChatRowCompiler(gifDisplayMode = ChatGifDisplayMode.EMOTE)
            .compile(message)
        .pieces.filterIsInstance<ChatPiece.Gif>().single()
        assertEquals(28, compact.asset.targetHeight)
        assertTrue(ChatRowCompiler(gifDisplayMode = ChatGifDisplayMode.EMOTE)
            .compile(message)
            .pieces.none { it is ChatPiece.Text && it.value.contains('\n') })

        val link = ChatRowCompiler(gifDisplayMode = ChatGifDisplayMode.LINK).compile(message)
        assertTrue(link.pieces.none { it is ChatPiece.Gif })
        assertTrue(link.pieces.filterIsInstance<ChatPiece.Text>().any { it.value == url })
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
        assertTrue(row.pieces.filterIsInstance<ChatPiece.Reply>().any { it.value.contains("Replying to") })
    }

    @Test
    fun firstChatterRewardAndAnnouncementHaveVisiblePresentation() {
        val first = ChatRowCompiler().compile(
            message(ChatSegment.Text("hello")).copy(isFirst = true),
        )
        assertTrue(first.pieces.filterIsInstance<ChatPiece.Text>().any { it.value.contains("First Time Chatter") })
        assertEquals(ChatRowBackground.FIRST_CHATTER, first.backgroundStyle)

        val reward = ChatRowCompiler().compile(
            message(ChatSegment.Text("redeemed message")).copy(rewardId = "reward"),
        )
        assertTrue(reward.pieces.filterIsInstance<ChatPiece.Text>().any { it.value.contains("Channel points reward") })
        assertEquals(ChatRowBackground.REWARD, reward.backgroundStyle)

        val announcement = ChatRowCompiler().compile(
            message(ChatSegment.Text("announcement")).copy(kind = ChatMessageKind.ANNOUNCEMENT),
        )
        assertTrue(announcement.pieces.filterIsInstance<ChatPiece.Text>().any { it.value.contains("Announcement") })
        assertEquals(ChatRowBackground.NOTICE, announcement.backgroundStyle)
    }

    @Test
    fun redemptionUsesRewardMetadataAndMatchesLegacyOrdering() {
        val reward = ChatReward(
            title = "Hydrate",
            cost = 420,
            imageUrl = "https://cdn.example.test/rewards/hydrate.png",
        )
        val row = ChatRowCompiler().compile(
            message(ChatSegment.Text("drink up")).copy(
                rewardId = "reward",
                user = ChatUser("user", "viewer", "Viewer", null),
            ),
            ChatCatalogSnapshot(0, channelPointRewards = mapOf("reward" to reward)),
        )

        val rewardText = row.pieces.filterIsInstance<ChatPiece.Text>().joinToString("") { it.value }
        assertTrue(rewardText.contains("Redeemed Hydrate"))
        assertTrue(rewardText.contains("420"))
        assertEquals(reward.imageUrl, row.pieces.filterIsInstance<ChatPiece.RewardIcon>().single().asset.key.value)
        assertTrue(row.pieces.indexOfFirst { it is ChatPiece.Text && it.value.contains("Redeemed Hydrate") } < row.pieces.indexOfFirst { it is ChatPiece.Username })
    }

    @Test
    fun redemptionWithoutUserTextStillShowsRewardMetadata() {
        val reward = ChatReward(
            title = "Sound Alert",
            cost = 1_000,
            imageUrl = "https://cdn.example.test/rewards/sound.png",
        )
        val row = ChatRowCompiler().compile(
            message(ChatSegment.Text("")).copy(
                segments = emptyList(),
                rewardId = "reward",
                user = ChatUser("user", "viewer", "Viewer", null),
            ),
            ChatCatalogSnapshot(0, channelPointRewards = mapOf("reward" to reward)),
        )

        val text = row.pieces.filterIsInstance<ChatPiece.Text>().joinToString("") { it.value }
        assertEquals("Viewer", row.pieces.filterIsInstance<ChatPiece.Username>().single().value)
        assertTrue(text.contains("redeemed Sound Alert"))
        assertTrue(text.filter(Char::isDigit).contains("1000"))
        assertEquals(reward.imageUrl, row.pieces.filterIsInstance<ChatPiece.RewardIcon>().single().asset.key.value)
    }

    @Test
    fun translationIsRenderedAsASeparateMutedLine() {
        val row = ChatRowCompiler(
            translation = { "Translated: hello" },
        ).compile(message(ChatSegment.Text("hello")))

        assertTrue(row.pieces.filterIsInstance<ChatPiece.Text>().any { it.value == "\nTranslated: hello" })
    }

    @Test
    fun subscriptionNoticeUsesTheSpaciousRailTreatment() {
        val row = ChatRowCompiler().compile(
            message(ChatSegment.Text("They've been subscribed for 6 months!")).copy(
                kind = ChatMessageKind.NOTICE,
                noticeType = "resub",
                systemText = "Daaaaale subscribed with Prime Gaming.",
                subscriptionPlan = "Prime",
            ),
        )

        assertEquals(ChatRowBackground.SUBSCRIPTION, row.backgroundStyle)
        assertTrue(row.pieces.any { it is ChatPiece.Icon && it.drawableRes == R.drawable.ic_chat_subscription })
        assertTrue(row.pieces.filterIsInstance<ChatPiece.Text>().any { it.value.contains("Daaaaale") && it.bold })
        assertTrue(row.pieces.filterIsInstance<ChatPiece.Text>().any { it.value.contains("Prime Gaming") && it.color != null })
    }

    @Test
    fun paidSubscriptionNoticeDoesNotUseThePrimeRail() {
        val row = ChatRowCompiler().compile(
            message(ChatSegment.Text("They've been subscribed for 6 months!")).copy(
                kind = ChatMessageKind.NOTICE,
                noticeType = "resub",
                subscriptionPlan = "1000",
                systemText = "Viewer subscribed with Tier 1.",
            ),
        )

        assertEquals(ChatRowBackground.NOTICE, row.backgroundStyle)
        assertTrue(row.pieces.filterIsInstance<ChatPiece.Text>().any { it.value.contains("Viewer subscribed") })
        assertTrue(row.pieces.none { it is ChatPiece.Icon && it.drawableRes == R.drawable.ic_chat_subscription })
    }

    @Test
    fun sharedChatAndWatchStreakRemainVisibleWhenSystemBodyIsUsed() {
        val message = message(ChatSegment.Text("hello")).copy(
            kind = ChatMessageKind.NOTICE,
            noticeType = "watch_streak",
            systemText = "Viewer is on a streak",
            watchStreakCount = 7,
            watchStreakPoints = 700,
            source = com.github.andreyasadchy.xtra.ui.chat.v2.domain.SharedChatSource(
                broadcasterId = "source",
                broadcasterLogin = "source_login",
                broadcasterName = "Source",
                messageId = null,
                badges = emptyList(),
                sourceOnly = false,
            ),
        )
        val row = ChatRowCompiler().compile(message)
        val text = row.pieces.filterIsInstance<ChatPiece.Text>().joinToString("") { it.value }
        assertTrue(text.contains("Watch Streak Reached"))
        assertTrue(text.contains("7-stream streak"))
        assertTrue(text.contains("+700"))
        assertTrue(row.pieces.any { it is ChatPiece.Source && it.value == "Source" })
        assertEquals(ChatRowBackground.WATCH_STREAK, row.backgroundStyle)
    }

    @Test
    fun cheerUsesNumericBitsAndAccessibilityAmount() {
        val row = ChatRowCompiler().compile(message(ChatSegment.Cheermote(base, "Cheer100", 100, 0xffff0000.toInt())))
        val cheer = row.pieces.filterIsInstance<ChatPiece.Cheermote>().single()
        assertEquals(100, cheer.bits)
        assertEquals(ChatAssetProvider.TWITCH, cheer.interaction?.provider)
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
            sevenTv = ScopedEmoteCatalog(global = mapOf("Party" to emote("Party", ChatAssetProvider.SEVEN_TV))),
        )
        val withCatalog = resolver.resolve(message, catalog)
        assertTrue(withCatalog.pieces.any { it is ChatPiece.Emote && it.fallback == "Party" })
        assertEquals(message.id, withCatalog.id)
    }

    @Test
    fun thirdPartyEmotePieceRetainsProviderIdentityForInteraction() {
        val definition = emote("Party", ChatAssetProvider.BTTV).copy(
            id = "bttv-id",
            scope = ChatEmoteScope.CHANNEL,
            animated = true,
        )
        val row = ChatRowCompiler().compile(
            message(ChatSegment.Text("Party")),
            ChatCatalogSnapshot(
                1,
                bttv = ScopedEmoteCatalog(channel = mapOf("Party" to definition)),
            ),
        )

        val interaction = row.pieces.filterIsInstance<ChatPiece.Emote>().single().interaction
        assertEquals(
            ChatEmoteInteraction(
                id = "bttv-id",
                name = "Party",
                url = "Party",
                animated = true,
                provider = ChatAssetProvider.BTTV,
                scope = ChatEmoteScope.CHANNEL,
            ),
            interaction,
        )
    }

    @Test
    fun globalBttvEmoteResolvesFromTheGlobalCatalog() {
        val definition = emote("haHAA", ChatAssetProvider.BTTV).copy(
            id = "555981336ba1901877765555",
            scope = ChatEmoteScope.GLOBAL,
        )
        val row = ChatRowCompiler().compile(
            message(ChatSegment.Text("hello haHAA")),
            ChatCatalogSnapshot(
                1,
                bttv = ScopedEmoteCatalog(global = mapOf("haHAA" to definition)),
            ),
        )

        val piece = row.pieces.filterIsInstance<ChatPiece.Emote>().single()
        assertEquals("haHAA", piece.fallback)
        assertEquals(ChatAssetProvider.BTTV, piece.interaction?.provider)
        assertEquals(ChatEmoteScope.GLOBAL, piece.interaction?.scope)
    }

    @Test
    fun personalEmotesRespectTheVisibilityPreference() {
        val personal = emote("VIPWave", ChatAssetProvider.SEVEN_TV).copy(scope = ChatEmoteScope.PERSONAL)
        val message = message(ChatSegment.Text("VIPWave")).copy(
            user = ChatUser("sender", "sender", "Sender", null),
        )
        val catalog = ChatCatalogSnapshot(
            1,
            sevenTv = ScopedEmoteCatalog(personal = mapOf("set-a" to mapOf("VIPWave" to personal))),
            userDecorations = mapOf("sender" to ChatUserDecoration(personalEmoteSetId = "set-a")),
        )

        assertTrue(ChatRowCompiler().compile(message, catalog).pieces.any { it is ChatPiece.Emote })
        assertTrue(ChatRowCompiler(showPersonalEmotes = false).compile(message, catalog).pieces.none { it is ChatPiece.Emote })
        assertTrue(ChatRowCompiler().compile(message.copy(user = ChatUser("other", "other", "Other", null)), catalog).pieces.none { it is ChatPiece.Emote })
    }

    @Test
    fun userPaintAndSevenTvBadgeAreIncludedInV2Presentation() {
        val badge = ChatCatalogBadge(
            name = "badge",
            asset = base,
            provider = ChatAssetProvider.SEVEN_TV,
            setId = "badge",
            versionId = "1",
        )
        val row = ChatRowCompiler().compile(
            message(ChatSegment.Text("hello")).copy(user = ChatUser("user", "login", "Viewer", null)),
            ChatCatalogSnapshot(
                1,
                userDecorations = mapOf("user" to ChatUserDecoration(paintId = "paint", badgeId = "badge")),
                namePaints = mapOf("paint" to ChatNamePaint(colors = listOf(0xffff00ff.toInt(), 0xff9146ff.toInt()))),
                sevenTvBadges = mapOf("badge" to badge),
            ),
        )

        assertEquals(
            listOf(0xffff00ff.toInt(), 0xff9146ff.toInt()),
            row.pieces.filterIsInstance<ChatPiece.Username>().single().paint?.colors,
        )
        assertEquals(ChatAssetProvider.SEVEN_TV, row.pieces.filterIsInstance<ChatPiece.Badge>().single().interaction?.provider)
    }

    @Test
    fun overlayCompositionKeepsBaseEmoteInteraction() {
        val baseEmote = emote("Party", ChatAssetProvider.SEVEN_TV).copy(id = "base-id")
        val modifier = emote("Crown", ChatAssetProvider.SEVEN_TV).copy(
            id = "modifier-id",
            zeroWidth = true,
        )
        val row = ChatRowCompiler().compile(
            message(ChatSegment.Text("Party Crown")),
            ChatCatalogSnapshot(
                1,
                sevenTv = ScopedEmoteCatalog(
                    global = mapOf("Party" to baseEmote, "Crown" to modifier),
                ),
            ),
        )
        val piece = row.pieces.filterIsInstance<ChatPiece.Emote>().single()
        assertEquals("base-id", piece.interaction?.id)
        assertEquals(ChatAssetProvider.SEVEN_TV, piece.interaction?.provider)
        assertEquals(1, piece.asset.overlays.size)
    }

    @Test
    fun replacingPresentationStyleRecompilesEmoteAndBadgeGeometryTogether() {
        val message = message(ChatSegment.Emote(base, "Kappa", false)).copy(
            badges = listOf(ChatBadgeRef("subscriber", "1", "Subscriber")),
        )
        val resolver = ChatPresentationResolver(ChatRowCompiler(emoteHeightPx = 14, badgeHeightPx = 9))
        val compact = resolver.resolve(message, ChatCatalogSnapshot(1))
        resolver.replaceCompiler(ChatRowCompiler(emoteHeightPx = 56, badgeHeightPx = 36))
        val large = resolver.resolve(message, ChatCatalogSnapshot(1))

        assertEquals(14, compact.pieces.filterIsInstance<ChatPiece.Emote>().single().asset.targetHeight)
        assertEquals(9, compact.pieces.filterIsInstance<ChatPiece.Badge>().single().asset.targetHeight)
        assertEquals(56, large.pieces.filterIsInstance<ChatPiece.Emote>().single().asset.targetHeight)
        assertEquals(36, large.pieces.filterIsInstance<ChatPiece.Badge>().single().asset.targetHeight)
    }

    @Test
    fun zeroWidthModifierComposesAfterNativeOrThirdPartyEmote() {
        val modifier = emote("modifier", ChatAssetProvider.SEVEN_TV).copy(zeroWidth = true)
        val catalog = ChatCatalogSnapshot(
            revision = 1,
            sevenTv = ScopedEmoteCatalog(global = mapOf("ZW" to modifier)),
        )
        val nativeThenModifier = message(ChatSegment.Emote(base, "Kappa", false)).copy(
            segments = listOf(ChatSegment.Emote(base, "Kappa", false), ChatSegment.Text(" ZW")),
        )
        val nativeRow = ChatPresentationResolver().resolve(nativeThenModifier, catalog)
        val nativeEmote = nativeRow.pieces.filterIsInstance<ChatPiece.Emote>().single()
        assertEquals(1, nativeEmote.asset.overlays.size)

        val thirdPartyRow = ChatPresentationResolver().resolve(message(ChatSegment.Text("Party ZW")), catalog.copy(
            sevenTv = ScopedEmoteCatalog(
                global = catalog.sevenTv.effective + ("Party" to emote("Party", ChatAssetProvider.SEVEN_TV)),
            ),
        ))
        assertEquals(1, thirdPartyRow.pieces.filterIsInstance<ChatPiece.Emote>().single().asset.overlays.size)
    }

    @Test
    fun providerPrecedenceIsDeterministicAndCatalogReplacementTakesEffect() {
        val message = message(ChatSegment.Text("same"))
        val seven = emote("same", ChatAssetProvider.SEVEN_TV)
        val bttv = emote("same", ChatAssetProvider.BTTV)
        val ffz = emote("same", ChatAssetProvider.FFZ)
        val first = ChatPresentationResolver().resolve(
            message,
            ChatCatalogSnapshot(
                1,
                sevenTv = ScopedEmoteCatalog(global = mapOf("same" to seven)),
                bttv = ScopedEmoteCatalog(global = mapOf("same" to bttv)),
                ffz = ScopedEmoteCatalog(global = mapOf("same" to ffz)),
            ),
        )
        assertEquals(seven.asset.key, (first.pieces.single() as ChatPiece.Emote).asset.key)
        val replaced = ChatPresentationResolver().resolve(message, ChatCatalogSnapshot(2, ffz = ScopedEmoteCatalog(global = mapOf("same" to ffz))))
        assertEquals(ffz.asset.key, (replaced.pieces.single() as ChatPiece.Emote).asset.key)
    }

    @Test
    fun scopedNameLookupUsesChannelPrecedenceWithoutRebuildingTheProjection() {
        val global = emote("global", ChatAssetProvider.SEVEN_TV).copy(name = "same")
        val channel = emote("channel", ChatAssetProvider.SEVEN_TV).copy(name = "same", scope = ChatEmoteScope.CHANNEL)
        val scoped = ScopedEmoteCatalog(
            global = mapOf("same" to global),
            channel = mapOf("same" to channel),
        )

        assertEquals(channel, scoped["same"])
        assertTrue("same" in scoped)
    }

    @Test
    fun badgeIdentityAndGeometryExistBeforeBadgeCatalogLoads() {
        val badge = ChatBadgeRef("subscriber", "12", "Subscriber")
        val row = ChatPresentationResolver().resolve(message(ChatSegment.Text("hi")).copy(badges = listOf(badge)), ChatCatalogSnapshot(0))
        val piece = row.pieces.filterIsInstance<ChatPiece.Badge>().single()
        assertEquals("twitch-badge:subscriber:12", piece.asset.key.value)
        assertEquals(18, piece.asset.targetHeight)
        assertEquals(ChatAssetProvider.TWITCH, piece.interaction?.provider)
    }

    @Test
    fun usernameDisplayModeAndRandomFallbackRemainConfigurable() {
        val message = message(ChatSegment.Text("hello")).copy(
            user = ChatUser("user", "login", "Display", null),
        )
        val display = ChatRowCompiler(
            colors = ChatColorResolver(randomFallback = true),
            nameDisplay = "1",
        ).compile(message)
        val login = ChatRowCompiler(nameDisplay = "2").compile(message)
        assertTrue(display.pieces.filterIsInstance<ChatPiece.Username>().single().value == "Display")
        assertTrue(login.pieces.filterIsInstance<ChatPiece.Username>().single().value == "login")
        assertTrue(ChatColorResolver(randomFallback = true).resolve(null, "user") != 0xFF919191.toInt())
    }

    @Test
    fun presentationResolverCachesUnchangedRows() {
        val resolver = ChatPresentationResolver()
        val message = message(ChatSegment.Text("hello"))
        val first = resolver.resolve(message, ChatCatalogSnapshot(1))
        val second = resolver.resolve(message, ChatCatalogSnapshot(1))
        assertSame(first, second)
    }

    @Test
    fun presentationOnlyRevisionRecompilesAChangedRow() {
        val resolver = ChatPresentationResolver(ChatRowCompiler(translation = { "initial" }))
        val message = message(ChatSegment.Text("hello"))
        val first = resolver.resolve(message, ChatCatalogSnapshot(1), presentationRevision = 1L)
        val second = resolver.resolve(message, ChatCatalogSnapshot(1), presentationRevision = 2L)
        assertTrue(first !== second)
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
