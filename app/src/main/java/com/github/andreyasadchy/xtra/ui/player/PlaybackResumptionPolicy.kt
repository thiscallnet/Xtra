package com.github.andreyasadchy.xtra.ui.player

/** Persistence is destructive only when Media3 is actually going to play. */
internal fun shouldConsumeResumptionState(
    isForPlay: Boolean,
    mediaItemAvailable: Boolean,
): Boolean = isForPlay && mediaItemAvailable
