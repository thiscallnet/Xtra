package com.github.andreyasadchy.xtra.ui.player.clip

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.AbstractList

class ClipSizeEstimatorTest {
    @Test
    fun sixtySecondsAtSixMbpsIsAbout45MB() {
        val estimated = ClipSizeEstimator.estimateBytes(
            selectedDurationUs = 60_000_000L,
            segments = listOf(segment(60_000_000L)),
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
            segments = listOf(
                segment(2_000_000L, byteRangeLength = 12_345L),
                segment(2_000_000L, byteRangeLength = 6_789L),
            ),
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
                segments = listOf(segment(1_000_000L)),
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
            segments = listOf(
                segment(20_000_000L, byteRangeLength = 100L),
                segment(20_000_000L, byteRangeLength = 200L),
                segment(20_000_000L, byteRangeLength = 300L),
                segment(20_000_000L, byteRangeLength = 400L),
            ),
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
                segments = listOf(segment(1_000_000L)),
                startIndex = 0,
                endIndexExclusive = 2,
                bitrateBitsPerSecond = 6_000_000,
            ),
        )
    }

    @Test
    fun estimatesWithoutCreatingASelectedRangeList() {
        val source = listOf(
            segment(1_000_000L, byteRangeLength = 100L),
            segment(1_000_000L, byteRangeLength = 200L),
            segment(1_000_000L, byteRangeLength = 300L),
        )
        val segments = object : AbstractList<ClipSegmentRef>() {
            override val size = source.size

            override fun get(index: Int): ClipSegmentRef = source[index]

            override fun subList(fromIndex: Int, toIndex: Int): MutableList<ClipSegmentRef> =
                error("range copies are not allowed during estimation")
        }

        assertEquals(
            500L,
            ClipSizeEstimator.estimateBytes(
                selectedDurationUs = 2_000_000L,
                segments = segments,
                startIndex = 1,
                endIndexExclusive = 3,
                bitrateBitsPerSecond = null,
            ),
        )
    }

    private fun segment(durationUs: Long, byteRangeLength: Long = C.LENGTH_UNSET.toLong()) = ClipSegmentRef(
        generation = 0L,
        renditionId = "vod",
        mediaSequence = 0L,
        absoluteUri = "https://example.invalid/segment.ts",
        durationUs = durationUs,
        absoluteStartUs = 0L,
        relativeStartUs = 0L,
        discontinuitySequence = 0,
        byteRangeOffset = 0L,
        byteRangeLength = byteRangeLength,
        initSegment = null,
        encryptionKeyUri = null,
        encryptionIv = null,
        drmInitDataPresent = false,
        hasGap = false,
    )
}
