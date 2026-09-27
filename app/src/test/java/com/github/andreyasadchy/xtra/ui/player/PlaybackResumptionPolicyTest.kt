package com.github.andreyasadchy.xtra.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResumptionPolicyTest {

    @Test
    fun `metadata query does not restore service state`() {
        assertFalse(shouldRestoreServiceState(isForPlay = false, mediaItemAvailable = true))
        assertFalse(shouldRestoreServiceState(isForPlay = false, mediaItemAvailable = false))
    }

    @Test
    fun `real play restores only when media item was created`() {
        assertTrue(shouldRestoreServiceState(isForPlay = true, mediaItemAvailable = true))
        assertFalse(shouldRestoreServiceState(isForPlay = true, mediaItemAvailable = false))
    }

    @Test
    fun `resumption restores normal playback speed policy`() {
        assertEquals(
            1f,
            resumptionPlaybackSpeed(PlaybackContract.STREAM, configuredSpeed = 1.75f),
            0f,
        )
        assertEquals(
            1.75f,
            resumptionPlaybackSpeed(PlaybackContract.VIDEO, configuredSpeed = 1.75f),
            0f,
        )
    }
}
