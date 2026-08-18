package com.github.andreyasadchy.xtra.ui.player.clip

import android.content.res.AssetManager
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.transformer.ExportResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the no-reencode export path on the same codecs used by the live clip feature. */
@RunWith(AndroidJUnit4::class)
class ClipExporterInstrumentationTest {
    @Test
    fun mpegTsH264AacIsTransmuxedToMp4() = runBlocking {
        assertTransmuxed("ts")
    }

    @Test
    fun fmp4H264AacIsTransmuxedToMp4() = runBlocking {
        assertTransmuxed("fmp4")
    }

    private suspend fun assertTransmuxed(format: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val fixtureDirectory = File(targetContext.cacheDir, "clip-export-fixture-$format")
        copyAssets(instrumentation.context.assets, "live_clip/$format", fixtureDirectory)
        val output = File(fixtureDirectory, "export.mp4")
        try {
            val isFmp4 = format == "fmp4"
            val sourceSegments = (0 until 4).map { index ->
                ClipSegmentRef(
                    generation = 1L,
                    renditionId = "fixture-$format",
                    mediaSequence = index.toLong(),
                    absoluteUri = File(
                        fixtureDirectory,
                        if (isFmp4) "segment_${index.toString().padStart(3, '0')}.m4s"
                        else "segment_${index.toString().padStart(3, '0')}.ts",
                    ).toUri().toString(),
                    durationUs = 2_000_000L,
                    absoluteStartUs = index * 2_000_000L,
                    relativeStartUs = index * 2_000_000L,
                    discontinuitySequence = 0,
                    byteRangeOffset = 0L,
                    byteRangeLength = C.LENGTH_UNSET.toLong(),
                    initSegment = if (isFmp4) {
                        ClipResourceRef(File(fixtureDirectory, "init.mp4").toUri().toString())
                    } else null,
                    encryptionKeyUri = null,
                    encryptionIv = null,
                    drmInitDataPresent = false,
                    hasGap = false,
                )
            }
            val prepared = ClipPreparationRepository(
                dataSourceFactory = DefaultDataSource.Factory(targetContext),
                rootDirectory = File(fixtureDirectory, "prepared"),
            ).prepare(ClipSnapshot(1L, "fixture-$format", sourceSegments))
            val selected = ClipSelectionPlaylistWriter.write(
                prepared = prepared,
                output = File(prepared.directory, "selected.m3u8"),
                startIndex = 1,
                endIndexExclusive = 4,
            )
            val selectedLines = selected.readLines()
            assertEquals(3, selectedLines.count { it.startsWith("#EXTINF:") })
            assertEquals(
                listOf(
                    "segment_0001.${if (isFmp4) "m4s" else "ts"}",
                    "segment_0002.${if (isFmp4) "m4s" else "ts"}",
                    "segment_0003.${if (isFmp4) "m4s" else "ts"}",
                ),
                selectedLines.filter { it.startsWith("segment_") },
            )
            val result = ClipExporter(targetContext).export(
                selected,
                output,
            ).result
            assertTrue(output.isFile)
            assertTrue(output.length() > 0L)
            assertEquals(ExportResult.CONVERSION_PROCESS_TRANSMUXED, result.videoConversionProcess)
            assertEquals(ExportResult.CONVERSION_PROCESS_TRANSMUXED, result.audioConversionProcess)
            val durationMs = MediaMetadataRetriever().run {
                setDataSource(output.absolutePath)
                val duration = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
                    ?: error("Exported MP4 has no duration")
                release()
                duration
            }
            assertTrue("Unexpected exported duration: $durationMs", durationMs in 5_000L..7_000L)
        } finally {
            fixtureDirectory.deleteRecursively()
        }
    }

    private fun copyAssets(assets: AssetManager, path: String, destination: File) {
        destination.mkdirs()
        assets.list(path).orEmpty().forEach { name ->
            val childPath = "$path/$name"
            val child = File(destination, name)
            if (assets.list(childPath).orEmpty().isEmpty()) {
                assets.open(childPath).use { input -> child.outputStream().use(input::copyTo) }
            } else {
                copyAssets(assets, childPath, child)
            }
        }
    }
}
