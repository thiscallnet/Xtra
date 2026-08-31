package com.github.andreyasadchy.xtra.ui.player

/** Persistence is destructive only when Media3 is actually going to play. */
internal fun shouldConsumeResumptionState(
    isForPlay: Boolean,
    mediaItemAvailable: Boolean,
): Boolean = isForPlay && mediaItemAvailable

/** Matches the playback parameters applied by the normal START_* paths. */
internal fun resumptionPlaybackSpeed(
    playbackType: String?,
    configuredSpeed: Float,
): Float = if (playbackType == BasePlaybackService.STREAM) 1f else configuredSpeed
