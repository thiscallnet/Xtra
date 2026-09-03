package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.model.chat.ChannelPointReward
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialChatMessageParsingTest {

    @Test
    fun parsesIrcWatchStreakWithViewerMessage() {
        val message = ChatUtils.parseChatMessage(
            ChatUtils.parseIRCMessage(
                """
                @badges=;color=#9147ff;display-name=TwitchDev;emotes=;id=message-id;login=twitchdev;msg-id=viewermilestone;msg-param-category=watch-streak;msg-param-copoReward=450;msg-param-value=10;room-id=channel-id;system-msg=TwitchDev\\sreached\\sa\\s10-stream\\sstreak!;tmi-sent-ts=1700000000000;user-id=user-id :twitchdev!twitchdev@twitch.tv USERNOTICE #channel :Aware
                """.trimIndent(),
            ),
        )

        assertEquals("viewermilestone", message.msgId)
        assertEquals("Aware", message.message)
        assertEquals(10, message.watchStreakCount)
        assertEquals(450, message.watchStreakPoints)
        assertTrue(message.isWatchStreakNotice())
    }

    @Test
    fun normalizesSharedChatWatchStreakNotice() {
        val message = ChatUtils.parseChatMessage(
            ChatUtils.parseIRCMessage(
                """
                @badges=;color=#9147ff;display-name=TwitchDev;emotes=;id=message-id;login=twitchdev;msg-id=sharedchatnotice;msg-param-category=watch-streak;msg-param-copoReward=450;msg-param-value=10;room-id=channel-id;source-msg-id=viewermilestone;system-msg=TwitchDev\\sreached\\sa\\s10-stream\\sstreak!;tmi-sent-ts=1700000000000;user-id=user-id :twitchdev!twitchdev@twitch.tv USERNOTICE #channel :Aware
                """.trimIndent(),
            ),
        )

        assertEquals("sharedchatnotice", message.msgId)
        assertEquals("viewermilestone", message.sourceMsgId)
        assertEquals("viewermilestone", message.effectiveNoticeId())
        assertTrue(message.isWatchStreakNotice())
    }

    @Test
    fun parsesEventSubWatchStreakWithViewerMessage() {
        val message = EventSubUtils.parseUserNotice(
            JSONObject(
                """
                {
                  "message_id": "message-id",
                  "chatter_user_id": "user-id",
                  "chatter_user_login": "twitchdev",
                  "chatter_user_name": "TwitchDev",
                  "notice_type": "watch_streak",
                  "watch_streak": {
                    "streak_count": 10,
                    "channel_points_awarded": 450
                  },
                  "message": {
                    "text": "Aware",
                    "fragments": [
                      { "type": "text", "text": "Aware" }
                    ]
                  }
                }
                """.trimIndent(),
            ),
            null,
        )

        assertEquals("Aware", message.message)
        assertEquals(10, message.watchStreakCount)
        assertEquals(450, message.watchStreakPoints)
        assertTrue(message.isWatchStreakNotice())
    }

    @Test
    fun eventSubSystemOnlySubscriptionKeepsChatterColor() {
        val message = EventSubUtils.parseUserNotice(
            JSONObject(
                """
                {
                  "chatter_user_id": "gifter-id",
                  "chatter_user_login": "renagade45",
                  "chatter_user_name": "renagade45",
                  "color": "#9147ff",
                  "notice_type": "sub_gift",
                  "system_message": "renagade45 gifted a Tier 1 Sub to posty's community!",
                  "message": {"text": "", "fragments": []}
                }
                """.trimIndent(),
            ),
            null,
        )

        assertEquals("#9147ff", message.color)
        assertEquals("sub_gift", message.msgId)
    }

    @Test
    fun legacyEventSubReadsPrimeMetadataFromSharedChatSubscriptionObjects() {
        listOf("resub", "shared_chat_sub", "shared_chat_resub").forEach { objectName ->
            val message = EventSubUtils.parseUserNotice(
                JSONObject(
                    """
                    {
                      "notice_type": "$objectName",
                      "$objectName": {"sub_tier": "1000", "is_prime": true},
                      "system_message": "Viewer subscribed with Prime Gaming.",
                      "message": {"text": "", "fragments": []}
                    }
                    """.trimIndent(),
                ),
                null,
            )

            assertEquals("1000", message.subscriptionPlan)
            assertTrue(message.isPrimeSubscription == true)
        }
    }

    @Test
    fun sharedChatSubscriptionUsesOriginalNoticeId() {
        val message = ChatUtils.parseChatMessage(
            ChatUtils.parseIRCMessage(
                "@badges=;display-name=TwitchDev;login=twitchdev;msg-id=sharedchatnotice;source-msg-id=sub;system-msg=TwitchDev\\ssubscribed;user-id=user-id :twitchdev!twitchdev@twitch.tv USERNOTICE #channel",
            ),
        )

        assertEquals("sharedchatnotice", message.msgId)
        assertEquals("sub", message.sourceMsgId)
        assertEquals("sub", message.effectiveNoticeId())
        assertTrue(message.isSubscriptionNotice())
        assertFalse(ChatMessage(msgId = "sharedchatnotice").isSubscriptionNotice())
    }

    @Test
    fun highlightedMessageClassificationUsesTransportValues() {
        val ircMessage = ChatUtils.parseChatMessage(
            ChatUtils.parseIRCMessage(
                "@badges=;display-name=TwitchDev;login=twitchdev;msg-id=highlighted-message;user-id=user-id :twitchdev!twitchdev@twitch.tv PRIVMSG #channel :Highlighted",
            ),
        )
        val eventSubMessage = EventSubUtils.parseChatMessage(
            JSONObject(
                """{
                    "message_type": "channel_points_highlighted",
                    "chatter_user_id": "user-id",
                    "message": {
                        "text": "Highlighted",
                        "fragments": [{"type": "text", "text": "Highlighted"}]
                    }
                }""".trimIndent(),
            ),
            null,
        )

        assertTrue(ircMessage.isHighlightedMessage())
        assertTrue(eventSubMessage.isHighlightedMessage())
        assertTrue(ChatMessage(reward = ChannelPointReward(title = "Highlight My Message")).isHighlightedMessage())
        assertFalse(ChatMessage(msgId = "not-highlighted").isHighlightedMessage())
    }
}
