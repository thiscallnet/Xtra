package com.github.andreyasadchy.xtra.util

/**
 * Decides whether live monitoring should stay active. The live offset is a
 * sample consumed by the monitor, not a lifecycle gate: Media3 can report it
 * as unavailable while a live timeline is being refreshed.
 */
internal fun shouldRunAdaptiveLiveSampleTicker(
    isLiveContent: Boolean,
    isDynamicLiveItem: Boolean,
    isPlaying: Boolean,
    systemMediaControlsEnabled: Boolean,
    isLiveRewindActive: Boolean,
    hasCurrentItem: Boolean,
): Boolean = isLiveContent &&
    ((isDynamicLiveItem && (isPlaying || systemMediaControlsEnabled)) ||
        (isLiveRewindActive && hasCurrentItem))
