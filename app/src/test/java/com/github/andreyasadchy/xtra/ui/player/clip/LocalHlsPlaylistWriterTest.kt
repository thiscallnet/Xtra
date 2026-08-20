package com.github.andreyasadchy.xtra.ui.player.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LocalHlsPlaylistWriterTest {
    @Test
    fun contiguousSegmentsKeepExactDurationsWithoutSyntheticDiscontinuities() {
        val directory = Files.createTempDirectory("local-hls-playlist").toFile()
        try {
            val playlist = LocalHlsPlaylistWriter.write(
                directory,
                listOf(
                    segment(10L, 1_500_500L),
                    segment(11L, 2_001_250L),
                ),
            )
            val text = playlist.readText()

            assertTrue(text.contains("#EXT-X-MEDIA-SEQUENCE:10"))
            assertTrue(text.contains("#EXTINF:1.500500,"))
            assertTrue(text.contains("#EXTINF:2.001250,"))
            assertFalse(text.contains("#EXT-X-DISCONTINUITY"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun discontinuityChangesRemainExplicit() {
        val directory = Files.createTempDirectory("local-hls-discontinuity").toFile()
        try {
            val playlist = LocalHlsPlaylistWriter.write(
                directory,
                listOf(
                    segment(10L, 2_000_000L, discontinuitySequence = 0),
                    segment(11L, 2_000_000L, discontinuitySequence = 1),
                ),
            )

            assertEquals(1, playlist.readLines().count { it == "#EXT-X-DISCONTINUITY" })
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun segment(
        mediaSequence: Long,
        durationUs: Long,
        discontinuitySequence: Int = 0,
    ) = PreparedClipSegment(
        mediaSequence = mediaSequence,
        durationUs = durationUs,
        discontinuitySequence = discontinuitySequence,
        segmentFile = "segment_$mediaSequence.ts",
        initFile = null,
        keyFile = null,
        encryptionIv = null,
    )
}
