package com.github.andreyasadchy.xtra.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResumptionPolicyTest {

    @Test
    fun onlyPlaybackResumptionForAStreamNeedsFreshNetworkResolution() {
        assertTrue(shouldResolveFreshStreamForResumption(BasePlaybackService.STREAM, playWhenReady = true))
        assertFalse(shouldResolveFreshStreamForResumption(BasePlaybackService.STREAM, playWhenReady = false))
        assertFalse(shouldResolveFreshStreamForResumption(BasePlaybackService.VIDEO, playWhenReady = true))
    }
}
