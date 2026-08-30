package com.github.andreyasadchy.xtra.util

enum class PlaybackBackend(
    val diagnosticName: String,
) {
    MEDIA3("Modern Media3"),
    LEGACY_EXOPLAYER("Legacy ExoPlayer service"),
    ANDROID_MEDIA_PLAYER("Android MediaPlayer"),
}

fun resolvePlaybackBackend(
    playerPreference: String?,
    useLegacyCustomPlaybackService: Boolean,
): PlaybackBackend {
    if (playerPreference == C.MEDIA_PLAYER) {
        return PlaybackBackend.ANDROID_MEDIA_PLAYER
    }

    return if (useLegacyCustomPlaybackService) {
        PlaybackBackend.LEGACY_EXOPLAYER
    } else {
        PlaybackBackend.MEDIA3
    }
}
