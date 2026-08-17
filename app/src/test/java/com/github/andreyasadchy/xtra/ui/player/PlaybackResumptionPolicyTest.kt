package com.github.andreyasadchy.xtra.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResumptionPolicyTest {

    @Test
    fun onlyPlaybackResumptionForAStreamNeedsFreshNetworkResolution() {
        assertTrue(shouldResolveFreshStreamForResumption(BasePlaybackService.STREAM, isForPlayback = true))
        assertFalse(shouldResolveFreshStreamForResumption(BasePlaybackService.STREAM, isForPlayback = false))
        assertFalse(shouldResolveFreshStreamForResumption(BasePlaybackService.VIDEO, isForPlayback = true))
    }
}
