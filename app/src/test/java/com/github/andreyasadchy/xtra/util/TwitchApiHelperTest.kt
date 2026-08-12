package com.github.andreyasadchy.xtra.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class TwitchApiHelperTest {

    @Test
    fun `new timestamp values use the four supported formats`() {
        val previousLocale = Locale.getDefault()
        val previousTimeZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.US)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val timestamp = 1_704_116_262_000L // 2024-01-01 13:37:42 UTC

            assertEquals("13:37", TwitchApiHelper.getTimestamp(timestamp, "0"))
            assertEquals("13:37:42", TwitchApiHelper.getTimestamp(timestamp, "1"))
            assertEquals("1:37 PM", TwitchApiHelper.getTimestamp(timestamp, "2"))
            assertEquals("1:37:42 PM", TwitchApiHelper.getTimestamp(timestamp, "3"))
        } finally {
            Locale.setDefault(previousLocale)
            TimeZone.setDefault(previousTimeZone)
        }
    }

    @Test
    fun `unsupported timestamp values fall back to short 24 hour format`() {
        val previousLocale = Locale.getDefault()
        val previousTimeZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.US)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val timestamp = 1_704_116_262_000L // 2024-01-01 13:37:42 UTC

            assertEquals("13:37", TwitchApiHelper.getTimestamp(timestamp, "99"))
            assertEquals("13:37", TwitchApiHelper.getTimestamp(timestamp, null))
        } finally {
            Locale.setDefault(previousLocale)
            TimeZone.setDefault(previousTimeZone)
        }
    }

    @Test
    fun `developer overrides use production values while disabled`() {
        assertEquals("0", developerStringValue(C.API_LOGIN, "2", "0", enabled = false))
        assertEquals(C.DEFAULT_HELIX_CLIENT_ID, developerStringValue(C.HELIX_CLIENT_ID, "custom", null, false))
        assertEquals(C.DEFAULT_HELIX_REDIRECT, developerStringValue(C.HELIX_REDIRECT, "custom", null, false))
        assertEquals(C.DEFAULT_GQL_CLIENT_ID2, developerStringValue(C.GQL_CLIENT_ID2, "custom", null, false))
        assertEquals("https://www.twitch.tv/settings/connections", developerStringValue(C.GQL_REDIRECT2, "custom", null, false))
        assertEquals(C.DEFAULT_GQL_CLIENT_ID_WEB, developerStringValue(C.GQL_CLIENT_ID_WEB, "custom", null, false))
        assertEquals(C.OKHTTP, developerStringValue(C.NETWORK_LIBRARY, C.CRONET, null, false))
        assertEquals(C.EXOPLAYER, developerStringValue(C.PLAYER, C.MEDIA_PLAYER, null, false))
        assertNull(developerStringValue(C.PLAYER_STREAM_HEADERS, "secret", null, false))
        assertEquals(C.DEFAULT_TOKEN_X_DEVICE_ID, developerStringValue(C.TOKEN_X_DEVICE_ID, "custom", null, false))
        assertEquals(C.DEFAULT_TOKEN_PLAYER_TYPE, developerStringValue(C.TOKEN_PLAYER_TYPE, "custom", null, false))
        assertEquals(C.DEFAULT_TOKEN_SUPPORTED_CODECS, developerStringValue(C.TOKEN_SUPPORTED_CODECS, "custom", null, false))
        assertEquals(false, developerBooleanValue(C.DEBUG_EVENT_SUB_CHAT, true, enabled = false))
        assertEquals(false, developerBooleanValue(C.ENABLE_INTEGRITY, true, enabled = false))
        assertEquals(false, developerBooleanValue(C.PROXY_MULTIVARIANT_PLAYLIST, true, enabled = false))
        assertEquals(true, developerBooleanValue(C.TOKEN_RANDOM_DEVICE_ID, false, enabled = false))
    }

    @Test
    fun `enabled developer overrides remain effective`() {
        assertEquals("2", developerStringValue(C.API_LOGIN, "2", "0", enabled = true))
        assertEquals("custom", developerStringValue(C.HELIX_CLIENT_ID, "custom", null, enabled = true))
        assertEquals("custom", developerStringValue(C.GQL_CLIENT_ID_WEB, "custom", null, enabled = true))
        assertEquals(true, developerBooleanValue(C.DEBUG_EVENT_SUB_CHAT, true, enabled = true))
        assertEquals(false, developerBooleanValue(C.TOKEN_RANDOM_DEVICE_ID, false, enabled = true))
        assertEquals(C.OKHTTP, developerStringValue(C.NETWORK_LIBRARY, C.AUTOMATIC, null, enabled = true))
    }
}
