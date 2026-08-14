package com.github.andreyasadchy.xtra.ui.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginActivityTest {

    @Test
    fun `login API values are bounded and invalid values use Both`() {
        assertEquals(0, parseLoginApi(null))
        assertEquals(0, parseLoginApi("0"))
        assertEquals(1, parseLoginApi("1"))
        assertEquals(2, parseLoginApi("2"))
        assertEquals(0, parseLoginApi("not-a-number"))
        assertEquals(0, parseLoginApi("-1"))
        assertEquals(2, parseLoginApi("3"))
    }

    @Test
    fun `reauthorization always uses Helix while normal login preserves the selected mode`() {
        assertEquals(HELIX_ONLY_LOGIN_API, resolveLoginApi("0", reauthorize = true))
        assertEquals(HELIX_ONLY_LOGIN_API, resolveLoginApi("1", reauthorize = true))
        assertEquals(HELIX_ONLY_LOGIN_API, resolveLoginApi("2", reauthorize = true))
        assertEquals(0, resolveLoginApi("0", reauthorize = false))
        assertEquals(1, resolveLoginApi("1", reauthorize = false))
        assertEquals(2, resolveLoginApi("2", reauthorize = false))
    }

    @Test
    fun `reauthorization preserves an existing session while normal login clears it`() {
        assertFalse(shouldClearExistingSession(hasExistingSession = false, reauthorize = false))
        assertTrue(shouldClearExistingSession(hasExistingSession = true, reauthorize = false))
        assertFalse(shouldClearExistingSession(hasExistingSession = true, reauthorize = true))
    }

    @Test
    fun `reauthorization only accepts the existing Twitch user`() {
        assertTrue(isReauthorizationUserAllowed(reauthorize = true, previousUserId = "1", newUserId = "1"))
        assertFalse(isReauthorizationUserAllowed(reauthorize = true, previousUserId = "1", newUserId = "2"))
        assertTrue(isReauthorizationUserAllowed(reauthorize = false, previousUserId = "1", newUserId = "2"))
        assertFalse(isReauthorizationUserAllowed(reauthorize = true, previousUserId = null, newUserId = "2"))
    }

    @Test
    fun `reauthorization requires a new valid Helix token even when GQL is valid`() {
        assertFalse(
            canCompleteReauthorization(
                reauthorize = true,
                helixToken = null,
                helixScopes = REAUTHORIZATION_ACCOUNT_SCOPES,
                helixValidationFailed = false,
            ),
        )
        assertFalse(
            canCompleteReauthorization(
                reauthorize = true,
                helixToken = "old-helix-token",
                helixScopes = REAUTHORIZATION_ACCOUNT_SCOPES,
                helixValidationFailed = true,
                identityMismatch = true,
            ),
        )
        assertTrue(
            canCompleteReauthorization(
                reauthorize = true,
                helixToken = "new-helix-token",
                helixScopes = REAUTHORIZATION_ACCOUNT_SCOPES,
                helixValidationFailed = false,
            ),
        )
        assertTrue(
            canCompleteReauthorization(
                reauthorize = false,
                helixToken = null,
                helixScopes = emptySet(),
                helixValidationFailed = true,
            ),
        )
    }

    @Test
    fun `reauthorization requires all account scopes`() {
        assertTrue(hasRequiredReauthorizationScopes(REAUTHORIZATION_ACCOUNT_SCOPES))
        assertFalse(hasRequiredReauthorizationScopes(REAUTHORIZATION_ACCOUNT_SCOPES - "user:edit"))
    }
}
