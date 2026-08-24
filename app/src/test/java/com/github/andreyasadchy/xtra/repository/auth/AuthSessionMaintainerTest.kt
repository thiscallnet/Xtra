package com.github.andreyasadchy.xtra.repository.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSessionMaintainerTest {
    @Test
    fun `only authoritative reauthorization requires user action`() {
        assertTrue(AuthHealth.REAUTH_REQUIRED.requiresUserAction)
        assertFalse(AuthHealth.SIGNED_OUT.requiresUserAction)
        assertFalse(AuthHealth.HEALTHY.requiresUserAction)
        assertFalse(AuthHealth.UNKNOWN.requiresUserAction)
    }

}
