package com.github.andreyasadchy.xtra.player.lowlatency

/** Twitch playlist host rules shared by Media3 sources and the HTTP data sources. */
object TwitchHlsRequestRules {
    const val MULTIVARIANT_PLAYLIST_REGEX = "^usher\\.ttvnw\\.net$"
    const val MEDIA_PLAYLIST_REGEX = "^(?:[a-z0-9-]+\\.playlist\\.(?:live-video|ttvnw)\\.net|video-weaver\\.[a-z0-9-]+\\.hls\\.ttvnw\\.net)$"
}
