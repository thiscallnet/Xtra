package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.model.chat.Prediction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionDismissalPolicyTest {
    @Test
    fun `fresh final waits the full display grace period`() {
        val now = 1_000_000L

        assertEquals(
            PredictionState.RESULT_DISPLAY_GRACE_MILLIS,
            PredictionDismissalPolicy.dismissalDelayMillis(endedAt = now, now = now),
        )
    }

    @Test
    fun `partially aged final waits only the remaining grace period`() {
        val now = 1_000_000L

        assertEquals(
            5_000L,
            PredictionDismissalPolicy.dismissalDelayMillis(
                endedAt = now - (PredictionState.RESULT_DISPLAY_GRACE_MILLIS - 5_000L),
                now = now,
            ),
        )
    }

    @Test
    fun `already expired final dismisses immediately`() {
        val now = 1_000_000L

        assertEquals(
            0L,
            PredictionDismissalPolicy.dismissalDelayMillis(
                endedAt = now - PredictionState.RESULT_DISPLAY_GRACE_MILLIS - 1L,
                now = now,
            ),
        )
    }

    @Test
    fun `final without end timestamp waits the full display grace period`() {
        assertEquals(
            PredictionState.RESULT_DISPLAY_GRACE_MILLIS,
            PredictionDismissalPolicy.dismissalDelayMillis(endedAt = null, now = 1_000_000L),
        )
    }

    @Test
    fun `duration preference parses to millis`() {
        assertEquals(10_000L, PredictionDismissalPolicy.graceMillis("10"))
        assertEquals(20_000L, PredictionDismissalPolicy.graceMillis("20"))
        assertEquals(30_000L, PredictionDismissalPolicy.graceMillis("30"))
        assertEquals(60_000L, PredictionDismissalPolicy.graceMillis("60"))
    }

    @Test
    fun `never duration disables the grace period`() {
        assertEquals(
            PredictionState.RESULT_DISPLAY_NEVER_MILLIS,
            PredictionDismissalPolicy.graceMillis("never"),
        )
        assertEquals(
            PredictionState.RESULT_DISPLAY_NEVER_MILLIS,
            PredictionDismissalPolicy.graceMillis(" Never "),
        )
        assertTrue(
            PredictionDismissalPolicy.isNever(
                PredictionDismissalPolicy.graceMillis("never"),
            ),
        )
        assertFalse(
            PredictionDismissalPolicy.isNever(
                PredictionDismissalPolicy.graceMillis("20"),
            ),
        )
    }

    @Test
    fun `never duration keeps session-start eligibility bounded`() {
        assertEquals(
            PredictionState.RESULT_DISPLAY_GRACE_MILLIS,
            PredictionDismissalPolicy.eligibilityMillis(
                PredictionState.RESULT_DISPLAY_NEVER_MILLIS,
            ),
        )
        assertEquals(
            60_000L,
            PredictionDismissalPolicy.eligibilityMillis(60_000L),
        )
    }

    @Test
    fun `unknown duration falls back to the default grace period`() {
        assertEquals(
            PredictionState.RESULT_DISPLAY_GRACE_MILLIS,
            PredictionDismissalPolicy.graceMillis(null),
        )
        assertEquals(
            PredictionState.RESULT_DISPLAY_GRACE_MILLIS,
            PredictionDismissalPolicy.graceMillis(""),
        )
        assertEquals(
            PredictionState.RESULT_DISPLAY_GRACE_MILLIS,
            PredictionDismissalPolicy.graceMillis("soon"),
        )
        assertEquals(
            PredictionState.RESULT_DISPLAY_GRACE_MILLIS,
            PredictionDismissalPolicy.graceMillis("0"),
        )
        assertEquals(
            PredictionState.RESULT_DISPLAY_GRACE_MILLIS,
            PredictionDismissalPolicy.graceMillis("-5"),
        )
    }

    @Test
    fun `custom grace period anchors the remaining delay`() {
        val now = 1_000_000L

        assertEquals(
            20_000L,
            PredictionDismissalPolicy.dismissalDelayMillis(
                endedAt = now - 40_000L,
                now = now,
                graceMillis = 60_000L,
            ),
        )
        assertEquals(
            0L,
            PredictionDismissalPolicy.dismissalDelayMillis(
                endedAt = now - 15_000L,
                now = now,
                graceMillis = 10_000L,
            ),
        )
    }

    @Test
    fun `never duration never schedules a dismissal`() {
        assertNull(
            PredictionDismissalPolicy.dismissalDelayMillis(
                endedAt = 1_000_000L,
                now = 2_000_000L,
                graceMillis = PredictionState.RESULT_DISPLAY_NEVER_MILLIS,
            ),
        )
        assertNull(
            PredictionDismissalPolicy.dismissalDelayMillis(
                endedAt = null,
                now = 1_000_000L,
                graceMillis = PredictionState.RESULT_DISPLAY_NEVER_MILLIS,
            ),
        )
    }

    @Test
    fun `matching final is dismissed`() {
        assertTrue(
            PredictionDismissalPolicy.shouldDismiss(
                current = prediction("p1", "RESOLVED"),
                predictionId = "p1",
            ),
        )
    }

    @Test
    fun `ongoing prediction is kept`() {
        assertFalse(
            PredictionDismissalPolicy.shouldDismiss(
                current = prediction("p1", "LOCKED"),
                predictionId = "p1",
            ),
        )
        assertFalse(
            PredictionDismissalPolicy.shouldDismiss(
                current = prediction("p1", "ACTIVE"),
                predictionId = "p1",
            ),
        )
    }

    @Test
    fun `new prediction id is kept`() {
        assertFalse(
            PredictionDismissalPolicy.shouldDismiss(
                current = prediction("p2", "RESOLVED"),
                predictionId = "p1",
            ),
        )
    }

    @Test
    fun `missing state or id is kept`() {
        assertFalse(PredictionDismissalPolicy.shouldDismiss(current = null, predictionId = "p1"))
        assertFalse(
            PredictionDismissalPolicy.shouldDismiss(
                current = prediction("p1", "RESOLVED"),
                predictionId = null,
            ),
        )
        assertFalse(
            PredictionDismissalPolicy.shouldDismiss(
                current = prediction("p1", "RESOLVED"),
                predictionId = "",
            ),
        )
    }

    private fun prediction(id: String, status: String) = Prediction(
        id = id,
        createdAt = 1_000L,
        outcomes = emptyList(),
        predictionWindowSeconds = null,
        status = status,
        title = "Title",
        winningOutcomeId = null,
        endedAt = 2_000L,
        observedAt = 2_000L,
    )
}
