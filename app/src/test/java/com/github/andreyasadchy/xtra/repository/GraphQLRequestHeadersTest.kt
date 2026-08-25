package com.github.andreyasadchy.xtra.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import com.github.andreyasadchy.xtra.util.ProtectedGqlHeadersUnavailable
import com.github.andreyasadchy.xtra.util.TwitchIntegrityUnavailableException

class GraphQLRequestHeadersTest {

    @Test
    fun `transport-managed content type is emitted only by the request builder`() {
        val headers = graphQLTransportHeaders(
            mapOf(
                "Client-Integrity" to "integrity",
                "Content-Type" to "text/plain",
                "Origin" to "https://www.twitch.tv",
            ),
        )

        assertEquals("integrity", headers["Client-Integrity"])
        assertEquals("https://www.twitch.tv", headers["Origin"])
        assertFalse(headers.keys.any { it.equals("Content-Type", ignoreCase = true) })
    }

    @Test(expected = TwitchIntegrityUnavailableException::class)
    fun `unavailable protected headers stop the request before transport`() {
        ensureProtectedHeadersAvailable(
            ProtectedGqlHeadersUnavailable(
                mapOf("Client-Session-Id" to "session"),
            ),
        )
    }
}
