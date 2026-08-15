package com.github.andreyasadchy.xtra.ui.account

import com.github.andreyasadchy.xtra.model.helix.channel.ChannelInformation
import com.github.andreyasadchy.xtra.repository.buildChannelInformationUpdateBody
import com.github.andreyasadchy.xtra.repository.buildChatSettingsUpdateBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountContractsTest {

    @Test
    fun `channel language uses Twitch broadcaster language field`() {
        val channel = Json.decodeFromString<ChannelInformation>("""
            {"broadcaster_id":"1","broadcaster_language":"sk","title":"Live"}
        """.trimIndent())

        assertEquals("sk", channel.language)

        val body = Json.parseToJsonElement(
            buildChannelInformationUpdateBody(language = "sk"),
        ).jsonObject
        assertEquals("sk", body["broadcaster_language"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("language"))
    }

    @Test
    fun `scopes map to account capabilities`() {
        val capabilities = AccountCapabilities.from(
            setOf(
                "user:edit",
                "user:manage:chat_color",
                "channel:manage:broadcast",
                "moderator:manage:chat_settings",
                "user:read:blocked_users",
            ),
        )

        assertTrue(capabilities.editBio)
        assertTrue(capabilities.editChatColor)
        assertTrue(capabilities.editChannel)
        assertTrue(capabilities.editChatSettings)
        assertTrue(capabilities.readBlockedUsers)
        assertFalse(capabilities.manageBlockedUsers)
    }

    @Test
    fun `chat duration updates include the required mode`() {
        val followerUpdate = normalizeChatSettingsUpdate(followersDuration = 10)
        assertEquals(true, followerUpdate.followers)
        val followerBody = Json.parseToJsonElement(
            buildChatSettingsUpdateBody(
                followers = followerUpdate.followers,
                followersDuration = followerUpdate.followersDuration,
            ),
        ).jsonObject
        assertEquals(true, followerBody["follower_mode"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("10", followerBody["follower_mode_duration"]?.jsonPrimitive?.content)

        val slowUpdate = normalizeChatSettingsUpdate(slowDuration = 15)
        assertEquals(true, slowUpdate.slow)
        val slowBody = Json.parseToJsonElement(
            buildChatSettingsUpdateBody(
                slow = slowUpdate.slow,
                slowDuration = slowUpdate.slowDuration,
            ),
        ).jsonObject
        assertEquals(true, slowBody["slow_mode"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("15", slowBody["slow_mode_wait_time"]?.jsonPrimitive?.content)
    }

    @Test
    fun `predefined colors use Twitch names and custom colors use hex`() {
        assertEquals(
            listOf(
                "blue", "blue_violet", "cadet_blue", "chocolate", "coral",
                "dodger_blue", "firebrick", "golden_rod", "green", "hot_pink",
                "orange_red", "red", "sea_green", "spring_green", "yellow_green",
            ),
            TWITCH_CHAT_COLOR_OPTIONS.map { it.apiValue },
        )
        assertEquals("#0000FF", TWITCH_CHAT_COLOR_OPTIONS.first().hex)
        assertTrue(isValidCustomChatColor("#9146FF"))
        assertTrue(isValidCustomChatColor("#abcdef"))
        assertFalse(isValidCustomChatColor("blue"))
    }

    @Test
    fun `channel validation rejects empty titles and invalid tags`() {
        assertFalse(isValidStreamTitle(""))
        assertTrue(isValidStreamTitle("a".repeat(140)))
        assertFalse(isValidStreamTitle("a".repeat(141)))

        assertTrue(isValidAccountTag("Programming2"))
        assertFalse(isValidAccountTag("Game Dev"))
        assertFalse(isValidAccountTag("game_dev"))
        assertFalse(isValidAccountTag("game-dev"))
        assertFalse(isValidAccountTag(""))
    }
}
