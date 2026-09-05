package com.github.andreyasadchy.xtra.ui.player

import androidx.media3.common.Player

internal fun shouldDisableVideoForBackground(
    backgroundPlaybackEnabled: Boolean,
    isInPictureInPicture: Boolean,
    playWhenReady: Boolean,
    playbackState: Int,
    hasMediaItem: Boolean,
    audioOnly: Boolean,
    chatOnly: Boolean,
    videoAlreadySuppressed: Boolean,
): Boolean {
    return backgroundPlaybackEnabled &&
        !isInPictureInPicture &&
        playWhenReady &&
        playbackState != Player.STATE_IDLE &&
        playbackState != Player.STATE_ENDED &&
        hasMediaItem &&
        !audioOnly &&
        !chatOnly &&
        !videoAlreadySuppressed
}

internal fun shouldRestoreVideoAfterBackground(
    backgroundOwnedVideoDisable: Boolean,
    audioOnly: Boolean,
    chatOnly: Boolean,
    videoAlreadySuppressed: Boolean,
): Boolean {
    return backgroundOwnedVideoDisable &&
        !audioOnly &&
        !chatOnly &&
        !videoAlreadySuppressed
}
