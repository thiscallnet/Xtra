package com.github.andreyasadchy.xtra.repository.preload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamPreviewDwellPolicyTest {
    @Test
    fun scrollingDoesNotConsumeConfiguredDwell() {
        assertNull(StreamPreviewDwellPolicy.startAt(null, nowMs = 0L, isScrolling = true))

        val startedAt = StreamPreviewDwellPolicy.startAt(null, nowMs = 1_500L, isScrolling = false)

        assertEquals(1_500L, startedAt)
        assertEquals(
            1_250L,
            StreamPreviewDwellPolicy.remainingDelay(startedAt!!, nowMs = 1_500L, delayMs = 1_250L),
        )
    }

    @Test
    fun immediatePreferenceAllowsAZeroMillisecondStart() {
        assertEquals(StreamPreviewDelay.IMMEDIATE, StreamPreviewDelay.fromPreference("instant"))
        assertEquals(
            0L,
            StreamPreviewDwellPolicy.remainingDelay(startedAtMs = 1_500L, nowMs = 1_500L, delayMs = 0L),
        )
    }
}
