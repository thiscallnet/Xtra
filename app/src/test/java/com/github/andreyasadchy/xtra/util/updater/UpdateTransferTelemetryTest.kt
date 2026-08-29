package com.github.andreyasadchy.xtra.util.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTransferTelemetryTest {
    @Test
    fun initialSampleDoesNotInventSpeed() {
        assertEquals(0L, TransferRateEstimator().sample(0L, 1_000L).bytesPerSecond)
    }

    @Test
    fun oneMebiByteInOneSecondProducesUsefulRate() {
        val estimator = TransferRateEstimator()
        estimator.sample(0L, 0L)
        assertEquals(1_048_576L, estimator.sample(1_048_576L, 1_000L).bytesPerSecond)
    }

    @Test
    fun rateChangesAreSmoothed() {
        val estimator = TransferRateEstimator(smoothingFactor = 0.25)
        estimator.sample(0L, 0L)
        assertEquals(1_000L, estimator.sample(1_000L, 1_000L).bytesPerSecond)
        assertEquals(1_250L, estimator.sample(3_000L, 2_000L).bytesPerSecond)
    }

    @Test
    fun noMovementBecomesStalledAndMovementClearsIt() {
        val estimator = TransferRateEstimator(stallAfterMs = 4_000L)
        estimator.sample(100L, 0L)
        estimator.sample(100L, 4_000L).also { assertTrue(it.stalled) }
        estimator.sample(200L, 4_100L).also { assertFalse(it.stalled) }
    }

    @Test
    fun counterResetStartsFresh() {
        val estimator = TransferRateEstimator()
        estimator.sample(1_000L, 0L)
        estimator.sample(2_000L, 1_000L)
        assertEquals(0L, estimator.sample(10L, 2_000L).bytesPerSecond)
    }

    @Test
    fun nonPositiveTimingNeverDividesByZero() {
        val estimator = TransferRateEstimator()
        estimator.sample(0L, 100L)
        assertEquals(0L, estimator.sample(100L, 100L).bytesPerSecond)
        assertTrue(estimator.sample(200L, 0L).bytesPerSecond >= 0L)
    }

    @Test
    fun etaUsesCeilingAndRejectsUnknownOrFinishedTransfers() {
        assertEquals(4L, calculateEtaSeconds(0L, 10L, 3L))
        assertEquals(2L, calculateEtaSeconds(1L, 10L, 5L))
        assertEquals(null, calculateEtaSeconds(1L, null, 5L))
        assertEquals(null, calculateEtaSeconds(10L, 10L, 5L))
        assertEquals(null, calculateEtaSeconds(1L, 10L, 0L))
    }
}
