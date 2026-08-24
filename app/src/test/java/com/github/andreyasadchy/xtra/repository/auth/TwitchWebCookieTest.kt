package com.github.andreyasadchy.xtra.repository.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class TwitchWebCookieTest {
    @Test
    fun `cookie header only includes cookies applicable to the requested host and path`() {
        val cookies = listOf(
            TwitchWebCookie("auth-token", "web", ".twitch.tv", "/", true, false, null),
            TwitchWebCookie("gql-only", "yes", "gql.twitch.tv", "/gql", true, true, null),
            TwitchWebCookie("other-path", "no", ".twitch.tv", "/other", true, false, null),
            TwitchWebCookie("www-only", "no", "www.twitch.tv", "/", true, true, null),
        )

        assertEquals(
            "gql-only=yes; auth-token=web",
            TwitchWebCookiePolicy.headerFor("https://gql.twitch.tv/gql", cookies),
        )
        assertEquals("www-only=no; auth-token=web", TwitchWebCookiePolicy.headerFor("https://www.twitch.tv/", cookies))
    }

    @Test
    fun `secure expired and shorter path cookies are excluded or ordered correctly`() {
        val cookies = listOf(
            TwitchWebCookie("session", "root", ".twitch.tv", "/", true, false, null),
            TwitchWebCookie("session", "nested", ".twitch.tv", "/gql", true, false, null),
            TwitchWebCookie("expired", "gone", ".twitch.tv", "/", true, false, 10),
            TwitchWebCookie("insecure", "plain", ".twitch.tv", "/", false, false, null),
        )

        assertEquals(
            "session=nested; session=root; expired=gone; insecure=plain",
            TwitchWebCookiePolicy.headerFor("https://gql.twitch.tv/gql", cookies, nowMillis = 0),
        )
        assertEquals(
            "session=nested; session=root; insecure=plain",
            TwitchWebCookiePolicy.headerFor("https://gql.twitch.tv/gql", cookies, nowMillis = 11),
        )
    }
}
