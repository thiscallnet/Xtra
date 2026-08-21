package com.github.andreyasadchy.xtra.repository.auth

import com.github.andreyasadchy.xtra.BuildConfig

object TwitchClientConfig {
    /**
     * Debug builds use Xtra's built-in public client ID. Configure a release with
     * `-PtwitchPublicClientId=<public-client-id>`.
     */
    fun publicClientId(): String? = BuildConfig.TWITCH_PUBLIC_CLIENT_ID
        .trim()
        .takeIf { it.isNotEmpty() }
}
