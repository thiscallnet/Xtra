package com.github.andreyasadchy.xtra.util

import com.github.andreyasadchy.xtra.repository.auth.GeckoGqlIdentity
import com.github.andreyasadchy.xtra.repository.auth.isCurrentGeckoGqlIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GeckoGqlIdentityTest {

    @Test
    fun `protected headers use one captured identity and the live cookie`() {
        val identity = GeckoGqlIdentity(
            authorization = "OAuth captured-token",
            clientId = "captured-client",
            clientIntegrity = "captured-integrity",
            xDeviceId = "captured-device",
            clientSessionId = "captured-session",
            userId = "user-1",
            authTokenFingerprint = GeckoGqlIdentity.fingerprintForAccessToken("captured-token"),
            capturedAt = 1_000L,
            clientVersion = "web-version",
        )

        val headers = TwitchApiHelper.buildGeckoGqlHeaders(
            identity = identity,
            cookieHeader = "auth-token=captured-token; other=value",
        )

        assertEquals("OAuth captured-token", headers[C.HEADER_TOKEN])
        assertEquals("captured-client", headers[C.HEADER_CLIENT_ID])
        assertEquals("captured-integrity", headers["Client-Integrity"])
        assertEquals("captured-device", headers["X-Device-Id"])
        assertEquals("captured-session", headers["Client-Session-Id"])
        assertEquals("web-version", headers["Client-Version"])
        assertEquals("auth-token=captured-token; other=value", headers["Cookie"])
    }

    @Test
    fun `authenticated headers always use the complete captured identity`() {
        val identity = GeckoGqlIdentity(
            authorization = "OAuth captured-token",
            clientId = "captured-client",
            clientIntegrity = "captured-integrity",
            xDeviceId = "captured-device",
            clientSessionId = "captured-session",
            userId = "user-1",
            authTokenFingerprint = GeckoGqlIdentity.fingerprintForAccessToken("captured-token"),
            capturedAt = 1_000L,
        )

        val headers = TwitchApiHelper.buildGeckoGqlHeaders(
            identity = identity,
            cookieHeader = null,
        )

        assertEquals("OAuth captured-token", headers[C.HEADER_TOKEN])
        assertEquals("captured-client", headers[C.HEADER_CLIENT_ID])
        assertEquals("captured-integrity", headers["Client-Integrity"])
        assertEquals("captured-device", headers["X-Device-Id"])
        assertEquals("captured-session", headers["Client-Session-Id"])
        assertFalse(headers.containsKey("Cookie"))
    }

    @Test
    fun `identity rejects a cookie from another authenticated session`() {
        val identity = GeckoGqlIdentity(
            authorization = "OAuth captured-token",
            clientId = "captured-client",
            clientIntegrity = "captured-integrity",
            xDeviceId = "captured-device",
            clientSessionId = null,
            userId = "user-1",
            authTokenFingerprint = GeckoGqlIdentity.fingerprintForAccessToken("captured-token"),
            capturedAt = 1_000L,
        )

        assertTrue(identity.matchesCookieHeader("auth-token=captured-token"))
        assertFalse(identity.matchesCookieHeader("auth-token=current-token"))
        assertFalse(identity.matchesCookieHeader("other=value"))
    }

    @Test
    fun `identity expires by age but server rejection can still force refresh`() {
        val identity = GeckoGqlIdentity(
            authorization = "OAuth token",
            clientId = "client",
            clientIntegrity = "integrity",
            xDeviceId = "device",
            clientSessionId = null,
            userId = "user-1",
            authTokenFingerprint = GeckoGqlIdentity.fingerprintForAccessToken("token"),
            capturedAt = 1_000L,
        )

        assertFalse(identity.isExpired(nowMillis = 1_000L + GeckoGqlIdentity.MAX_AGE_MILLIS - 1))
        assertTrue(identity.isExpired(nowMillis = 1_000L + GeckoGqlIdentity.MAX_AGE_MILLIS))
    }

    @Test
    fun `late failure from old identity cannot invalidate a refreshed identity`() {
        val identityX = identity("x")
        val identityY = identity("y")
        val lock = Any()
        var current: GeckoGqlIdentity? = identityX
        var refreshCount = 0
        val aFinished = CountDownLatch(1)
        val bMayFail = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val requestA = executor.submit {
                synchronized(lock) {
                    check(isCurrentGeckoGqlIdentity(current, identityX))
                    current = null
                }
                synchronized(lock) {
                    current = identityY
                    refreshCount++
                }
                aFinished.countDown()
                bMayFail.countDown()
            }
            val requestB = executor.submit {
                check(aFinished.await(1, TimeUnit.SECONDS))
                bMayFail.await(1, TimeUnit.SECONDS)
                synchronized(lock) {
                    check(!isCurrentGeckoGqlIdentity(current, identityX))
                    // B must retry with Y and leave it installed.
                    check(current == identityY)
                }
            }

            requestA.get(1, TimeUnit.SECONDS)
            requestB.get(1, TimeUnit.SECONDS)
            synchronized(lock) {
                assertEquals(1, refreshCount)
                assertEquals(identityY, current)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun identity(suffix: String) = GeckoGqlIdentity(
        authorization = "OAuth token-$suffix",
        clientId = "client-$suffix",
        clientIntegrity = "integrity-$suffix",
        xDeviceId = "device-$suffix",
        clientSessionId = "session-$suffix",
        userId = "user-1",
        authTokenFingerprint = GeckoGqlIdentity.fingerprintForAccessToken("token-$suffix"),
        capturedAt = 1_000L,
    )
}
