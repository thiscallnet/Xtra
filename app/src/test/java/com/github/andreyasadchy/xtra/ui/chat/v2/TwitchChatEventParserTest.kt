package com.github.andreyasadchy.xtra.ui.chat.v2

import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEvent
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUserClearReason
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReward
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.HIGHLIGHTED_MESSAGE_REWARD_TYPE
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.TwitchChatMessageType
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatEventKind
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPiece
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowCompiler
import com.github.andreyasadchy.xtra.ui.chat.v2.transport.TwitchChatEventParser
import com.github.andreyasadchy.xtra.util.chat.ChatUtils
import com.github.andreyasadchy.xtra.util.chat.PubSubUtils
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchChatEventParserTest {
    @Test
    fun ircClearchatDistinguishesTimeoutBanAndRoomClear() {
        val timeout = TwitchChatEventParser.fromIrc(
            ChatUtils.parseIRCMessage(
                "@target-user-id=42;ban-duration=600;tmi-sent-ts=1000 :mod!mod@mod.tmi.twitch.tv CLEARCHAT #channel :viewer",
            ),
            "channel-id",
        ) as ChatEvent.ClearUser
        assertEquals("42", timeout.userId)
        assertEquals("viewer", timeout.userLogin)
        assertEquals(ChatUserClearReason.TIMEOUT, timeout.reason)
        assertEquals(600, timeout.timeoutSeconds)

        val ban = TwitchChatEventParser.fromIrc(
            ChatUtils.parseIRCMessage(
                "@tmi-sent-ts=1001 :mod!mod@mod.tmi.twitch.tv CLEARCHAT #channel :viewer",
            ),
            "channel-id",
        ) as ChatEvent.ClearUser
        assertEquals(ChatUserClearReason.BAN, ban.reason)
        assertEquals("viewer", ban.userLogin)

        val roomClear = TwitchChatEventParser.fromIrc(
            ChatUtils.parseIRCMessage(
                "@tmi-sent-ts=1002 :tmi.twitch.tv CLEARCHAT #channel",
            ),
            "channel-id",
        )
        assertTrue(roomClear is ChatEvent.Clear)
    }

    @Test
    fun eventSubUserClearCarriesTargetIdentityWithoutCallingItABan() {
        val event = TwitchChatEventParser.fromEventSubClear(
            JSONObject(
                """{"target_user_id":"42","target_user_login":"viewer","target_user_name":"Viewer"}""",
            ),
            "2026-09-01T12:00:00Z",
        ) as ChatEvent.ClearUser

        assertEquals("42", event.userId)
        assertEquals("viewer", event.userLogin)
        assertEquals("Viewer", event.userName)
        assertEquals(ChatUserClearReason.MESSAGES_CLEARED, event.reason)
        assertTrue(event.eventId?.startsWith("42-") == true)
    }

    @Test
    fun repeatedEventSubUserClearsUseNotificationIdsForDistinctNotices() {
        val payload = JSONObject(
            """{"target_user_id":"42","target_user_login":"viewer","target_user_name":"Viewer"}""",
        )
        val first = TwitchChatEventParser.fromEventSubClear(
            payload,
            "2026-09-01T12:00:00Z",
            notificationId = "clear-1",
        ) as ChatEvent.ClearUser
        val second = TwitchChatEventParser.fromEventSubClear(
            payload,
            "2026-09-01T12:00:00Z",
            notificationId = "clear-2",
        ) as ChatEvent.ClearUser

        assertEquals("clear-1", first.eventId)
        assertEquals("clear-2", second.eventId)
        assertNotEquals(first.eventId, second.eventId)
    }

    @Test
    fun eventSubKeepsStructuredFragmentsAndCurrentMessageMetadata() {
        val gifUrl = "https://clips-media-assets2.twitch.tv/gif?token=keep-exactly"
        val event = JSONObject(
            """
            {
              "broadcaster_user_id":"broadcaster",
              "chatter_user_id":"chatter",
              "chatter_user_login":"viewer_login",
              "chatter_user_name":"Viewer",
              "color":"#123456",
              "message_id":"message-1",
              "message_type":"power_ups_gigantified_emote",
              "badges":[{"set_id":"subscriber","id":"12","info":"Tier 1"}],
              "reply":{
                "parent_message_id":"parent",
                "parent_message_body":"parent body",
                "parent_user_id":"parent-user",
                "parent_user_name":"Parent",
                "parent_user_login":"parent_login",
                "thread_message_id":"thread",
                "thread_user_id":"thread-user",
                "thread_user_name":"Thread",
                "thread_user_login":"thread_login"
              },
              "source_broadcaster_user_id":"source",
              "source_broadcaster_user_login":"source_login",
              "source_broadcaster_user_name":"Source",
              "source_message_id":"source-message",
              "source_badges":[{"set_id":"moderator","id":"1","info":"Mod"}],
              "is_source_only":true,
              "message":{
                "text":"Kappa @friend Cheer100 party",
                "fragments":[
                  {"type":"emote","text":"Kappa","emote":{"id":"25","emote_set_id":"set","owner_id":"owner","format":["static","animated"]}},
                  {"type":"text","text":" "},
                  {"type":"mention","text":"@friend","mention":{"user_id":"mentioned","user_login":"friend","user_name":"Friend"}},
                  {"type":"text","text":" "},
                  {"type":"cheermote","text":"Cheer100","cheermote":{"prefix":"Cheer","bits":100,"tier":1}},
                  {"type":"text","text":" party"},
                  {"type":"gif","text":"party","gif":{"gif_id":"gif-1","url":"%s"}}
                ]
              }
            }
            """.trimIndent().format(gifUrl),
        )

        val message = (TwitchChatEventParser.fromEventSub(event, "2026-09-01T12:00:00Z") as ChatEvent.Message).message
        assertEquals("message-1", message.id.value)
        assertEquals(TwitchChatMessageType.GigantifiedEmote, message.twitchType)
        assertEquals("#123456", "#%06X".format(message.user!!.color!! and 0xFFFFFF))
        assertEquals("subscriber", message.badges.single().setId)
        assertEquals("12", message.badges.single().versionId)
        assertEquals("parent", message.reply!!.parentMessageId.value)
        assertEquals("parent body", message.reply.parentMessageBody)
        assertEquals("thread_login", message.reply.threadUserLogin)
        assertEquals("source-message", message.source!!.messageId!!.value)
        assertTrue(message.source.sourceOnly)

        assertTrue(message.segments[0] is ChatSegment.Emote)
        assertEquals("25", (message.segments[0] as ChatSegment.Emote).interaction?.id)
        assertEquals("TWITCH", (message.segments[0] as ChatSegment.Emote).interaction?.provider?.name)
        assertEquals("mentioned", (message.segments[2] as ChatSegment.Mention).userId)
        assertEquals(100, (message.segments[4] as ChatSegment.Cheermote).bits)
        val gif = message.segments.last() as ChatSegment.Gif
        assertEquals("gif-1", gif.gifId)
        assertEquals(gifUrl, gif.url)
    }

    @Test
    fun unknownMessageTypeAndUnknownFragmentAreNonFatal() {
        val event = JSONObject(
            """
            {
              "broadcaster_user_id":"broadcaster",
              "message_id":"message-2",
              "message_type":"future_type",
              "message":{"fragments":[{"type":"future_fragment","text":"future text"}]}
            }
            """.trimIndent(),
        )

        val message = (TwitchChatEventParser.fromEventSub(event, null) as ChatEvent.Message).message
        assertEquals(TwitchChatMessageType.Unknown("future_type"), message.twitchType)
        assertEquals(ChatSegment.Text("future text"), message.segments.single())
    }

    @Test
    fun eventSubKeepsFirstChatterAndWatchStreakMetadata() {
        val first = JSONObject(
            """
            {
              "broadcaster_user_id":"broadcaster",
              "chatter_user_id":"chatter",
              "chatter_user_name":"Viewer",
              "message_id":"first",
              "message_type":"user_intro",
              "message":{"text":"hello","fragments":[{"type":"text","text":"hello"}]}
            }
            """.trimIndent(),
        )
        val firstMessage = (TwitchChatEventParser.fromEventSub(first, null) as ChatEvent.Message).message
        assertTrue(firstMessage.isFirst)
        assertEquals(TwitchChatMessageType.UserIntro, firstMessage.twitchType)

        val streak = JSONObject(
            """
            {
              "broadcaster_user_id":"broadcaster",
              "chatter_user_id":"chatter",
              "chatter_user_name":"Viewer",
              "notice_type":"watch_streak",
              "system_message":"Viewer is on a streak",
              "watch_streak":{"streak_count":7,"channel_points_awarded":700}
            }
            """.trimIndent(),
        )
        val streakMessage = (TwitchChatEventParser.fromEventSub(streak, null, notice = true) as ChatEvent.Message).message
        assertEquals(7, streakMessage.watchStreakCount)
        assertEquals(700, streakMessage.watchStreakPoints)
        assertEquals("watch_streak", streakMessage.noticeType)
    }

    @Test
    fun eventSubFirstMessageNotificationIsShownAsFirstChatter() {
        val event = JSONObject(
            """
            {
              "broadcaster_user_id":"broadcaster",
              "chatter_user_id":"chatter",
              "chatter_user_login":"viewer",
              "chatter_user_name":"Viewer",
              "message_id":"first-notice",
              "notice_type":"first_message",
              "message":{"text":"hello","fragments":[{"type":"text","text":"hello"}]}
            }
            """.trimIndent(),
        )

        val message = (TwitchChatEventParser.fromEventSub(event, null, notice = true) as ChatEvent.Message).message
        assertTrue(message.isFirst)
        assertEquals("first_message", message.noticeType)
    }

    @Test
    fun rewardRedemptionWithoutUserInputRemainsAChatEvent() {
        val event = JSONObject(
            """
            {
              "id":"redemption-1",
              "broadcaster_user_id":"broadcaster",
              "user_id":"user",
              "user_login":"viewer",
              "user_name":"Viewer",
              "user_input":"",
              "redeemed_at":"2026-09-01T12:00:00Z",
              "reward":{"id":"reward-1","title":"Sound Alert","cost":1000}
            }
            """.trimIndent(),
        )

        val message = TwitchChatEventParser.fromEventSubRewardRedemption(event, null).message
        assertEquals("redemption-1", message.id.value)
        assertEquals(com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind.REWARD, message.kind)
        assertEquals("reward-1", message.rewardId)
        assertTrue(message.segments.all { it is ChatSegment.Text && it.text.isBlank() })
    }

    @Test
    fun unrestrictedHermesRewardKeepsNoInputRedemptionMetadata() {
        val event = JSONObject(
            """
            {
              "data": {
                "timestamp":"2026-09-01T12:00:00Z",
                "redemption": {
                  "id":"redemption-hermes-1",
                  "user":{"id":"user","login":"viewer","display_name":"Viewer"},
                  "user_input":"",
                  "reward":{"id":"reward-1","title":"Sound Alert","cost":1000,
                    "default_image":{"url_1x":"https://example.test/reward.png"}}
                }
              }
            }
            """.trimIndent(),
        )

        val message = TwitchChatEventParser.fromPubSubReward(
            PubSubUtils.parseRewardMessage(event),
            "broadcaster",
        ).message
        assertEquals("reward-redemption-hermes-1", message.id.value)
        assertEquals("redemption-hermes-1", message.rewardRedemptionId)
        assertEquals("reward-1", message.rewardId)
        assertEquals("Sound Alert", message.rewardTitle)
        assertTrue(message.segments.isEmpty())
    }

    @Test
    fun ircUserNoticeIsNormalizedAsNotice() {
        val message = ChatUtils.IRCMessage(
            tags = mapOf("msg-id" to "sub", "system-msg" to "Viewer subscribed", "msg-param-sub-plan" to "Prime"),
            prefix = "user!user@user.tmi.twitch.tv",
            command = "USERNOTICE",
            params = listOf("#channel", "hello"),
            fullMessage = "USERNOTICE #channel :hello",
        )
        val event = TwitchChatEventParser.fromIrc(message, "channel-id") as ChatEvent.Message

        assertEquals(com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind.NOTICE, event.message.kind)
        assertEquals("Prime", event.message.subscriptionPlan)
        assertEquals(true, event.message.isPrimeSubscription)
        assertEquals("Prime", event.message.subscription?.tier)
    }

    @Test
    fun ircGiftNoticeKeepsStructuredRecipientCountAndAnonymousState() {
        val event = TwitchChatEventParser.fromIrc(
            ChatUtils.IRCMessage(
                tags = mapOf(
                    "msg-id" to "subgift",
                    "msg-param-sub-plan" to "1000",
                    "msg-param-recipient-display-name" to "Recipient",
                    "msg-param-gifter-is-anonymous" to "1",
                ),
                prefix = "gifter!gifter@gifter.tmi.twitch.tv",
                command = "USERNOTICE",
                params = listOf("#channel"),
                fullMessage = "USERNOTICE #channel",
            ),
            "channel-id",
        ) as ChatEvent.Message

        assertEquals("1000", event.message.subscription?.tier)
        assertEquals("Recipient", event.message.subscription?.recipientName)
        assertEquals(true, event.message.subscription?.isAnonymous)
    }

    @Test
    fun eventSubResubKeepsStructuredMonthsStreakAndTier() {
        val event = JSONObject(
            """
            {
              "broadcaster_user_id":"broadcaster",
              "chatter_user_id":"viewer",
              "chatter_user_name":"Viewer",
              "message_id":"resub-1",
              "notice_type":"shared_chat_resub",
              "shared_chat_resub":{"sub_tier":"2000","cumulative_months":6,"streak_months":4},
              "message":{"text":"Thanks!","fragments":[{"type":"text","text":"Thanks!"}]}
            }
            """.trimIndent(),
        ).let { TwitchChatEventParser.fromEventSub(it, null, notice = true) as ChatEvent.Message }

        assertEquals("2000", event.message.subscription?.tier)
        assertEquals(6, event.message.subscription?.months)
        assertEquals(4, event.message.subscription?.streakMonths)
    }

    @Test
    fun eventSubSharedChatRaidUsesRaidPresentation() {
        val event = JSONObject(
            """
            {
              "broadcaster_user_id":"broadcaster",
              "chatter_user_id":"viewer",
              "chatter_user_name":"Viewer",
              "message_id":"shared-raid-1",
              "notice_type":"shared_chat_raid",
              "shared_chat_raid":{"user_id":"raider","user_login":"raider","user_name":"Raider","viewer_count":42}
            }
            """.trimIndent(),
        )

        val message = (TwitchChatEventParser.fromEventSub(event, null, notice = true) as ChatEvent.Message).message
        val row = ChatRowCompiler().compile(message)

        assertEquals(ChatMessageKind.RAID, message.kind)
        assertEquals(ChatEventKind.RAID, row.eventPresentation?.kind)
    }

    @Test
    fun eventSubAnonymousCommunityGiftUsesTopLevelChatterAnonymity() {
        val event = JSONObject(
            """
            {
              "broadcaster_user_id":"broadcaster",
              "chatter_user_id":"anonymous",
              "chatter_user_name":"Anonymous",
              "chatter_is_anonymous":true,
              "message_id":"community-gift-1",
              "notice_type":"community_sub_gift",
              "community_sub_gift":{"total":5,"sub_tier":"1000","cumulative_total":5}
            }
            """.trimIndent(),
        )

        val message = (TwitchChatEventParser.fromEventSub(event, null, notice = true) as ChatEvent.Message).message

        assertEquals(true, message.subscription?.isAnonymous)
        assertEquals(true, message.subscription?.isCommunityGift)
        assertEquals(5, message.subscription?.giftCount)
    }

    @Test
    fun ircHighlightedMessageKeepsItsHighlightedType() {
        val message = ChatUtils.IRCMessage(
            tags = mapOf(
                "msg-id" to "highlighted-message",
                "display-name" to "Viewer",
            ),
            prefix = "viewer!viewer@viewer.tmi.twitch.tv",
            command = "PRIVMSG",
            params = listOf("#channel", "Lock them up!"),
            fullMessage = "PRIVMSG #channel :Lock them up!",
        )

        val event = TwitchChatEventParser.fromIrc(message, "channel-id") as ChatEvent.Message

        assertEquals(TwitchChatMessageType.Highlighted, event.message.twitchType)
        assertEquals(null, event.message.rewardId)
    }

    @Test
    fun eventSubHighlightedMessageHasNoCustomRewardId() {
        val event = JSONObject(
            """
            {
              "broadcaster_user_id":"broadcaster",
              "chatter_user_id":"chatter",
              "chatter_user_login":"viewer",
              "chatter_user_name":"Viewer",
              "message_id":"highlight-1",
              "message_type":"channel_points_highlighted",
              "message":{"text":"Lock them up!","fragments":[{"type":"text","text":"Lock them up!"}]}
            }
            """.trimIndent(),
        )

        val message = (TwitchChatEventParser.fromEventSub(event, "2026-09-01T12:00:00Z") as ChatEvent.Message).message

        assertEquals(TwitchChatMessageType.Highlighted, message.twitchType)
        assertEquals(null, message.rewardId)
    }

    @Test
    fun highlightedWireFormatsRenderConfiguredAutomaticReward() {
        val irc = (TwitchChatEventParser.fromIrc(
            ChatUtils.IRCMessage(
                tags = mapOf(
                    "msg-id" to "highlighted-message",
                    "display-name" to "Viewer",
                ),
                prefix = "viewer!viewer@viewer.tmi.twitch.tv",
                command = "PRIVMSG",
                params = listOf("#channel", "Lock them up!"),
                fullMessage = "PRIVMSG #channel :Lock them up!",
            ),
            "channel-id",
        ) as ChatEvent.Message).message
        val eventSub = (TwitchChatEventParser.fromEventSub(
            JSONObject(
                """
                {
                  "broadcaster_user_id":"broadcaster",
                  "chatter_user_id":"chatter",
                  "chatter_user_login":"viewer",
                  "chatter_user_name":"Viewer",
                  "message_id":"highlight-1",
                  "message_type":"channel_points_highlighted",
                  "message":{"text":"Lock them up!","fragments":[{"type":"text","text":"Lock them up!"}]}
                }
                """.trimIndent(),
            ),
            "2026-09-01T12:00:00Z",
        ) as ChatEvent.Message).message
        val catalog = ChatCatalogSnapshot(
            0,
            automaticChannelPointRewards = mapOf(
                HIGHLIGHTED_MESSAGE_REWARD_TYPE to ChatReward("Highlight My Message", 2_000, null),
            ),
        )
        listOf(irc, eventSub).forEach { message ->
            val row = ChatRowCompiler().compile(message, catalog)
            val text = row.pieces.filterIsInstance<ChatPiece.Text>().joinToString("") { it.value }
            assertTrue(row.pieces.any { it is ChatPiece.Text && it.value.contains("Highlight My Message") && it.bold })
            assertTrue(row.pieces.any { it is ChatPiece.Icon && it.drawableRes == R.drawable.ic_chat_channel_points })
            assertTrue(text.filter(Char::isDigit).contains("2000"))
            assertTrue(text.contains("Lock them up!"))
            assertEquals(ChatEventKind.HIGHLIGHT, row.eventPresentation?.kind)
        }
    }

    @Test
    fun eventSubUsesTypedPrimeFlagInsteadOfSubscriptionTierText() {
        val event = JSONObject(
            """
            {
              "broadcaster_user_id":"broadcaster",
              "chatter_user_id":"viewer",
              "chatter_user_name":"Viewer",
              "message_id":"sub-1",
              "notice_type":"sub",
              "sub_tier":"1000",
              "is_prime":true,
              "system_message":"Viewer subscribed with Prime Gaming."
            }
            """.trimIndent(),
        )

        val message = (TwitchChatEventParser.fromEventSub(event, null, notice = true) as ChatEvent.Message).message
        assertEquals("1000", message.subscriptionTier)
        assertEquals(true, message.isPrimeSubscription)
    }

    @Test
    fun eventSubReadsPrimeMetadataFromAllSubscriptionObjects() {
        listOf("resub", "shared_chat_sub", "shared_chat_resub").forEach { objectName ->
            val event = JSONObject(
                """
                {
                  "notice_type":"$objectName",
                  "$objectName":{"sub_tier":"1000","is_prime":true},
                  "system_message":"Viewer subscribed with Prime Gaming."
                }
                """.trimIndent(),
            )

            val message = (TwitchChatEventParser.fromEventSub(event, null, notice = true) as ChatEvent.Message).message
            assertEquals("1000", message.subscriptionPlan)
            assertEquals(true, message.isPrimeSubscription)
        }
    }

    @Test
    fun eventSubPaidSubscriptionMetadataRemainsNonPrime() {
        val event = JSONObject(
            """
            {
              "notice_type":"shared_chat_resub",
              "shared_chat_resub":{"sub_tier":"1000","is_prime":false},
              "system_message":"Viewer subscribed with Tier 1."
            }
            """.trimIndent(),
        )

        val message = (TwitchChatEventParser.fromEventSub(event, null, notice = true) as ChatEvent.Message).message
        assertEquals("1000", message.subscriptionPlan)
        assertEquals(false, message.isPrimeSubscription)
    }

    @Test
    fun ircGifOnlyMessageUsesTheProvidedUrlAndFallbackText() {
        val text = "[Dog Eat GIF by Respective]"
        val url = "https://media4.giphy.com/media/gif/giphy.gif?token=a=b&format=webp"
        val event = TwitchChatEventParser.fromIrc(
            ircMessage(text, mapOf("gifs" to "0-${text.lastIndex}|gif-1|$url")),
            "channel-id",
        ) as ChatEvent.Message

        assertEquals(
            ChatSegment.Gif("gif-1", url, text),
            event.message.segments.single(),
        )
    }

    @Test
    fun ircGifKeepsTextBeforeAndAfterIt() {
        val text = "hello [gif] world"
        val event = TwitchChatEventParser.fromIrc(
            ircMessage(text, mapOf("gifs" to "6-10|gif-1|https://example.test/one.gif")),
            "channel-id",
        ) as ChatEvent.Message

        assertEquals(
            listOf(
                ChatSegment.Text("hello "),
                ChatSegment.Gif("gif-1", "https://example.test/one.gif", "[gif]"),
                ChatSegment.Text(" world"),
            ),
            event.message.segments,
        )
    }

    @Test
    fun ircGifsAndNativeEmotesAreMergedInTextOrder() {
        val event = TwitchChatEventParser.fromIrc(
            ircMessage(
                "GIF LUL",
                mapOf(
                    "gifs" to "0-2|gif-1|https://example.test/one.gif",
                    "emotes" to "425618:4-6",
                ),
            ),
            "channel-id",
        ) as ChatEvent.Message

        assertTrue(event.message.segments[0] is ChatSegment.Gif)
        assertEquals(ChatSegment.Text(" "), event.message.segments[1])
        assertTrue(event.message.segments[2] is ChatSegment.Emote)
        assertEquals("425618", (event.message.segments[2] as ChatSegment.Emote).interaction?.id)
    }

    @Test
    fun ircSupportsMultipleGifs() {
        val event = TwitchChatEventParser.fromIrc(
            ircMessage(
                "A B C",
                mapOf(
                    "gifs" to "0-0|gif-a|https://example.test/a.gif,2-2|gif-b|https://example.test/b.gif,4-4|gif-c|https://example.test/c.gif",
                ),
            ),
            "channel-id",
        ) as ChatEvent.Message

        assertEquals(
            listOf(
                ChatSegment.Gif("gif-a", "https://example.test/a.gif", "A"),
                ChatSegment.Text(" "),
                ChatSegment.Gif("gif-b", "https://example.test/b.gif", "B"),
                ChatSegment.Text(" "),
                ChatSegment.Gif("gif-c", "https://example.test/c.gif", "C"),
            ),
            event.message.segments,
        )
    }

    @Test
    fun malformedOrOutOfRangeIrcGifRemainsPlainText() {
        val malformedText = "[broken gif]"
        val malformed = TwitchChatEventParser.fromIrc(
            ircMessage(malformedText, mapOf("gifs" to "not-a-gif")),
            "channel-id",
        ) as ChatEvent.Message
        assertEquals(listOf(ChatSegment.Text(malformedText)), malformed.message.segments)

        val outOfRangeText = "GIF"
        val outOfRange = TwitchChatEventParser.fromIrc(
            ircMessage(outOfRangeText, mapOf("gifs" to "0-99|gif-1|https://example.test/one.gif")),
            "channel-id",
        ) as ChatEvent.Message
        assertEquals(listOf(ChatSegment.Text(outOfRangeText)), outOfRange.message.segments)
    }

    @Test
    fun anonymousIrcMessageStillProducesGifSegment() {
        val text = "[Dog Eat GIF by Respective]"
        val message = ChatUtils.IRCMessage(
            tags = mapOf("gifs" to "0-${text.lastIndex}|gif-1|https://example.test/one.gif"),
            prefix = "justinfan123!justinfan123@justinfan123.tmi.twitch.tv",
            command = "PRIVMSG",
            params = listOf("#channel", text),
            fullMessage = text,
        )

        val event = TwitchChatEventParser.fromIrc(message, "channel-id") as ChatEvent.Message

        assertTrue(event.message.user?.id == null)
        assertTrue(event.message.segments.single() is ChatSegment.Gif)
    }

    @Test
    fun ircTagParsingPreservesGifUrlQueryParameters() {
        val url = "https://media4.giphy.com/gif.gif?token=a=b&format=webp"
        val message = ChatUtils.parseIRCMessage(
            "@gifs=0-2|gif-1|$url;id=message-1 :viewer!viewer@viewer.tmi.twitch.tv PRIVMSG #channel :GIF",
        )

        assertEquals("0-2|gif-1|$url", message.tags["gifs"])
        val event = TwitchChatEventParser.fromIrc(message, "channel-id") as ChatEvent.Message
        assertEquals(ChatSegment.Gif("gif-1", url, "GIF"), event.message.segments.single())
    }

    private fun ircMessage(text: String, extraTags: Map<String, String> = emptyMap()): ChatUtils.IRCMessage =
        ChatUtils.IRCMessage(
            tags = mapOf(
                "id" to "message-1",
                "display-name" to "Viewer",
                "login" to "viewer",
            ) + extraTags,
            prefix = "viewer!viewer@viewer.tmi.twitch.tv",
            command = "PRIVMSG",
            params = listOf("#channel", text),
            fullMessage = text,
        )
}
