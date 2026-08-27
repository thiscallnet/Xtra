package com.github.andreyasadchy.xtra.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchPlaybackEligibilityTest {
    @Test
    fun mediaPlayerPlayingStreamIsEligible() {
        assertTrue(isWatchCreditPlaybackEligible(BasePlaybackService.STREAM, true, false))
    }

    @Test
    fun mediaPlayerBufferingPausedCompletedErroredAndStoppedPlaybackIsNotEligible() {
        assertFalse(isWatchCreditPlaybackEligible(BasePlaybackService.STREAM, true, true))
        assertFalse(isWatchCreditPlaybackEligible(BasePlaybackService.STREAM, false, false))
        assertFalse(isWatchCreditPlaybackEligible(BasePlaybackService.STREAM, false, false))
        assertFalse(isWatchCreditPlaybackEligible(BasePlaybackService.STREAM, false, false))
        assertFalse(isWatchCreditPlaybackEligible(BasePlaybackService.STREAM, false, false))
    }

    @Test
    fun nonLivePlaybackIsNeverEligible() {
        assertFalse(isWatchCreditPlaybackEligible("video", true, false))
        assertFalse(isWatchCreditPlaybackEligible("clip", true, false))
        assertFalse(isWatchCreditPlaybackEligible("offline", true, false))
    }
}
