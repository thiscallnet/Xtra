package com.github.andreyasadchy.xtra.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProxyPolicyTest {

    @Test
    fun sourceConstructionUsesTheSameProxyStateExposedToTheUi() {
        val policy = PlaybackProxyPolicy(mediaPlaylistEnabled = true)

        assertTrue(policy.sourceUsesMediaPlaylistProxy(proxyConfigured = true))
        assertFalse(policy.sourceUsesMediaPlaylistProxy(proxyConfigured = false))
        assertTrue(policy.selectManually(false).automaticFallbackDisabled)
        assertFalse(policy.selectManually(false).sourceUsesMultivariantProxy(true, true))
    }

    @Test
    fun failingAutomaticProxyFallsBackToDirectAndDoesNotReenableItself() {
        val policy = PlaybackProxyPolicy()
            .enableAutomatically()
            .disableAfterFailure()

        assertFalse(policy.mediaPlaylistEnabled)
        assertFalse(policy.canEnableAutomatically(proxyConfigured = true))
        assertFalse(policy.sourceUsesMultivariantProxy(preferenceEnabled = true, proxyConfigured = true))
    }
}
