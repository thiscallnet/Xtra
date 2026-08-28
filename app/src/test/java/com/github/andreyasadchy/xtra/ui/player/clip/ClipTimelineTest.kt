package com.github.andreyasadchy.xtra.ui.player.clip

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipTimelineTest {
    @Test
    fun preservesExactFinalBoundaryForUnevenSegmentDurations() {
        val durationsUs = longArrayOf(1_500_500L, 2_001_250L, 1_998_750L)
        val boundariesUs = LongArray(durationsUs.size + 1).also { boundaries ->
            durationsUs.foldIndexed(0L) { index, positionUs, durationUs ->
                boundaries[index] = positionUs
                positionUs + durationUs
            }.also { boundaries[durationsUs.size] = it }
        }

        val normalized = ClipTimeline.normalizeBoundaries(boundariesUs)
        val startIndex = ClipTimeline.boundaryIndex(0f, normalized)
        val endIndex = ClipTimeline.boundaryIndex(normalized.last() / 1_000f, normalized)

        assertEquals(5_500_500L, normalized.last())
        assertEquals(0, startIndex)
        assertEquals(3, endIndex)
        assertEquals(0 until durationsUs.size, startIndex until endIndex)
    }

    @Test
    fun binarySearchKeepsNearestBoundaryAndLowerIndexOnATie() {
        val boundaries = longArrayOf(0L, 10_000_000L, 20_000_000L)

        assertEquals(0, ClipTimeline.boundaryIndexUs(-1L, boundaries))
        assertEquals(1, ClipTimeline.boundaryIndexUs(15_000_000L, boundaries))
        assertEquals(2, ClipTimeline.boundaryIndexUs(99_000_000L, boundaries))
        assertEquals(2, ClipTimeline.boundaryIndexUs(20_000_000L, boundaries))
    }

    @Test
    fun wholeVodsDoNotApplyTheLiveDurationLimit() {
        val boundaries = LongArray(361) { it * 60_000_000L }

        val start = ClipTimeline.boundaryIndexUs(60 * 60_000_000L, boundaries)
        val end = ClipTimeline.boundaryIndexUs(4 * 60 * 60_000_000L, boundaries)

        assertEquals(60, start)
        assertEquals(240, end)
        assertEquals(180, end - start)
    }
}
