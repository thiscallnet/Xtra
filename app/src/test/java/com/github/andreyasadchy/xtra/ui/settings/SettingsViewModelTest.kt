package com.github.andreyasadchy.xtra.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.github.andreyasadchy.xtra.repository.auth.AuthHealth
import org.junit.Test

class SettingsViewModelTest {

    @Test
    fun `initial notification baseline uses cached Helix channels only`() {
        assertFalse(initialNotificationBaselineIncludesFollowedStreams())
    }

    @Test
    fun `transient auth health keeps the existing account connected`() {
        assertTrue(isSettingsAccountConnected(AuthHealth.UNKNOWN))
        assertFalse(isSettingsAccountConnected(AuthHealth.SIGNED_OUT))
        assertFalse(isSettingsAccountConnected(AuthHealth.REAUTH_REQUIRED))
    }
}
