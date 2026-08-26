package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchNotificationAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchInboxJsonParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesGenericNotificationsAndRejectsUnsafeActions() {
        val page = parseNotificationPage(json.parseToJsonElement(fixture("twitch_gql/notifications_page.json")).jsonObject)
        assertEquals(3, page.notifications.size)
        assertTrue(page.notifications.first().isUnread)
        assertFalse(page.notifications.first().canDismiss)
        assertTrue(page.notifications.first().action is TwitchNotificationAction.TwitchWebUrl)
        assertFalse(page.notifications[1].isUnread)
        assertEquals(TwitchNotificationAction.None, page.notifications[1].action)
        assertEquals(TwitchNotificationAction.Channel("channel-1", "channel_login", "Channel Name", "https://static-cdn.jtvnw.net/channel.png"), page.notifications[2].action)
        assertTrue(page.hasNextPage)
        assertEquals("notification-cursor-3", page.nextCursor)
    }

    @Test
    fun buildsNotificationCursorVariables() {
        val first = buildNotificationVariables(null, 20, "de")
        assertEquals("20", first.getValue("first").jsonPrimitive.content)
        assertEquals("de", first.getValue("language").jsonPrimitive.content)
        assertEquals("VIEWER", first.getValue("displayType").jsonPrimitive.content)
        assertFalse(first.containsKey("after"))

        val next = buildNotificationVariables("cursor-2", 200, "unsupported")
        assertEquals("50", next.getValue("first").jsonPrimitive.content)
        assertEquals("cursor-2", next.getValue("after").jsonPrimitive.content)
    }

    @Test
    fun selectsPeerAndPreservesWhisperCursor() {
        val page = parseWhisperThreadPage(json.parseToJsonElement(fixture("twitch_gql/whisper_threads.json")).jsonObject, "user-1")
        assertEquals("user-2", page.threads.single().peer.id)
        assertTrue(page.threads.single().isUnread)
        assertEquals("thread-cursor-1", page.nextCursor)

        val details = parseWhisperThreadDetails(json.parseToJsonElement(fixture("twitch_gql/whisper_messages.json")).jsonObject, "user-1")
        assertEquals(listOf("message-1", "message-2"), details.messages.map { it.id })
        assertEquals("message-cursor-1", details.messages.first().cursor)
        assertFalse(details.messages.first().isMine)
        assertTrue(details.messages.last().isMine)
        assertTrue(details.hasOlderMessages)
    }

    private fun fixture(path: String): String = javaClass.classLoader!!.getResource(path)!!.readText()
}
