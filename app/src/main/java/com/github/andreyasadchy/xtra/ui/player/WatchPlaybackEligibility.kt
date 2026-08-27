package com.github.andreyasadchy.xtra.ui.player

internal fun isWatchCreditPlaybackEligible(
    type: String?,
    isPlaying: Boolean,
    isBuffering: Boolean,
): Boolean = type == BasePlaybackService.STREAM && isPlaying && !isBuffering
