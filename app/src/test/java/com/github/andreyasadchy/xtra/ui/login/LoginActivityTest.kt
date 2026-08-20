package com.github.andreyasadchy.xtra.ui.login

import com.github.andreyasadchy.xtra.model.id.DeviceCodeResponse
import com.github.andreyasadchy.xtra.model.id.TokenResponse
import com.github.andreyasadchy.xtra.repository.auth.AuthSession
import com.github.andreyasadchy.xtra.repository.auth.DeviceAuthorizationPoller
import com.github.andreyasadchy.xtra.repository.auth.REAUTHORIZATION_ACCOUNT_SCOPES
import com.github.andreyasadchy.xtra.repository.auth.TwitchAuthHttpException
import com.github.andreyasadchy.xtra.repository.auth.hasRequiredReauthorizationScopes
import com.github.andreyasadchy.xtra.repository.auth.isReauthorizationUserAllowed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginActivityTest {
    private val deviceAuthorization = DeviceCodeResponse(
        deviceCode = "device-code",
        userCode = "ABCD-EFGH",
        verificationUri = "https://www.twitch.tv/activate?public=true&device-code=ABCD-EFGH",
        expiresIn = 60,
        interval = 2,
    )

    @Test
    fun `device response parses every field and preserves verification uri`() {
        val response = Json.decodeFromString<DeviceCodeResponse>(
            """
            {
              "device_code":"device-code",
              "user_code":"ABCD-EFGH",
              "verification_uri":"https://www.twitch.tv/activate?public=true&device-code=ABCD-EFGH",
              "expires_in":600,
              "interval":5
            }
            """.trimIndent(),
        )

        assertEquals("device-code", response.deviceCode)
        assertEquals("ABCD-EFGH", response.userCode)
        assertEquals(response.verificationUri, selectVerificationUri(response))
        assertEquals(600, response.expiresIn)
        assertEquals(5, response.interval)
    }

    @Test
    fun `complete verification uri takes precedence when Twitch returns both urls`() {
        val response = deviceAuthorization.copy(
            verificationUriComplete = "https://www.twitch.tv/activate?device-code=complete",
        )

        assertEquals(response.verificationUriComplete, selectVerificationUri(response))
    }

    @Test
    fun `poller waits for the returned interval through pending responses`() {
        var now = 0L
        val delays = mutableListOf<Long>()
        val responses = ArrayDeque(
            listOf(
                TokenResponse(error = "authorization_pending"),
                TokenResponse(error = "authorization_pending"),
                TokenResponse(error = "authorization_pending"),
                successfulToken(),
            ),
        )

        val result = runBlocking {
            DeviceAuthorizationPoller(
                requestToken = { _, _ -> responses.removeFirst() },
                delayMillis = { millis -> delays += millis; now += millis },
                nowMillis = { now },
            ).poll(deviceAuthorization, listOf("user:read:follows"))
        }

        assertEquals("new-access-token", result.accessToken)
        assertEquals(listOf(2_000L, 2_000L, 2_000L, 2_000L), delays)
    }

    @Test
    fun `poller accepts Twitch documented pending message response`() {
        var now = 0L
        val responses = ArrayDeque(
            listOf(
                Json.decodeFromString<TokenResponse>(
                    """{"status":400,"message":"authorization_pending"}""",
                ),
                successfulToken(),
            ),
        )

        val result = runBlocking {
            DeviceAuthorizationPoller(
                requestToken = { _, _ -> responses.removeFirst() },
                delayMillis = { millis -> now += millis },
                nowMillis = { now },
            ).poll(deviceAuthorization, listOf("user:read:follows"))
        }

        assertEquals("new-access-token", result.accessToken)
    }

    @Test
    fun `poller stops at expiration without another request`() {
        var now = 0L
        var requests = 0
        val delays = mutableListOf<Long>()
        val expiring = deviceAuthorization.copy(expiresIn = 2, interval = 1)

        val error = runCatching {
            runBlocking {
                DeviceAuthorizationPoller(
                    requestToken = { _, _ ->
                        requests += 1
                        TokenResponse(error = "authorization_pending")
                    },
                    delayMillis = { millis -> delays += millis; now += millis },
                    nowMillis = { now },
                ).poll(expiring, listOf("user:read:follows"))
            }
        }.exceptionOrNull()

        assertTrue(error?.message?.contains("expired", ignoreCase = true) == true)
        assertEquals(1, requests)
        assertEquals(listOf(1_000L, 1_000L), delays)
    }

    @Test
    fun `poller retries temporary network failures with a bound`() {
        var now = 0L
        var attempts = 0
        val delays = mutableListOf<Long>()

        val result = runBlocking {
                DeviceAuthorizationPoller(
                requestToken = { _, _ ->
                    attempts += 1
                    if (attempts < 3) throw IllegalStateException("temporary network error")
                    successfulToken()
                },
                delayMillis = { millis -> delays += millis; now += millis },
                nowMillis = { now },
            ).poll(deviceAuthorization, listOf("user:read:follows"))
        }

        assertEquals("new-access-token", result.accessToken)
        assertEquals(3, attempts)
        assertEquals(listOf(2_000L, 2_000L, 2_000L), delays)
    }

    @Test
    fun `poller retries Twitch server errors`() {
        var now = 0L
        var attempts = 0
        val delays = mutableListOf<Long>()

        val result = runBlocking {
                DeviceAuthorizationPoller(
                requestToken = { _, _ ->
                    attempts += 1
                    if (attempts < 3) throw TwitchAuthHttpException(503)
                    successfulToken()
                },
                delayMillis = { millis -> delays += millis; now += millis },
                nowMillis = { now },
            ).poll(deviceAuthorization, listOf("user:read:follows"))
        }

        assertEquals("new-access-token", result.accessToken)
        assertEquals(3, attempts)
        assertEquals(listOf(2_000L, 2_000L, 2_000L), delays)
    }

    @Test
    fun `poller propagates cancellation instead of retrying`() {
        var attempts = 0
        val error = runCatching {
            runBlocking {
                DeviceAuthorizationPoller(
                    requestToken = { _, _ ->
                        attempts += 1
                        throw CancellationException("cancelled")
                    },
                    delayMillis = {},
                    nowMillis = { 2_000L },
                ).poll(deviceAuthorization, listOf("user:read:follows"))
            }
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals(1, attempts)
    }

    @Test
    fun `slow down increases the next polling interval`() {
        var now = 0L
        val delays = mutableListOf<Long>()
        val responses = ArrayDeque(
            listOf(
                TokenResponse(error = "slow_down"),
                successfulToken(),
            ),
        )

        runBlocking {
            DeviceAuthorizationPoller(
                requestToken = { _, _ -> responses.removeFirst() },
                delayMillis = { millis -> delays += millis; now += millis },
                nowMillis = { now },
            ).poll(deviceAuthorization, listOf("user:read:follows"))
        }

        assertEquals(listOf(2_000L, 7_000L), delays)
    }

    @Test
    fun `reauthorization requires the same account and account scopes`() {
        assertTrue(isReauthorizationUserAllowed(true, "1", "1"))
        assertFalse(isReauthorizationUserAllowed(true, "1", "2"))
        assertFalse(isReauthorizationUserAllowed(true, null, "1"))
        assertTrue(hasRequiredReauthorizationScopes(REAUTHORIZATION_ACCOUNT_SCOPES))
        assertFalse(hasRequiredReauthorizationScopes(REAUTHORIZATION_ACCOUNT_SCOPES - "user:edit"))
    }

    @Test
    fun `session expiry uses a safety window`() {
        val session = AuthSession(
            clientId = "client",
            accessToken = "access",
            refreshToken = "refresh",
            expiresAtMillis = 100_000,
            userId = "1",
            login = "viewer",
            scopes = emptySet(),
        )

        assertFalse(session.isAccessTokenExpired(nowMillis = 38_000))
        assertTrue(session.isAccessTokenExpired(nowMillis = 40_000))
    }

    private fun successfulToken() = TokenResponse(
        accessToken = "new-access-token",
        refreshToken = "new-refresh-token",
        expiresIn = 14_400,
        scopes = listOf("user:read:follows"),
        tokenType = "bearer",
    )
}
