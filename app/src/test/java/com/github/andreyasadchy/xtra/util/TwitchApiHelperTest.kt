package com.github.andreyasadchy.xtra.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class TwitchApiHelperTest {

    @Test
    fun `ordinary gql headers do not carry protected web request metadata`() {
        val headers = TwitchApiHelper.buildGQLHeaders(
            clientId = "public-gql-client",
            accessToken = "web-token",
            cookieHeader = "auth-token=cookie-token",
        )

        assertEquals("public-gql-client", headers[C.HEADER_CLIENT_ID])
        assertEquals("OAuth web-token", headers[C.HEADER_TOKEN])
        assertEquals("auth-token=cookie-token", headers["Cookie"])
        assertNull(headers["Client-Session-Id"])
        assertNull(headers["X-Device-Id"])
        assertNull(headers["Origin"])
        assertNull(headers["Referer"])
        assertNull(headers["User-Agent"])
    }

    @Test
    fun `protected gql headers preserve the same request identity across calls`() {
        val first = TwitchApiHelper.buildWebGQLHeaders(
            clientId = "web-client",
            accessToken = "web-token",
            deviceId = "stable-device",
            clientSessionId = "stable-session",
            cookieHeader = "auth-token=cookie-token",
        )
        val second = TwitchApiHelper.buildWebGQLHeaders(
            clientId = "web-client",
            accessToken = "web-token",
            deviceId = "stable-device",
            clientSessionId = "stable-session",
            cookieHeader = "auth-token=cookie-token",
        )

        assertEquals(first["X-Device-Id"], second["X-Device-Id"])
        assertEquals(first["Client-Session-Id"], second["Client-Session-Id"])
        assertEquals("https://www.twitch.tv", first["Origin"])
        assertEquals("https://www.twitch.tv/", first["Referer"])
        assertEquals("OAuth web-token", first[C.HEADER_TOKEN])
    }

    @Test
    fun `captured browser headers replace generated identity metadata`() {
        val headers = TwitchApiHelper.buildCapturedWebGQLHeaders(
            fallbackClientId = "native-client",
            fallbackAccessToken = "native-token",
            cookieHeader = "auth-token=cookie-token",
            browserHeaders = mapOf(
                "Authorization" to "OAuth browser-token",
                "Client-Id" to "browser-client",
                "Client-Integrity" to "integrity",
                "Client-Session-Id" to "browser-session",
                "Client-Version" to "browser-version",
                "Content-Type" to "text/plain",
            ),
        )

        assertEquals("browser-client", headers[C.HEADER_CLIENT_ID])
        assertEquals("OAuth browser-token", headers[C.HEADER_TOKEN])
        assertEquals("integrity", headers["Client-Integrity"])
        assertEquals("browser-session", headers["Client-Session-Id"])
        assertEquals("browser-version", headers["Client-Version"])
        assertNull(headers["Content-Type"])
        assertNull(headers["X-Device-Id"])
        assertEquals("auth-token=cookie-token", headers["Cookie"])
    }

    @Test
    fun `protected headers mark missing browser integrity instead of silently degrading`() {
        val fallback = TwitchApiHelper.buildWebGQLHeaders(
            clientId = "native-client",
            accessToken = "native-token",
            deviceId = "stable-device",
            clientSessionId = "stable-session",
        )

        val headers = TwitchApiHelper.markUnavailableProtectedGQLHeaders(fallback)

        assertTrue(headers is ProtectedGqlHeadersUnavailable)
        assertEquals("OAuth native-token", headers[C.HEADER_TOKEN])
        assertNull(headers["Client-Integrity"])
    }

    @Test
    fun `recommendation headers keep the supplied client and stable request identity`() {
        val headers = TwitchApiHelper.buildRecommendationGQLHeaders(
            clientId = "private-gql-client",
            accessToken = "private-gql-token",
            deviceId = "stable-device",
            clientSessionId = "session-1",
        )

        assertEquals("private-gql-client", headers[C.HEADER_CLIENT_ID])
        assertEquals("OAuth private-gql-token", headers[C.HEADER_TOKEN])
        assertEquals("stable-device", headers["X-Device-Id"])
        assertEquals("session-1", headers["Client-Session-Id"])
        assertEquals("https://www.twitch.tv", headers["Origin"])
    }

    @Test
    fun `anonymous recommendation headers never carry an authorization token`() {
        val headers = TwitchApiHelper.buildRecommendationGQLHeaders(
            clientId = "public-gql-client",
            accessToken = null,
            deviceId = "stable-device",
            clientSessionId = "session-1",
        )

        assertNull(headers[C.HEADER_TOKEN])
    }

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
        assertEquals(C.OKHTTP, developerStringValue(C.NETWORK_LIBRARY, C.CRONET, null, false))
        assertEquals(C.EXOPLAYER, developerStringValue(C.PLAYER, C.MEDIA_PLAYER, null, false))
        assertNull(developerStringValue(C.PLAYER_STREAM_HEADERS, "secret", null, false))
        assertEquals(C.DEFAULT_TOKEN_X_DEVICE_ID, developerStringValue(C.TOKEN_X_DEVICE_ID, "custom", null, false))
        assertEquals(C.DEFAULT_TOKEN_PLAYER_TYPE, developerStringValue(C.TOKEN_PLAYER_TYPE, "custom", null, false))
        assertEquals(C.DEFAULT_TOKEN_SUPPORTED_CODECS, developerStringValue(C.TOKEN_SUPPORTED_CODECS, "custom", null, false))
        assertEquals(false, developerBooleanValue(C.DEBUG_EVENT_SUB_CHAT, true, enabled = false))
        assertEquals(false, developerBooleanValue(C.PROXY_MULTIVARIANT_PLAYLIST, true, enabled = false))
        assertEquals(true, developerBooleanValue(C.TOKEN_RANDOM_DEVICE_ID, false, enabled = false))
    }

    @Test
    fun `enabled developer overrides remain effective`() {
        assertEquals(C.DEFAULT_GQL_CLIENT_ID_WEB, developerStringValue(C.GQL_CLIENT_ID_WEB, "custom", null, enabled = true))
        assertEquals(true, developerBooleanValue(C.DEBUG_EVENT_SUB_CHAT, true, enabled = true))
        assertEquals(false, developerBooleanValue(C.TOKEN_RANDOM_DEVICE_ID, false, enabled = true))
        assertEquals(C.OKHTTP, developerStringValue(C.NETWORK_LIBRARY, C.AUTOMATIC, null, enabled = true))
    }
}
