package com.github.andreyasadchy.xtra.ui.player

/**
 * State for the media-playlist proxy path.
 *
 * The enabled flag describes the media-playlist route that will actually be
 * built. Automatic fallback and network-proxy disablement are kept in the
 * same policy so source construction cannot accidentally revive a failed
 * proxy path.
 */
internal data class PlaybackProxyPolicy(
    val mediaPlaylistEnabled: Boolean = false,
    val automaticFallbackDisabled: Boolean = false,
    val networkProxyDisabled: Boolean = false,
) {

    fun sourceUsesMediaPlaylistProxy(proxyConfigured: Boolean): Boolean =
        mediaPlaylistEnabled && sourceUsesNetworkProxy(proxyConfigured)

    fun sourceUsesMultivariantProxy(preferenceEnabled: Boolean, proxyConfigured: Boolean): Boolean =
        preferenceEnabled && sourceUsesNetworkProxy(proxyConfigured)

    fun sourceUsesNetworkProxy(proxyConfigured: Boolean): Boolean =
        proxyConfigured && !networkProxyDisabled

    fun sourceRouting(preferenceEnabled: Boolean, proxyConfigured: Boolean): PlaybackSourceRouting {
        val useNetworkProxy = sourceUsesNetworkProxy(proxyConfigured)
        return PlaybackSourceRouting(
            useNetworkProxy = useNetworkProxy,
            useMultivariantPlaylistProxy = preferenceEnabled && useNetworkProxy,
            useMediaPlaylistProxy = mediaPlaylistEnabled && useNetworkProxy,
        )
    }

    fun selectManually(enabled: Boolean): PlaybackProxyPolicy = copy(
        mediaPlaylistEnabled = enabled,
        automaticFallbackDisabled = !enabled,
        networkProxyDisabled = !enabled,
    )

    fun canEnableAutomatically(proxyConfigured: Boolean): Boolean =
        proxyConfigured && !automaticFallbackDisabled

    fun enableAutomatically(): PlaybackProxyPolicy = copy(
        mediaPlaylistEnabled = true,
        networkProxyDisabled = false,
    )

    fun disableAfterFailure(): PlaybackProxyPolicy = copy(
        mediaPlaylistEnabled = false,
        automaticFallbackDisabled = true,
        networkProxyDisabled = true,
    )

    fun disableAfterCleanPlaylist(): PlaybackProxyPolicy = copy(
        mediaPlaylistEnabled = false,
        networkProxyDisabled = true,
    )

    fun prepareForRecovery(customProxyEnabled: Boolean): PlaybackProxyRecoveryDecision {
        val bypassNetworkProxy =
            mediaPlaylistEnabled || automaticFallbackDisabled || customProxyEnabled
        val disableProxyRoute = mediaPlaylistEnabled || customProxyEnabled
        return PlaybackProxyRecoveryDecision(
            policy = if (disableProxyRoute) disableAfterFailure() else this,
            bypassNetworkProxy = bypassNetworkProxy,
            discardCurrentUri = customProxyEnabled,
        )
    }
}

internal data class PlaybackProxyRecoveryDecision(
    val policy: PlaybackProxyPolicy,
    val bypassNetworkProxy: Boolean,
    val discardCurrentUri: Boolean,
)

internal data class PlaybackSourceRouting(
    val useNetworkProxy: Boolean,
    val useMultivariantPlaylistProxy: Boolean,
    val useMediaPlaylistProxy: Boolean,
)
