package com.github.andreyasadchy.xtra.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResumptionPolicyTest {

    @Test
    fun `metadata query does not consume persisted state`() {
        assertFalse(shouldConsumeResumptionState(isForPlay = false, mediaItemAvailable = true))
        assertFalse(shouldConsumeResumptionState(isForPlay = false, mediaItemAvailable = false))
    }

    @Test
    fun `real play consumes only when media item was created`() {
        assertTrue(shouldConsumeResumptionState(isForPlay = true, mediaItemAvailable = true))
        assertFalse(shouldConsumeResumptionState(isForPlay = true, mediaItemAvailable = false))
    }

    @Test
    fun `resumption restores normal playback speed policy`() {
        assertEquals(
            1f,
            resumptionPlaybackSpeed(BasePlaybackService.STREAM, configuredSpeed = 1.75f),
            0f,
        )
        assertEquals(
            1.75f,
            resumptionPlaybackSpeed(BasePlaybackService.VIDEO, configuredSpeed = 1.75f),
            0f,
        )
    }
}
