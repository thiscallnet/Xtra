package com.github.andreyasadchy.xtra.ui.player.clip

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipSizeEstimatorTest {
    @Test
    fun sixtySecondsAtSixMbpsIsAbout45MB() {
        val estimated = ClipSizeEstimator.estimateBytes(
            selectedDurationUs = 60_000_000L,
            byteRangeLengths = longArrayOf(C.LENGTH_UNSET.toLong()),
            startIndex = 0,
            endIndexExclusive = 1,
            bitrateBitsPerSecond = 6_000_000,
        )

        assertEquals(45_000_000L, estimated)
    }

    @Test
    fun usesByteRangesWhenEverySelectedSegmentHasOne() {
        val estimated = ClipSizeEstimator.estimateBytes(
            selectedDurationUs = 4_000_000L,
            byteRangeLengths = longArrayOf(12_345L, 6_789L),
            startIndex = 0,
            endIndexExclusive = 2,
            bitrateBitsPerSecond = null,
        )

        assertEquals(19_134L, estimated)
    }

    @Test
    fun rejectsAnEmptyOrReversedSelection() {
        assertNull(
            ClipSizeEstimator.estimateBytes(
                selectedDurationUs = 1_000_000L,
                byteRangeLengths = longArrayOf(C.LENGTH_UNSET.toLong()),
                startIndex = 1,
                endIndexExclusive = 1,
                bitrateBitsPerSecond = 6_000_000,
            ),
        )
    }

    @Test
    fun estimatesOnlyTheSelectedByteRanges() {
        val estimated = ClipSizeEstimator.estimateBytes(
            selectedDurationUs = 60_000_000L,
            byteRangeLengths = longArrayOf(100L, 200L, 300L, 400L),
            startIndex = 1,
            endIndexExclusive = 3,
            bitrateBitsPerSecond = null,
        )

        assertEquals(500L, estimated)
    }

    @Test
    fun rejectsAnOutOfBoundsSelection() {
        assertNull(
            ClipSizeEstimator.estimateBytes(
                selectedDurationUs = 1_000_000L,
                byteRangeLengths = longArrayOf(100L),
                startIndex = 0,
                endIndexExclusive = 2,
                bitrateBitsPerSecond = 6_000_000,
            ),
        )
    }

}
