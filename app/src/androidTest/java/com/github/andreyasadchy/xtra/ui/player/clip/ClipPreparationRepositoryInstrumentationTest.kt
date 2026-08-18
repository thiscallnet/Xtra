package com.github.andreyasadchy.xtra.ui.player.clip

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class ClipPreparationRepositoryInstrumentationTest {
    @Test
    fun downloadsEveryPreparedResourceThroughTheProvidedFactoryAndByteRanges() = runBlocking {
        val root = Files.createTempDirectory("clip-preparation-test").toFile()
        val resources = mapOf(
            "memory://init" to "INIT".toByteArray(),
            "memory://segment-a" to "012345".toByteArray(),
            "memory://segment-b" to "abcdef".toByteArray(),
        )
        val requests = mutableListOf<DataSpec>()
        val factory = object : DataSource.Factory {
            override fun createDataSource(): DataSource = object : DataSource {
                private var bytes = ByteArray(0)
                private var position = 0
                private var uri = resources.keys.first().let(android.net.Uri::parse)

                override fun addTransferListener(transferListener: TransferListener) = Unit

                override fun open(dataSpec: DataSpec): Long {
                    requests += dataSpec
                    uri = dataSpec.uri
                    val source = resources[dataSpec.uri.toString()] ?: error("Unknown resource")
                    val start = dataSpec.position.toInt()
                    val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                        source.size
                    } else {
                        start + dataSpec.length.toInt()
                    }
                    bytes = source.copyOfRange(start, end)
                    position = 0
                    return bytes.size.toLong()
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (position == bytes.size) return C.RESULT_END_OF_INPUT
                    val count = minOf(length, bytes.size - position)
                    bytes.copyInto(buffer, offset, position, position + count)
                    position += count
                    return count
                }

                override fun getUri() = uri

                override fun close() = Unit
            }
        }
        try {
            val segments = listOf(
                ClipSegmentRef(
                    generation = 1L,
                    renditionId = "memory",
                    mediaSequence = 1L,
                    absoluteUri = "memory://segment-a",
                    durationUs = 2_000_000L,
                    absoluteStartUs = 0L,
                    relativeStartUs = 0L,
                    discontinuitySequence = 0,
                    byteRangeOffset = 1L,
                    byteRangeLength = 3L,
                    initSegment = ClipResourceRef("memory://init"),
                    encryptionKeyUri = null,
                    encryptionIv = null,
                    drmInitDataPresent = false,
                    hasGap = false,
                ),
                ClipSegmentRef(
                    generation = 1L,
                    renditionId = "memory",
                    mediaSequence = 2L,
                    absoluteUri = "memory://segment-b",
                    durationUs = 2_000_000L,
                    absoluteStartUs = 2_000_000L,
                    relativeStartUs = 2_000_000L,
                    discontinuitySequence = 0,
                    byteRangeOffset = 2L,
                    byteRangeLength = C.LENGTH_UNSET.toLong(),
                    initSegment = ClipResourceRef("memory://init"),
                    encryptionKeyUri = null,
                    encryptionIv = null,
                    drmInitDataPresent = false,
                    hasGap = false,
                ),
            )
            val prepared = ClipPreparationRepository(factory, root).prepare(
                ClipSnapshot(1L, "memory", segments),
            )

            assertEquals(3, requests.size)
            assertEquals(1L, requests[1].position)
            assertEquals(3L, requests[1].length)
            assertEquals(2L, requests[2].position)
            assertEquals(C.LENGTH_UNSET.toLong(), requests[2].length)
            assertArrayEquals("123".toByteArray(), File(prepared.directory, "segment_0000.bin").readBytes())
            assertArrayEquals("cdef".toByteArray(), File(prepared.directory, "segment_0001.bin").readBytes())
            assertTrue(File(prepared.directory, "init_000.bin").isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
