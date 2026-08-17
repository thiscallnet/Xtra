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

    @Test
    fun recoveryTransitionRebuildsDirectSourcesAndDiscardsCustomProxyUris() {
        val mediaProxy = PlaybackProxyPolicy(mediaPlaylistEnabled = true)
            .prepareForRecovery(customProxyEnabled = false)
        val customProxy = PlaybackProxyPolicy()
            .prepareForRecovery(customProxyEnabled = true)

        assertFalse(mediaProxy.policy.sourceUsesMediaPlaylistProxy(proxyConfigured = true))
        assertTrue(mediaProxy.bypassNetworkProxy)
        assertFalse(mediaProxy.discardCurrentUri)
        assertTrue(customProxy.bypassNetworkProxy)
        assertTrue(customProxy.discardCurrentUri)
    }

    @Test
    fun rebuildingTheSourceAfterPolicyTransitionUsesTheNewRoutingSnapshot() {
        val proxiedSource = PlaybackProxyPolicy(mediaPlaylistEnabled = true)
            .sourceRouting(preferenceEnabled = true, proxyConfigured = true)
        val directSource = PlaybackProxyPolicy(mediaPlaylistEnabled = true)
            .disableAfterCleanPlaylist()
            .sourceRouting(preferenceEnabled = true, proxyConfigured = true)

        assertTrue(proxiedSource.useNetworkProxy)
        assertTrue(proxiedSource.useMultivariantPlaylistProxy)
        assertTrue(proxiedSource.useMediaPlaylistProxy)
        assertFalse(directSource.useNetworkProxy)
        assertFalse(directSource.useMultivariantPlaylistProxy)
        assertFalse(directSource.useMediaPlaylistProxy)
        assertTrue(
            PlaybackProxyPolicy(mediaPlaylistEnabled = true)
                .disableAfterCleanPlaylist()
                .canEnableAutomatically(proxyConfigured = true)
        )
    }
}
