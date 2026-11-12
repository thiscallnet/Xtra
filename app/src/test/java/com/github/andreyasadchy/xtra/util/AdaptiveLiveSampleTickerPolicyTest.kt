package com.github.andreyasadchy.xtra.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLiveSampleTickerPolicyTest {

    @Test
    fun keepsMonitoringDynamicLiveWhileOffsetIsTemporarilyUnavailable() {
        assertTrue(
            shouldRunAdaptiveLiveSampleTicker(
                isLiveContent = true,
                isDynamicLiveItem = true,
                isPlaying = true,
                systemMediaControlsEnabled = false,
                isLiveRewindActive = false,
                hasCurrentItem = true,
            ),
        )
    }

    @Test
    fun doesNotMonitorNonLiveOrStaticPlayback() {
        assertFalse(
            shouldRunAdaptiveLiveSampleTicker(
                isLiveContent = false,
                isDynamicLiveItem = true,
                isPlaying = true,
                systemMediaControlsEnabled = true,
                isLiveRewindActive = false,
                hasCurrentItem = true,
            ),
        )
        assertFalse(
            shouldRunAdaptiveLiveSampleTicker(
                isLiveContent = true,
                isDynamicLiveItem = false,
                isPlaying = true,
                systemMediaControlsEnabled = false,
                isLiveRewindActive = false,
                hasCurrentItem = true,
            ),
        )
    }

    @Test
    fun preservesPausedSystemControlAndLiveRewindMonitoring() {
        assertTrue(
            shouldRunAdaptiveLiveSampleTicker(
                isLiveContent = true,
                isDynamicLiveItem = true,
                isPlaying = false,
                systemMediaControlsEnabled = true,
                isLiveRewindActive = false,
                hasCurrentItem = true,
            ),
        )
        assertTrue(
            shouldRunAdaptiveLiveSampleTicker(
                isLiveContent = true,
                isDynamicLiveItem = false,
                isPlaying = false,
                systemMediaControlsEnabled = false,
                isLiveRewindActive = true,
                hasCurrentItem = true,
            ),
        )
    }
}
