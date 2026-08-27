package com.github.andreyasadchy.xtra.util.watch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

class WatchCreditTelemetryTest {
    @Test
    fun minuteWatchedPayloadUsesAnEventArray() {
        val payload = WatchCreditTelemetry.buildMinuteWatchedPayload(
            TwitchWatchSession(
                channelId = "channel-1",
                channelLogin = "channel",
                streamId = "broadcast-1",
                userId = "user-1",
                sessionId = "session-1",
            ),
            clientTimeMillis = 1_704_116_262_123L,
        )

        val root = Json.parseToJsonElement(payload)
        assertTrue(root is JsonArray)
        val event = (root as JsonArray).single().jsonObject
        assertEquals("minute-watched", event["event"]?.jsonPrimitive?.content)
        val properties = event["properties"]!!.jsonObject
        assertEquals("broadcast-1", properties["broadcast_id"]?.jsonPrimitive?.content)
        assertEquals("channel-1", properties["channel_id"]?.jsonPrimitive?.content)
        assertEquals("channel", properties["channel"]?.jsonPrimitive?.content)
        assertEquals("user-1", properties["user_id"]?.jsonPrimitive?.content)
        assertEquals("2024-01-01T13:37:42.123Z", properties["client_time"]?.jsonPrimitive?.content)
        assertEquals("channel", properties["location"]?.jsonPrimitive?.content)
        assertEquals("site", properties["player"]?.jsonPrimitive?.content)
        assertEquals("1", properties["minutes_logged"]?.jsonPrimitive?.content)
        assertEquals("true", properties["live"]?.jsonPrimitive?.content)
        assertEquals("true", properties["is_live"]?.jsonPrimitive?.content)
        assertEquals("true", properties["logged_in"]?.jsonPrimitive?.content)
        assertEquals("false", properties["hidden"]?.jsonPrimitive?.content)
        assertEquals("false", properties["muted"]?.jsonPrimitive?.content)
    }

    @Test
    fun spadeFormBodyUrlEncodesBase64Value() {
        val payload = "{\"value\":\"\u00BE\u00BF\u00BF\",\"x\":\"\"}"
        val formBody = encodeSpadeFormBody(payload).toString(StandardCharsets.UTF_8)
        val encodedValue = formBody.removePrefix("data=")

        assertTrue(encodedValue.contains("%2B"))
        assertTrue(encodedValue.contains("%2F"))
        assertTrue(encodedValue.contains("%3D"))

        val base64 = URLDecoder.decode(encodedValue, StandardCharsets.UTF_8.name())
        assertEquals(
            payload,
            Base64.getDecoder().decode(base64).toString(StandardCharsets.UTF_8),
        )
    }

    @Test
    fun directSpadeUrlParserSupportsAllKnownPropertyNames() {
        assertEquals(
            "https://spade.twitch.tv/track",
            WatchCreditTelemetry.extractSpadeUrl("{\"spadeUrl\":\"https:\\/\\/spade.twitch.tv/track\"}"),
        )
        assertEquals(
            "https://spade.twitch.tv/track",
            WatchCreditTelemetry.extractSpadeUrl("{\"spade_url\":\"https://spade.twitch.tv/track\"}"),
        )
        assertEquals(
            "https://spade.twitch.tv/track",
            WatchCreditTelemetry.extractSpadeUrl("{\"beacon_url\":\"https://spade.twitch.tv/track\"}"),
        )
    }

    @Test
    fun settingsUrlParserSupportsStaticTwitchCdnPath() {
        assertEquals(
            "https://static.twitchcdn.net/config/settings.a-b_c.123.js",
            WatchCreditTelemetry.extractSettingsUrl(
                "<script src=\"https://static.twitchcdn.net/config/settings.a-b_c.123.js\"></script>",
            ),
        )
    }

    @Test
    fun settingsUrlParserSupportsAssetsTwitchPath() {
        assertEquals(
            "https://assets.twitch.tv/config/settings.abc-def.js",
            WatchCreditTelemetry.extractSettingsUrl(
                "<script src=\"https://assets.twitch.tv/config/settings.abc-def.js\"></script>",
            ),
        )
    }

    @Test
    fun settingsUrlParserNormalizesEscapedSlashes() {
        assertEquals(
            "https://static.twitchcdn.net/config/settings.escaped-name.js",
            WatchCreditTelemetry.extractSettingsUrl(
                """<script src="https:\/\/static.twitchcdn.net\/config\/settings.escaped-name.js"></script>""",
            ),
        )
    }

    @Test
    fun statusPolicyAcceptsSuccessfulResponsesIncludingNoContent() {
        assertTrue(WatchCreditTelemetry.isSuccessfulStatus(200))
        assertTrue(WatchCreditTelemetry.isSuccessfulStatus(204))
        assertFalse(WatchCreditTelemetry.isSuccessfulStatus(199))
        assertFalse(WatchCreditTelemetry.isSuccessfulStatus(300))
    }
}
