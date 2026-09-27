package com.github.andreyasadchy.xtra.ui.player

/** Service state is restored only when Media3 can actually begin playback. */
internal fun shouldRestoreServiceState(
    isForPlay: Boolean,
    mediaItemAvailable: Boolean,
): Boolean = isForPlay && mediaItemAvailable

/** Matches the playback parameters applied by the normal START_* paths. */
internal fun resumptionPlaybackSpeed(
    playbackType: String?,
    configuredSpeed: Float,
): Float = if (playbackType == PlaybackContract.STREAM) 1f else configuredSpeed
