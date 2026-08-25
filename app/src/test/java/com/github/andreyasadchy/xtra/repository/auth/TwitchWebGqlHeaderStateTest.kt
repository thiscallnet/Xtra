package com.github.andreyasadchy.xtra.repository.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class TwitchWebGqlHeaderStateTest {

    @Test
    fun `captured integrity state is account bound and expires from jwt`() {
        val now = 1_700_000_000_000L
        val expiry = now + 60_000L
        val token = jwtWithExpiry(expiry / 1_000L)
        val state = TwitchWebGqlHeaderState.capture(
            headers = mapOf(
                TwitchWebGqlHeaderState.AUTHORIZATION to "OAuth access-token",
                TwitchWebGqlHeaderState.CLIENT_INTEGRITY to token,
            ),
            accountId = "account-1",
            capturedAtMillis = now,
        )

        assertNotNull(state)
        assertTrue(state!!.isUsable("account-1", now + 10_000L))
        assertFalse(state.isUsable("account-2", now + 10_000L))
        assertFalse(state.isUsable("account-1", expiry))
    }

    @Test
    fun `opaque integrity state is bounded by capture age`() {
        val now = 1_700_000_000_000L
        val state = TwitchWebGqlHeaderState.capture(
            headers = mapOf(
                TwitchWebGqlHeaderState.AUTHORIZATION to "OAuth access-token",
                TwitchWebGqlHeaderState.CLIENT_INTEGRITY to "opaque-integrity",
            ),
            accountId = "account-1",
            capturedAtMillis = now,
        )!!

        assertTrue(state.isUsable("account-1", now + 4 * 60_000L))
        assertFalse(state.isUsable("account-1", now + 5 * 60_000L + 1L))
    }

    @Test
    fun `a reconstructed session cannot reuse an unbound or different-account capture`() {
        val now = 1_700_000_000_000L
        val state = TwitchWebGqlHeaderState.capture(
            headers = mapOf(
                TwitchWebGqlHeaderState.AUTHORIZATION to "OAuth access-token",
                TwitchWebGqlHeaderState.CLIENT_INTEGRITY to "opaque-integrity",
            ),
            accountId = null,
            capturedAtMillis = now,
        )!!

        assertFalse(state.isUsable("account-1", now + 1_000L))
        assertTrue(state.withAccount("account-1").isUsable("account-1", now + 1_000L))
        assertFalse(state.withAccount("account-1").isUsable("account-2", now + 1_000L))
    }

    private fun jwtWithExpiry(expirySeconds: Long): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"none\"}".toByteArray())
        val payload = encoder.encodeToString("{\"exp\":$expirySeconds}".toByteArray())
        return "$header.$payload.signature"
    }
}
