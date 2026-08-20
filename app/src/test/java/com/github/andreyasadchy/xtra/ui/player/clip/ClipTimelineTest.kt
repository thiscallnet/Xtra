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
}
