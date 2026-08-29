package com.github.andreyasadchy.xtra.ui.player.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ClipSelectionPlaylistWriterTest {
    @Test
    fun canSelectTheFirstAndLastVodSegments() {
        val directory = Files.createTempDirectory("vod-selection").toFile()
        try {
            val prepared = ClipPreparationRepository.PreparedLiveClip(
                directory = directory,
                playlist = File(directory, "clip.m3u8"),
                segments = (0L until 4L).map { segment(it) },
            )
            val playlist = ClipSelectionPlaylistWriter.write(
                prepared = prepared,
                output = File(directory, "selected.m3u8"),
                startIndex = 0,
                endIndexExclusive = prepared.segments.size,
            )

            assertEquals(4, playlist.readLines().count { it.startsWith("segment_") })
            assertTrue(playlist.readText().contains("segment_0.ts"))
            assertTrue(playlist.readText().contains("segment_3.ts"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsAReversedSelection() {
        val directory = Files.createTempDirectory("vod-selection-invalid").toFile()
        try {
            val prepared = ClipPreparationRepository.PreparedLiveClip(
                directory = directory,
                playlist = File(directory, "clip.m3u8"),
                segments = listOf(segment(0L), segment(1L)),
            )

            assertThrows(IllegalArgumentException::class.java) {
                ClipSelectionPlaylistWriter.write(prepared, File(directory, "selected.m3u8"), 1, 1)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun keepsDiscontinuitiesAcrossLongSelections() {
        val directory = Files.createTempDirectory("vod-selection-discontinuity").toFile()
        try {
            val prepared = ClipPreparationRepository.PreparedLiveClip(
                directory = directory,
                playlist = File(directory, "clip.m3u8"),
                segments = (0L until 180L).map { segment(it, (it / 60).toInt()) },
            )
            val playlist = ClipSelectionPlaylistWriter.write(prepared, File(directory, "selected.m3u8"), 0, 180)

            assertEquals(2, playlist.readLines().count { it == "#EXT-X-DISCONTINUITY" })
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun segment(sequence: Long, discontinuitySequence: Int = 0) = PreparedClipSegment(
        mediaSequence = sequence,
        durationUs = 2_000_000L,
        discontinuitySequence = discontinuitySequence,
        segmentFile = "segment_$sequence.ts",
        initFile = null,
        keyFile = null,
        encryptionIv = null,
    )
}
