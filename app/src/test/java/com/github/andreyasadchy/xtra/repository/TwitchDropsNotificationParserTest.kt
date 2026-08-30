package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchNotificationAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class TwitchDropsNotificationParserTest {
    private val json = Json.Default

    @Test
    fun `drops urls become native drops actions`() {
        assertTrue(parseNotification(notification("https://www.twitch.tv/drops/inventory"))!!.action is TwitchNotificationAction.Drops)
        assertTrue(parseNotification(notification("https://www.twitch.tv/drops"))!!.action is TwitchNotificationAction.Drops)
    }

    @Test
    fun `drops url is used when action type is not click`() {
        val parsed = parseNotification(
            notification("https://www.twitch.tv/drops/inventory", actionType = "navigate"),
        )
        assertTrue(parsed?.action is TwitchNotificationAction.Drops)
    }

    @Test
    fun `ordinary safe twitch urls remain web actions`() {
        assertTrue(parseNotification(notification("https://www.twitch.tv/videos/123"))!!.action is TwitchNotificationAction.TwitchWebUrl)
    }

    @Test
    fun `body text alone does not identify a drop`() {
        val parsed = parseNotification(
            json.parseToJsonElement("""{"id":"notification-1","body":"You mentioned a drop","actions":[]}""").jsonObject,
        )
        assertFalse(parsed?.action is TwitchNotificationAction.Drops)
    }

    @Test
    fun `structured drop type identifies a drop without localized text`() {
        val parsed = parseNotification(
            json.parseToJsonElement("""{"id":"notification-1","body":"Localized text","type":"DROP_REWARD","actions":[]}""").jsonObject,
        )
        assertTrue(parsed?.action is TwitchNotificationAction.Drops)
    }

    private fun notification(url: String, actionType: String = "click") = buildJsonObject {
        put("id", "notification-1")
        put("body", "Localized notification")
        put("actions", kotlinx.serialization.json.buildJsonArray {
            add(buildJsonObject {
                put("type", actionType)
                put("url", url)
            })
        })
    }
}
