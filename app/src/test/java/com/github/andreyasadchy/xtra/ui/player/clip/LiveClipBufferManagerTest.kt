package com.github.andreyasadchy.xtra.ui.player.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveClipBufferManagerTest {
    @Test
    fun resetAdvancesGenerationSoOldSnapshotsCannotBeReused() {
        val manager = LiveClipBufferManager()
        val firstGeneration = manager.startNewGeneration()

        manager.reset()

        assertEquals(firstGeneration + 1L, manager.currentGeneration())
        assertNull(manager.snapshot())
    }

    @Test
    fun aNewSourceGenerationStartsWithNoContiguousHistory() {
        val manager = LiveClipBufferManager()
        manager.startNewGeneration()

        manager.startNewGeneration()

        assertNull(manager.snapshot())
        assertEquals(2L, manager.currentGeneration())
    }

    @Test
    fun snapshotIncludesTheNewestSegmentAndWalksContiguousSequences() {
        val selected = LiveClipBufferManager.selectContiguousLatest(
            candidates = listOf(100L, 101L, 102L, 103L).map(::segment),
            maxDurationUs = 30_000_000L,
        )

        assertEquals(listOf(100L, 101L, 102L, 103L), selected?.map { it.mediaSequence })
    }

    @Test
    fun snapshotStopsAtASequenceGap() {
        val selected = LiveClipBufferManager.selectContiguousLatest(
            candidates = listOf(segment(100), segment(101), segment(103)),
            maxDurationUs = 30_000_000L,
        )

        assertEquals(listOf(103L), selected?.map { it.mediaSequence })
    }

    @Test
    fun statusIgnoresDrmOutsideTheReadyClipTail() {
        val segments = (0L until 16L).map { sequence ->
            segment(sequence, drmInitDataPresent = sequence == 0L)
        }

        val status = LiveClipBufferManager().statusFor(segments)

        assertEquals(30_000_000L, status.durationUs)
        assertEquals(15, status.segmentCount)
        assertEquals(false, status.drmProtected)
        assertEquals(true, status.available)
    }

    @Test
    fun snapshotStopsAtAGapSegmentAndDiscontinuity() {
        val gapSelected = LiveClipBufferManager.selectContiguousLatest(
            candidates = listOf(segment(100), segment(101, hasGap = true), segment(102)),
            maxDurationUs = 30_000_000L,
        )
        val discontinuitySelected = LiveClipBufferManager.selectContiguousLatest(
            candidates = listOf(segment(100), segment(101, discontinuitySequence = 1)),
            maxDurationUs = 30_000_000L,
        )

        assertEquals(listOf(102L), gapSelected?.map { it.mediaSequence })
        assertEquals(listOf(101L), discontinuitySelected?.map { it.mediaSequence })
        assertNull(LiveClipBufferManager.selectContiguousLatest(emptyList(), 30_000_000L))
    }

    private fun segment(
        mediaSequence: Long,
        discontinuitySequence: Int = 0,
        hasGap: Boolean = false,
        drmInitDataPresent: Boolean = false,
    ) = ClipSegmentRef(
        generation = 1L,
        renditionId = "fixture",
        mediaSequence = mediaSequence,
        absoluteUri = "https://example.invalid/$mediaSequence.ts",
        durationUs = 2_000_000L,
        absoluteStartUs = mediaSequence * 2_000_000L,
        relativeStartUs = mediaSequence * 2_000_000L,
        discontinuitySequence = discontinuitySequence,
        byteRangeOffset = 0L,
        byteRangeLength = -1L,
        initSegment = null,
        encryptionKeyUri = null,
        encryptionIv = null,
        drmInitDataPresent = drmInitDataPresent,
        hasGap = hasGap,
    )
}
