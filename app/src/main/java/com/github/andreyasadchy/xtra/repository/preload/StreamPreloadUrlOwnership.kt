package com.github.andreyasadchy.xtra.repository.preload

/** Keeps URL ownership explicit: previews peek, fullscreen playback consumes. */
internal class StreamPreloadUrlOwnership(
    private val cache: StreamPreloadUrlCache,
) {
    fun forPreview(channelLogin: String, configurationFingerprint: String): String? =
        cache.get(channelLogin, configurationFingerprint)

    fun forPlayback(channelLogin: String, configurationFingerprint: String): String? =
        cache.take(channelLogin, configurationFingerprint)
}
