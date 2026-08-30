package com.github.andreyasadchy.xtra.ui.player

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
}
