package com.github.andreyasadchy.xtra.ui.chat.v2

import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatBadgeRef
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatModeration
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUserClearReason
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEmoteInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReply
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReward
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSubscription
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUser
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.TwitchChatMessageType
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPiece
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatEventKind
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatEventVisualStyle
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowBackground
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowCompiler
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPresentationResolver
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatColorResolver
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogEmote
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogBadge
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogCheermote
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatEmoteScope
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatNamePaint
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatUserDecoration
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ScopedEmoteCatalog
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.toCatalog
import com.github.andreyasadchy.xtra.ui.chat.ChatGifDisplayMode
import com.github.andreyasadchy.xtra.model.chat.CheerEmote
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
        val replyPiece = row.pieces.filterIsInstance<ChatPiece.Reply>().single()
        assertTrue(replyPiece.value.contains("Replying to Parent: hello"))
        assertEquals("Parent", replyPiece.parentUser)
        assertEquals("hello", replyPiece.parentMessage)
        assertTrue(!replyPiece.value.contains("@Parent"))
    }

    @Test
    fun ordinaryTwitchEmoteOnlyMessagesUseTheLargerEmoteSize() {
        val row = ChatRowCompiler().compile(
            message(ChatSegment.Emote(base, ":party:", animated = false)),
        )

        assertEquals(56, row.pieces.filterIsInstance<ChatPiece.Emote>().single().asset.targetHeight)
    }

    @Test
    fun thirdPartyEmoteOnlyMessagesAreDetectedAfterTokenization() {
        val definition = emote("Party", ChatAssetProvider.SEVEN_TV)
        val row = ChatRowCompiler().compile(
            message(ChatSegment.Text("Party Party")),
            ChatCatalogSnapshot(
                1,
                sevenTv = ScopedEmoteCatalog(global = mapOf("Party" to definition)),
            ),
        )

        assertEquals(
            listOf(56, 56),
            row.pieces.filterIsInstance<ChatPiece.Emote>().map { it.asset.targetHeight },
        )
    }

    @Test
    fun bttvEmoteOnlyMessagesAreDetectedAfterTokenization() {
        val definition = emote("Party", ChatAssetProvider.BTTV)
        val row = ChatRowCompiler().compile(
            message(ChatSegment.Text("Party")),
            ChatCatalogSnapshot(
                1,
                bttv = ScopedEmoteCatalog(global = mapOf("Party" to definition)),
            ),
        )

        assertEquals(56, row.pieces.filterIsInstance<ChatPiece.Emote>().single().asset.targetHeight)
    }

    @Test
    fun whitespaceBetweenEmotesStillCountsAsEmoteOnly() {
        val row = ChatRowCompiler().compile(
            message(ChatSegment.Emote(base, ":one:", animated = false)).copy(
                segments = listOf(
                    ChatSegment.Emote(base, ":one:", animated = false),
                    ChatSegment.Text("  "),
                    ChatSegment.Emote(base, ":two:", animated = false),
                ),
            ),
        )

        assertEquals(
            listOf(56, 56),
            row.pieces.filterIsInstance<ChatPiece.Emote>().map { it.asset.targetHeight },
        )
    }

    @Test
    fun textAndEmoteStayAtTheNormalEmoteSize() {
        val row = ChatRowCompiler().compile(
            message(ChatSegment.Emote(base, ":party:", animated = false)).copy(
                segments = listOf(
                    ChatSegment.Text("hello "),
                    ChatSegment.Emote(base, ":party:", animated = false),
                ),
            ),
        )

        assertEquals(28, row.pieces.filterIsInstance<ChatPiece.Emote>().single().asset.targetHeight)
    }

    @Test
    fun firstChatterRewardAndAnnouncementHaveVisiblePresentation() {
        val first = ChatRowCompiler().compile(
            message(ChatSegment.Text("hello")).copy(isFirst = true),
        )
        assertTrue(first.pieces.filterIsInstance<ChatPiece.Text>().any { it.value.contains("First Time Chatter") })
        assertEvent(first, ChatEventKind.FIRST_CHATTER, ChatEventVisualStyle.INTRO)

        val reward = ChatRowCompiler().compile(
            message(ChatSegment.Text("redeemed message")).copy(rewardId = "reward"),
        )
        assertTrue(reward.pieces.filterIsInstance<ChatPiece.Text>().any { it.value.contains("Channel points reward") })
        assertEvent(reward, ChatEventKind.CHANNEL_POINTS, ChatEventVisualStyle.REWARD)

        val announcement = ChatRowCompiler().compile(
            message(ChatSegment.Text("announcement")).copy(kind = ChatMessageKind.ANNOUNCEMENT),
        )
        assertTrue(announcement.pieces.filterIsInstance<ChatPiece.Text>().any { it.value.contains("Announcement") })
        assertEvent(announcement, ChatEventKind.ANNOUNCEMENT, ChatEventVisualStyle.NOTICE)
    }

    @Test
    fun firstMessageVisibilityPreservesFullTintAndNormalModes() {
        val firstMessage = message(ChatSegment.Text("hello")).copy(isFirst = true)

        val full = ChatRowCompiler(firstMessageVisibility = 0).compile(firstMessage)
        assertEvent(full, ChatEventKind.FIRST_CHATTER, ChatEventVisualStyle.INTRO)

        val tinted = ChatRowCompiler(firstMessageVisibility = 1).compile(firstMessage)
        assertEquals(null, tinted.eventPresentation)
        assertEquals(ChatRowBackground.FIRST_CHATTER_TINT, tinted.backgroundStyle)

        val normal = ChatRowCompiler(firstMessageVisibility = 2).compile(firstMessage)
        assertEquals(null, normal.eventPresentation)
        assertEquals(ChatRowBackground.NORMAL, normal.backgroundStyle)

        val wireFirstMessage = firstMessage.copy(
            kind = ChatMessageKind.NOTICE,
            noticeType = "first_message",
            systemText = "First message",
        )
        val wireTinted = ChatRowCompiler(firstMessageVisibility = 1).compile(wireFirstMessage)
        assertEquals(null, wireTinted.eventPresentation)
        assertEquals(ChatRowBackground.FIRST_CHATTER_TINT, wireTinted.backgroundStyle)

        val reward = firstMessage.copy(
            isFirst = false,
            kind = ChatMessageKind.REWARD,
            rewardId = "reward",
            noticeType = "channel_points_custom_reward_redemption",
            systemText = "Hydrate",
        )
        val hiddenReward = ChatRowCompiler(firstMessageVisibility = 2).compile(reward)
        assertEquals(null, hiddenReward.eventPresentation)
        assertEquals(ChatRowBackground.NOTICE, hiddenReward.backgroundStyle)
    }

    @Test
    fun userAuthoredEventBodiesKeepThirdPartyEmotesWhenSystemEmotesAreDisabled() {
        val catalog = ChatCatalogSnapshot(
            revision = 1,
            bttv = ScopedEmoteCatalog(global = mapOf("Party" to emote("Party", ChatAssetProvider.BTTV))),
        )
        val compiler = ChatRowCompiler(showSystemMessageEmotes = false)
        val eventMessages = listOf(
            message(ChatSegment.Text("Party")).copy(
                kind = ChatMessageKind.NOTICE,
                noticeType = "resub",
                subscription = ChatSubscription(tier = "1000"),
            ),
            message(ChatSegment.Text("Party")).copy(
                kind = ChatMessageKind.NOTICE,
                noticeType = "watch_streak",
                watchStreakCount = 7,
            ),
            message(ChatSegment.Text("Party")).copy(
                kind = ChatMessageKind.REWARD,
                rewardId = "reward",
            ),
        )

        eventMessages.forEach { eventMessage ->
            val row = compiler.compile(eventMessage, catalog)
            assertTrue(row.pieces.any { it is ChatPiece.Emote && it.fallback == "Party" })
        }

        val systemNotice = compiler.compile(
            message(ChatSegment.Text("Party")).copy(
                kind = ChatMessageKind.NOTICE,
                systemText = "A system notice",
            ),
            catalog,
        )
        assertTrue(systemNotice.pieces.none { it is ChatPiece.Emote })
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
        assertTrue(rewardText.contains("redeemed Hydrate"))
        assertTrue(rewardText.contains("420"))
        val rewardIcon = row.pieces.filterIsInstance<ChatPiece.RewardIcon>().single()
        assertEquals(reward.imageUrl, rewardIcon.asset.key.value)
        assertEquals(18, rewardIcon.asset.targetHeight)
        assertTrue(row.pieces.indexOfFirst { it is ChatPiece.Text && it.value.contains("redeemed Hydrate") } < row.pieces.indexOfFirst { it is ChatPiece.Username })
        assertEvent(row, ChatEventKind.CHANNEL_POINTS, ChatEventVisualStyle.REWARD)
        assertTrue(row.accessibilityText.contains("Viewer redeemed Hydrate for 420 channel points"))
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
        assertTrue(row.pieces.none { it is ChatPiece.Username })
        assertTrue(text.contains("redeemed Sound Alert"))
        assertTrue(text.filter(Char::isDigit).contains("1000"))
        val rewardIcon = row.pieces.filterIsInstance<ChatPiece.RewardIcon>().single()
        assertEquals(reward.imageUrl, rewardIcon.asset.key.value)
        assertEquals(18, rewardIcon.asset.targetHeight)
        assertEvent(row, ChatEventKind.CHANNEL_POINTS, ChatEventVisualStyle.REWARD)
        assertTrue(row.eventPresentation?.bodyPieces.isNullOrEmpty())
        assertTrue(!row.accessibilityText.contains("Message:"))
    }

    @Test
    fun highlightedRedemptionUsesTitleCostRowAndHighlightBackground() {
        val row = ChatRowCompiler().compile(
            message(ChatSegment.Text("Lock them up!")).copy(
                user = ChatUser("user", "viewer", "Viewer", null),
                rewardId = null,
                twitchType = TwitchChatMessageType.Highlighted,
            ),
            ChatCatalogSnapshot(
                0,
                automaticChannelPointRewards = mapOf(
                    com.github.andreyasadchy.xtra.ui.chat.v2.domain.HIGHLIGHTED_MESSAGE_REWARD_TYPE to ChatReward("Highlight My Message", 2_000, null),
                ),
            ),
        )

        val text = row.pieces.filterIsInstance<ChatPiece.Text>().joinToString("") { it.value }
        assertTrue(row.pieces.any { it is ChatPiece.Text && it.value.contains("redeemed Highlight My Message") && it.bold })
        assertTrue(row.pieces.any { it is ChatPiece.Icon && it.drawableRes == R.drawable.ic_chat_channel_points })
        assertTrue(text.filter(Char::isDigit).contains("2000"))
        assertTrue(row.pieces.any { it is ChatPiece.Username && it.value == "Viewer" })
        assertTrue(text.contains("Lock them up!"))
        assertEvent(row, ChatEventKind.HIGHLIGHT, ChatEventVisualStyle.REWARD)
        assertTrue(row.accessibilityText.contains("Viewer redeemed Highlight My Message for"))
        assertTrue(row.accessibilityText.contains("channel points"))
    }

    @Test
    fun highlightedRedemptionPrefersLocalizedTitleOverCatalogTitle() {
        val row = ChatRowCompiler(
            labels = com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPresentationLabels(
                highlightTitle = "Zvýrazniť moju správu",
                highlightRedeemed = { "Uplatnené: $it" },
            ),
        ).compile(
            message(ChatSegment.Text("Lock them up!")).copy(
                user = ChatUser("user", "viewer", "Viewer", null),
                rewardId = null,
                twitchType = TwitchChatMessageType.Highlighted,
            ),
            ChatCatalogSnapshot(
                0,
                automaticChannelPointRewards = mapOf(
                    com.github.andreyasadchy.xtra.ui.chat.v2.domain.HIGHLIGHTED_MESSAGE_REWARD_TYPE to ChatReward("Highlight My Message", 2_000, null),
                ),
            ),
        )

        val text = row.pieces.filterIsInstance<ChatPiece.Text>().joinToString("") { it.value }
        assertTrue(row.pieces.any { it is ChatPiece.Text && it.value.contains("Zvýrazniť moju správu") && it.bold })
        assertTrue(row.pieces.none { it is ChatPiece.Text && it.value == "Highlight My Message" })
        assertTrue(row.pieces.any { it is ChatPiece.Icon && it.drawableRes == R.drawable.ic_chat_channel_points })
        assertTrue(text.filter(Char::isDigit).contains("2000"))
        assertEvent(row, ChatEventKind.HIGHLIGHT, ChatEventVisualStyle.REWARD)
    }

    @Test
    fun translationIsRenderedAsASeparateMutedLine() {
        val row = ChatRowCompiler(
            translation = { "Translated: hello" },
        ).compile(message(ChatSegment.Text("hello")))

        assertTrue(row.pieces.filterIsInstance<ChatPiece.Text>().any { it.value == "\nTranslated: hello" })
    }

    @Test
    fun moderationRangeStopsBeforeSuffixAndLeavesReplyMetadataOutside() {
        val row = ChatRowCompiler(
            timestampText = { _ -> "12:00" },
            translation = { "Translated" },
        ).compile(
            message(ChatSegment.Text("hello")).copy(
                user = ChatUser("user", "viewer", "Viewer", null),
                reply = ChatReply(
                    parentMessageId = ChatMessageId("parent"),
                    parentMessageBody = "parent",
                    parentUserId = "parent-user",
                    parentUserName = "Parent",
                    parentUserLogin = "parent",
                    threadMessageId = null,
                    threadUserId = null,
                    threadUserName = null,
                    threadUserLogin = null,
                ),
                moderation = ChatModeration(ChatUserClearReason.TIMEOUT, 10),
            ),
        )

        val range = row.moderationPieceRange ?: error("Moderated rows must expose their message piece range")
        val suffixIndex = row.pieces.indexOfLast { it is ChatPiece.Text && it.value.contains("Timeout") }
        assertTrue(range.first > row.pieces.indexOfFirst { it is ChatPiece.Reply })
        assertTrue(range.last < suffixIndex)
        assertTrue(row.pieces.drop(range.first).take(range.last - range.first + 1).any { it is ChatPiece.Text && it.value.contains("hello") })
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

        assertEvent(row, ChatEventKind.SUBSCRIPTION, ChatEventVisualStyle.SUPPORT)
        assertTrue(row.pieces.any { it is ChatPiece.Icon && it.drawableRes == R.drawable.ic_chat_subscription })
        assertTrue(row.pieces.filterIsInstance<ChatPiece.Text>().any { it.value.contains("Daaaaale") && it.bold })
        assertTrue(row.pieces.filterIsInstance<ChatPiece.Text>().any { it.value.contains("Prime Gaming") && it.color != null })
    }

    @Test
    fun paidSubscriptionNoticeUsesTheGiftRailAndKeepsTheActorColored() {
        val row = ChatRowCompiler().compile(
            message(ChatSegment.Text("They've been subscribed for 6 months!")).copy(
                user = ChatUser("user", "viewer", "Viewer", 0xff9147ff.toInt()),
                kind = ChatMessageKind.NOTICE,
                noticeType = "resub",
                subscriptionPlan = "1000",
                isPrimeSubscription = false,
                systemText = "Viewer subscribed with Tier 1.",
            ),
        )

        assertEvent(row, ChatEventKind.SUBSCRIPTION, ChatEventVisualStyle.SUPPORT)
        assertTrue(row.pieces.any { it is ChatPiece.Icon && it.drawableRes == R.drawable.ic_chat_subscription_gift })
        assertTrue(row.pieces.filterIsInstance<ChatPiece.Text>().any { it.value == "Viewer" && it.bold && it.color == 0xff9147ff.toInt() })
        assertTrue(row.pieces.filterIsInstance<ChatPiece.Text>().any { it.value == "subscribed at Tier 1" && it.color != null && it.bold })
        assertTrue(row.accessibilityText.contains("Viewer subscribed at Tier 1"))
    }

    @Test
    fun giftedSubscriptionNoticeUsesTheSameInlineEventTreatment() {
        val row = ChatRowCompiler().compile(
            message(ChatSegment.Text("")).copy(
                user = ChatUser("gifter", "renagade45", "renagade45", 0xff9147ff.toInt()),
                kind = ChatMessageKind.NOTICE,
                noticeType = "sub_gift",
                systemText = "renagade45 gifted a Tier 1 Sub to posty's community!",
                segments = emptyList(),
            ),
        )

        assertEvent(row, ChatEventKind.SUBSCRIPTION, ChatEventVisualStyle.SUPPORT)
        assertTrue(row.pieces.any { it is ChatPiece.Icon && it.drawableRes == R.drawable.ic_chat_subscription_gift })
        assertTrue(row.pieces.none { it is ChatPiece.Username })
        assertTrue(row.pieces.filterIsInstance<ChatPiece.Text>().any { it.value == "renagade45" && it.bold })
    }

    @Test
    fun structuredSubscriptionVariantsShareOneEventContract() {
        val community = ChatRowCompiler().compile(
            message(ChatSegment.Text("")).copy(
                user = ChatUser("gifter", "gifter", "Gifter", 0xff9147ff.toInt()),
                kind = ChatMessageKind.NOTICE,
                noticeType = "community_sub_gift",
                subscription = ChatSubscription(tier = "1000", giftCount = 5, isCommunityGift = true),
                segments = emptyList(),
            ),
        )
        val anonymous = ChatRowCompiler().compile(
            message(ChatSegment.Text("")).copy(
                kind = ChatMessageKind.NOTICE,
                noticeType = "subgift",
                subscription = ChatSubscription(tier = "2000", recipientName = "Recipient", isAnonymous = true),
                segments = emptyList(),
            ),
        )

        assertEvent(community, ChatEventKind.SUBSCRIPTION, ChatEventVisualStyle.SUPPORT)
        assertEvent(anonymous, ChatEventKind.SUBSCRIPTION, ChatEventVisualStyle.SUPPORT)
        assertTrue(community.eventPresentation!!.titlePieces.joinToString("").contains("gifted 5 Tier 1 Subs"))
        val anonymousEvent = checkNotNull(anonymous.eventPresentation)
        assertTrue(anonymousEvent.titlePieces.joinToString("").contains("Anonymous"))
        assertTrue(anonymousEvent.titlePieces.joinToString("").contains("Tier 2 Sub to Recipient"))
        assertTrue(community.eventPresentation.bodyPieces.isEmpty())
        assertTrue(anonymous.eventPresentation.bodyPieces.isEmpty())
        assertTrue(community.accessibilityText.contains("gifted 5 Tier 1 subscriptions to the community"))
        assertTrue(anonymous.accessibilityText.contains("Anonymous gifted a Tier 2 subscription to Recipient"))
    }

    @Test
    fun resubMessageKeepsStructuredMetadataActorColorAndBodyOrder() {
        val row = ChatRowCompiler().compile(
            message(ChatSegment.Text("Thanks for the support!")).copy(
                user = ChatUser("user", "viewer", "Viewer", 0xff00a8a8.toInt()),
                kind = ChatMessageKind.NOTICE,
                noticeType = "shared_chat_resub",
                subscription = ChatSubscription(tier = "3000", months = 6, streakMonths = 4),
            ),
        )

        assertEvent(row, ChatEventKind.SUBSCRIPTION, ChatEventVisualStyle.SUPPORT)
        val event = checkNotNull(row.eventPresentation)
        assertTrue(eventText(event.titlePieces).contains("Viewer subscribed at Tier 3"))
        assertTrue(event.titlePieces.filterIsInstance<ChatPiece.Text>().first().color == 0xff00a8a8.toInt())
        assertTrue(eventText(event.metadataPieces).contains("6 months subscribed"))
        assertTrue(eventText(event.metadataPieces).contains("4 months streak"))
        assertTrue(event.bodyPieces.filterIsInstance<ChatPiece.Username>().single().value == "Viewer")
        assertTrue(row.pieces.indexOf(event.bodyPieces.first()) > row.pieces.indexOf(event.titlePieces.last()))
        assertTrue(row.accessibilityText.contains("They've been subscribed for 6 months"))
        assertTrue(row.accessibilityText.contains("Message: Thanks for the support!"))
    }

    @Test
    fun primePaidUpgradeUsesThePaidSubscriptionIcon() {
        listOf("prime_paid_upgrade", "shared_chat_prime_paid_upgrade").forEach { noticeType ->
            val row = ChatRowCompiler().compile(
                message(ChatSegment.Text("")).copy(
                    user = ChatUser("user", "viewer", "Viewer", null),
                    kind = ChatMessageKind.NOTICE,
                    noticeType = noticeType,
                    subscriptionPlan = "1000",
                    systemText = "Viewer upgraded to a paid Tier 1 Sub.",
                    segments = emptyList(),
                ),
            )

            assertTrue(row.pieces.any { it is ChatPiece.Icon && it.drawableRes == R.drawable.ic_chat_subscription_gift })
            assertTrue(row.pieces.none { it is ChatPiece.Icon && it.drawableRes == R.drawable.ic_chat_subscription })
            assertEvent(row, ChatEventKind.SUBSCRIPTION, ChatEventVisualStyle.SUPPORT)
        }
    }

    @Test
    fun primeSubscriptionMetadataUsesTheCrownAcrossSharedChatVariants() {
        listOf("resub", "shared_chat_sub", "shared_chat_resub").forEach { noticeType ->
            val row = ChatRowCompiler().compile(
                message(ChatSegment.Text("")).copy(
                    user = ChatUser("user", "viewer", "Viewer", null),
                    kind = ChatMessageKind.NOTICE,
                    noticeType = noticeType,
                    subscriptionPlan = "1000",
                    isPrimeSubscription = true,
                    systemText = "Viewer subscribed with Prime Gaming.",
                    segments = emptyList(),
                ),
            )

            assertTrue(row.pieces.any { it is ChatPiece.Icon && it.drawableRes == R.drawable.ic_chat_subscription })
            assertTrue(row.pieces.none { it is ChatPiece.Icon && it.drawableRes == R.drawable.ic_chat_subscription_gift })
            assertEvent(row, ChatEventKind.SUBSCRIPTION, ChatEventVisualStyle.SUPPORT)
        }
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
        assertEvent(row, ChatEventKind.WATCH_STREAK, ChatEventVisualStyle.STREAK)
        assertTrue(row.accessibilityText.contains("7-stream streak"))
        assertTrue(row.accessibilityText.contains("700 channel points"))
    }

    @Test
    fun watchStreakOptionalMessageUsesSharedBodyAndActorColor() {
        val row = ChatRowCompiler().compile(
            message(ChatSegment.Text("I will keep watching")).copy(
                user = ChatUser("user", "viewer", "Viewer", 0xffff9800.toInt()),
                kind = ChatMessageKind.NOTICE,
                noticeType = "watch_streak",
                watchStreakCount = 7,
                watchStreakPoints = 700,
            ),
        )

        assertEvent(row, ChatEventKind.WATCH_STREAK, ChatEventVisualStyle.STREAK)
        assertTrue(row.eventPresentation!!.bodyPieces.filterIsInstance<ChatPiece.Username>().single().color == 0xffff9800.toInt())
        assertTrue(eventText(row.eventPresentation.bodyPieces).contains("I will keep watching"))
        assertTrue(row.accessibilityText.contains("Viewer is currently on a 7-stream streak"))
        assertTrue(row.accessibilityText.contains("Message: I will keep watching"))
    }

    @Test
    fun firstChatterUsesEventContractAndAccessibleActorMessage() {
        val row = ChatRowCompiler().compile(
            message(ChatSegment.Text("hello")).copy(
                user = ChatUser("user", "viewer", "Viewer", 0xff42a5f5.toInt()),
                isFirst = true,
            ),
        )

        assertEvent(row, ChatEventKind.FIRST_CHATTER, ChatEventVisualStyle.INTRO)
        assertTrue(row.eventPresentation!!.bodyPieces.filterIsInstance<ChatPiece.Username>().single().color == 0xff42a5f5.toInt())
        assertTrue(row.accessibilityText == "First Time Chatter. Viewer: hello.")
    }

    @Test
    fun cheerUsesNumericBitsAndAccessibilityAmount() {
        val row = ChatRowCompiler().compile(message(ChatSegment.Cheermote(base, "Cheer100", 100, 0xffff0000.toInt())))
        val cheer = row.pieces.filterIsInstance<ChatPiece.Cheermote>().single()
        assertEquals(100, cheer.bits)
        assertEquals(ChatAssetProvider.TWITCH, cheer.interaction?.provider)
        assertTrue(cheer.interaction?.animated == false)
        assertTrue(row.accessibilityText.contains("100 Bits"))
        assertTrue(!row.accessibilityText.contains("Cheer100 100"))
    }

    @Test
    fun animatedCheermoteRetainsCatalogAnimationMetadata() {
        val row = ChatRowCompiler().compile(
            message(ChatSegment.Cheermote(base, "Cheer100", 100, null)),
            ChatCatalogSnapshot(
                revision = 1,
                cheermotes = mapOf(
                    base.key.value to ChatCatalogCheermote(base, null, animated = true),
                ),
            ),
        )
        val cheer = row.pieces.filterIsInstance<ChatPiece.Cheermote>().single()
        assertTrue(cheer.interaction?.animated == true)
    }

    @Test
    fun staticCheermoteRetainsCatalogAnimationMetadata() {
        val row = ChatRowCompiler().compile(
            message(ChatSegment.Cheermote(base, "Cheer100", 100, null)),
            ChatCatalogSnapshot(
                revision = 1,
                cheermotes = mapOf(
                    base.key.value to ChatCatalogCheermote(base, null, animated = false),
                ),
            ),
        )
        val cheer = row.pieces.filterIsInstance<ChatPiece.Cheermote>().single()
        assertTrue(cheer.interaction?.animated == false)
    }

    @Test
    fun cheerCatalogConversionPreservesAnimatedFlag() {
        val catalog = CheerEmote(
            name = "Cheer100",
            url1x = "https://cdn.example.test/cheer.gif",
            isAnimated = true,
            minBits = 100,
        ).toCatalog()!!.second
        assertTrue(catalog.animated)
    }

    @Test
    fun cheerCatalogConversionPreservesStaticFlag() {
        val catalog = CheerEmote(
            name = "Cheer100",
            url1x = "https://cdn.example.test/cheer.png",
            isAnimated = false,
            minBits = 100,
        ).toCatalog()!!.second
        assertTrue(!catalog.animated)
    }

    @Test
    fun overlaysRetainCompositionGeometryAndScaleTogether() {
        val overlay = ChatAssetSpec(ChatAssetKey("wide"), 80, 20, 12)
        val nested = ChatAssetSpec(ChatAssetKey("tall"), 10, 40, 12)
        val spec = base.copy(overlays = listOf(overlay.copy(overlays = listOf(nested))))
        val row = ChatRowCompiler().compile(message(ChatSegment.Emote(spec, ":x:", false)))
        val compiled = row.pieces.filterIsInstance<ChatPiece.Emote>().single().asset
        assertEquals(56, compiled.targetHeight)
        assertTrue(compiled.overlays.all { it.targetHeight == 56 })
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
            animated = true,
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
        assertTrue(piece.animated)
    }

    @Test
    fun replacingPresentationStyleRecompilesEmoteAndBadgeGeometryTogether() {
        val message = message(ChatSegment.Emote(base, "Kappa", false)).copy(
            badges = listOf(ChatBadgeRef("subscriber", "1", "Subscriber")),
        )
        val catalog = ChatCatalogSnapshot(
            1,
            badges = mapOf(
                "subscriber:1" to ChatCatalogBadge(
                    name = "subscriber:1",
                    asset = ChatAssetSpec(ChatAssetKey("https://cdn.example.test/subscriber.png"), 18, 18, 18),
                    provider = ChatAssetProvider.TWITCH,
                    setId = "subscriber",
                    versionId = "1",
                ),
            ),
        )
        val resolver = ChatPresentationResolver(ChatRowCompiler(emoteHeightPx = 14, badgeHeightPx = 9))
        val compact = resolver.resolve(message, catalog)
        resolver.replaceCompiler(ChatRowCompiler(emoteHeightPx = 56, badgeHeightPx = 36))
        val large = resolver.resolve(message, catalog)

        assertEquals(28, compact.pieces.filterIsInstance<ChatPiece.Emote>().single().asset.targetHeight)
        assertEquals(9, compact.pieces.filterIsInstance<ChatPiece.Badge>().single().asset.targetHeight)
        assertEquals(112, large.pieces.filterIsInstance<ChatPiece.Emote>().single().asset.targetHeight)
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
    fun twitchBadgesRequireResolvedHttpAssets() {
        val badge = ChatBadgeRef("subscriber", "12", "Subscriber")
        val missing = ChatPresentationResolver().resolve(
            message(ChatSegment.Text("hi")).copy(badges = listOf(badge)),
            ChatCatalogSnapshot(0),
        )
        assertTrue(missing.pieces.none { it is ChatPiece.Badge })

        val resolvedDefinition = ChatCatalogBadge(
            name = "subscriber:12",
            asset = ChatAssetSpec(ChatAssetKey("https://cdn.example.test/subscriber.png"), 18, 18, 18),
            provider = ChatAssetProvider.TWITCH,
            setId = "subscriber",
            versionId = "12",
            info = "Subscriber",
        )
        val resolved = ChatPresentationResolver().resolve(
            message(ChatSegment.Text("hi")).copy(badges = listOf(badge)),
            ChatCatalogSnapshot(1, badges = mapOf(badge.catalogKey to resolvedDefinition)),
        )
        val piece = resolved.pieces.filterIsInstance<ChatPiece.Badge>().single()
        assertTrue(piece.asset.key.value.startsWith("https://"))
        assertEquals(18, piece.asset.targetHeight)
        assertEquals(ChatAssetProvider.TWITCH, piece.interaction?.provider)

        val pseudo = resolvedDefinition.copy(
            asset = resolvedDefinition.asset.copy(key = ChatAssetKey("twitch-badge:subscriber:12")),
        )
        val invalid = ChatPresentationResolver().resolve(
            message(ChatSegment.Text("hi")).copy(badges = listOf(badge)),
            ChatCatalogSnapshot(2, badges = mapOf(badge.catalogKey to pseudo)),
        )
        assertTrue(invalid.pieces.none { it is ChatPiece.Badge })
    }

    @Test
    fun malformedBadgeIdentityDoesNotCreateAnAssetPlaceholder() {
        val row = ChatRowCompiler().compile(
            message(ChatSegment.Text("hi")).copy(badges = listOf(ChatBadgeRef("", ""))),
            ChatCatalogSnapshot(0),
        )

        assertTrue(row.pieces.none { it is ChatPiece.Badge })
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

    private fun assertEvent(
        row: com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel,
        kind: ChatEventKind,
        style: ChatEventVisualStyle,
    ) {
        assertEquals(com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowBackground.EVENT, row.backgroundStyle)
        assertEquals(kind, row.eventPresentation?.kind)
        assertEquals(style, row.eventPresentation?.visualStyle)
    }

    private fun eventText(pieces: List<ChatPiece>): String =
        pieces.filterIsInstance<ChatPiece.Text>().joinToString("") { it.value }

    private fun message(segment: ChatSegment) = ChatMessage(
        id = ChatMessageId("id"), channelId = "channel", timestampMs = 1,
        user = null, badges = emptyList(),
        segments = listOf(segment), kind = ChatMessageKind.CHAT,
    )
}
