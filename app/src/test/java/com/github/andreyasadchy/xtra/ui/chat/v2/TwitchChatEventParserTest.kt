package com.github.andreyasadchy.xtra.ui.chat.v2

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEvent
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.TwitchChatMessageType
import com.github.andreyasadchy.xtra.ui.chat.v2.transport.TwitchChatEventParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchChatEventParserTest {
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
                  {"type":"gif","text":"party","gif":{"id":"gif-1","url":"%s"}}
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
}
