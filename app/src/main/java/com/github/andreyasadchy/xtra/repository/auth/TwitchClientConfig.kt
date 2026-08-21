package com.github.andreyasadchy.xtra.repository.auth

import com.github.andreyasadchy.xtra.BuildConfig

object TwitchClientConfig {
    /**
     * The public Twitch application ID is supplied by the build, not stored in source.
     * Configure a release with `-PtwitchPublicClientId=<public-client-id>`.
     */
    fun publicClientId(): String? = BuildConfig.TWITCH_PUBLIC_CLIENT_ID
        .trim()
        .takeIf { it.isNotEmpty() }
}
