package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.misc.BTTVResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRepositoryEmoteParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun globalBttvArrayUsesGlobalParserAndPreservesMetadata() {
        val emote = parseGlobalBTTVEmotes(
            """
            [
              {
                "id": "555981336ba1901877765555",
                "code": "haHAA",
                "animated": false,
                "width": 48,
                "height": 36,
                "modifier": false
              }
            ]
            """.trimIndent(),
            useWebp = true,
            json = json,
        ).single()

        assertEquals("haHAA", emote.name)
        assertEquals("555981336ba1901877765555", emote.id)
        assertEquals(Emote.GLOBAL_BTTV, emote.source)
        assertEquals(48, emote.width)
        assertEquals(36, emote.height)
        assertFalse(emote.isOverlayEmote)
    }

    @Test
    fun channelBttvParserRemainsObjectResponseParserAndSupportsModifiers() {
        val response = json.decodeFromString<Map<String, JsonElement>>(
            """
            {
              "channelEmotes": [
                {"id":"channel-id","code":"ChannelWave","animated":true,"width":72,"height":24,"modifier":true}
              ],
              "sharedEmotes": [],
              "bots": []
            }
            """.trimIndent(),
        )
        val emote = parseBTTVEmotes(
            response.values
                .filterIsInstance<JsonArray>()
                .flatten()
                .map { json.decodeFromJsonElement<BTTVResponse>(it) },
            useWebp = true,
            source = Emote.CHANNEL_BTTV,
        ).single()

        assertEquals("ChannelWave", emote.name)
        assertEquals(Emote.CHANNEL_BTTV, emote.source)
        assertEquals(72, emote.width)
        assertEquals(24, emote.height)
        assertTrue(emote.isOverlayEmote)
    }
}
