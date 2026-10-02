package com.github.andreyasadchy.xtra.player.hls

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.ui.player.TwitchVaftController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Rare network boundaries exercised on Android with the production playlist parser. */
@RunWith(AndroidJUnit4::class)
@androidx.media3.common.util.UnstableApi
class ControlledVaftPlaylistInstrumentedTest {
    private val uri = Uri.parse("https://fixture.invalid/primary.m3u8")
    private val epoch = 1_704_067_200_000_000L

    private fun box(kind: String, body: ByteArray): ByteArray = java.nio.ByteBuffer.allocate(body.size + 8)
        .putInt(body.size + 8).put(kind.toByteArray(Charsets.US_ASCII)).put(body).array()

    @Test fun fragmentClockNormalizesTimebasesAndRejectsIncompleteMetadata() {
        fun initialization(scale: Int): ByteArray {
            val tkhd = java.nio.ByteBuffer.allocate(20).putInt(0).putLong(0).putInt(1).putInt(0).array()
            val mdhd = java.nio.ByteBuffer.allocate(20).putInt(0).putLong(0).putInt(scale).putInt(0).array()
            val hdlr = java.nio.ByteBuffer.allocate(12).putLong(0).put("vide".toByteArray()).array()
            return box("moov", box("trak", box("tkhd", tkhd) + box("mdia", box("mdhd", mdhd) + box("hdlr", hdlr))))
        }
        fun fragment(ticks: Long): ByteArray = box("moof", box("traf",
            box("tfhd", java.nio.ByteBuffer.allocate(8).putInt(0).putInt(1).array()) +
                box("tfdt", java.nio.ByteBuffer.allocate(12).putInt(0x01000000).putLong(ticks).array())))
        val first = FragmentedMp4Clock.signature(fragment(90_000_000), FragmentedMp4Clock.tracks(initialization(90_000)))
        val second = FragmentedMp4Clock.signature(fragment(1_000_000_000), FragmentedMp4Clock.tracks(initialization(1_000_000)))
        assertEquals("mp4:vide=1000000000", first)
        assertEquals(first, second)
        assertNull(FragmentedMp4Clock.signature(fragment(1).copyOf(18), FragmentedMp4Clock.tracks(initialization(90_000))))
        assertTrue(FragmentedMp4Clock.tracks(initialization(0)).isEmpty())
        assertNull(FragmentedMp4Clock.signature(fragment(-1), FragmentedMp4Clock.tracks(initialization(90_000))))
    }

    private fun parse(text: String, url: Uri = uri) = TwitchHlsPlaylistParserFactory(lowLatencyEnabled = true)
        .createPlaylistParser().parse(url, ByteArrayInputStream(text.toByteArray()))

    private fun media(indices: IntRange, marked: Set<Int> = emptySet(), part: Boolean = false): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-TARGETDURATION:2")
        appendLine("#EXT-X-MEDIA-SEQUENCE:${indices.first}")
        appendLine("#EXT-X-PROGRAM-DATE-TIME:${java.time.Instant.ofEpochMilli((epoch + indices.first * 2_000_000L) / 1000)}")
        indices.forEach { index ->
            appendLine("#EXTINF:2,${if (index in marked) "Amazon" else ""}")
            appendLine("${if (index in marked) "vaft" else "s"}$index.ts")
        }
        if (part) {
            appendLine("#EXT-X-PART-INF:PART-TARGET=0.5")
            appendLine("#EXT-X-PART:DURATION=0.5,URI=\"prefetch.ts\",INDEPENDENT=YES")
        }
    }

    private class Network : DataSource.Factory {
        @Volatile var response = ""
        @Volatile var delayMs = 0L
        val opens = AtomicInteger()
        val responses = ConcurrentHashMap<String, String>()
        val delays = ConcurrentHashMap<String, Long>()
        override fun createDataSource(): DataSource = object : DataSource {
            private var opened: Uri? = null
            private var bytes = byteArrayOf()
            private var offset = 0
            override fun addTransferListener(listener: TransferListener) = Unit
            override fun getUri(): Uri? = opened
            override fun open(spec: DataSpec): Long {
                opens.incrementAndGet()
                Thread.sleep(delays[spec.uri.lastPathSegment] ?: delayMs)
                opened = spec.uri
                bytes = if (spec.uri.toString().endsWith(".m3u8")) (responses[spec.uri.lastPathSegment] ?: response).toByteArray()
                    else ByteArray(65_536) { (spec.uri.lastPathSegment.hashCode() + it).toByte() }
                offset = 0
                return bytes.size.toLong()
            }
            override fun read(buffer: ByteArray, start: Int, length: Int): Int {
                if (offset == bytes.size) return C.RESULT_END_OF_INPUT
                val size = minOf(length, bytes.size - offset)
                bytes.copyInto(buffer, start, offset, offset + size)
                offset += size
                return size
            }
            override fun close() = Unit
        }
    }

    private fun controlled(network: Network, includeRequestedRung: Boolean = false): ControlledVaftPlaylist {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as XtraApp
        val formats = ConcurrentHashMap<String, Format>().apply {
            put(uri.toString(), Format.Builder().setHeight(1080).setWidth(1920).setFrameRate(60f)
                .setCodecs("avc1.640028").setAverageBitrate(6_000_000).build())
        }
        return ControlledVaftPlaylist(app, app.xtraModule, network, "fixture", formats, true).also { controlled ->
            val requestedRung = if (includeRequestedRung) "#EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,FRAME-RATE=60,CODECS=\"avc1.640028\"\nrequested.m3u8\n" else ""
            val master = parse("""
                #EXTM3U
                $requestedRung
                #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720,FRAME-RATE=30,CODECS="avc1.4d401f"
                replacement.m3u8
            """.trimIndent()) as HlsMultivariantPlaylist
            // Avoid token requests: these tests only exercise publication and replacement media.
            val field = ControlledVaftPlaylist::class.java.getDeclaredField("candidateMasters").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val masters = field.get(controlled) as ConcurrentHashMap<String, HlsMultivariantPlaylist>
            TwitchVaftController.PLAYER_TYPES.forEach { masters[it] = master }
        }
    }

    @Test fun markedRequestedRungDoesNotHideCleanLowerRung() {
        val network = Network().apply {
            response = media(0..4)
            responses["requested.m3u8"] = media(0..4, (0..4).toSet())
        }
        val controlled = controlled(network, includeRequestedRung = true)
        try {
            publish(controlled, media(0..2))
            val result = awaitPublication(controlled, media(0..4, setOf(3, 4))) { it.segments.size == 5 }
            assertTrue(result.segments.none { it.url.contains("vaft") })
            assertEquals(720, controlled.formatAt(epoch + 7_000_000)?.height)
        } finally { controlled.close() }
    }

    private fun publish(controlled: ControlledVaftPlaylist, raw: String): HlsMediaPlaylist =
        controlled.transform(uri, parse(raw)) as HlsMediaPlaylist

    private fun awaitPublication(controlled: ControlledVaftPlaylist, raw: String, predicate: (HlsMediaPlaylist) -> Boolean): HlsMediaPlaylist {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val result = try { publish(controlled, raw) } catch (_: ControlledVaftPlaylist.WaitingForVerifiedMediaException) { null }
            if (result != null && predicate(result)) return result
            Thread.sleep(50)
        }
        error("Verified replacement was not published")
    }

    @Test fun shortMiddleWindowResolvesAndUsesCompatibleLowerRung() {
        val network = Network().apply { response = media(0..4) }
        val controlled = controlled(network)
        try {
            publish(controlled, media(0..2))
            val raw = media(0..4, setOf(3))
            val start = SystemClock.elapsedRealtime()
            val prefix = publish(controlled, raw)
            assertTrue("Parser must not wait for discovery", SystemClock.elapsedRealtime() - start < 100)
            assertEquals(3, prefix.segments.size)
            val result = awaitPublication(controlled, raw) { it.segments.size == 5 }
            assertTrue(result.segments.none { it.url.contains("vaft") })
            assertEquals(720, controlled.formatAt(epoch + 7_000_000)?.height)
        } finally { controlled.close() }
    }

    @Test fun parsingAudioDoesNotReplaceActiveVideoFeed() {
        val network = Network().apply { response = media(0..4) }
        val controlled = controlled(network)
        try {
            val master = parse("""
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="audio",URI="audio.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,FRAME-RATE=60,CODECS="avc1.640028,mp4a.40.2",AUDIO="audio"
                primary.m3u8
            """.trimIndent()) as HlsMultivariantPlaylist
            controlled.transform(uri, master)
            publish(controlled, media(0..2))
            controlled.selectFormat(master.variants.single().format.buildUpon().setCodecs("avc1.640028").build())
            awaitPublication(controlled, media(0..4, setOf(3, 4))) { it.segments.size == 5 }
            assertEquals(720, controlled.formatAt(epoch + 7_000_000)?.height)
            val audioUri = master.audios.single().url!!
            controlled.transform(audioUri, parse(media(0..2), audioUri))
            assertEquals(720, controlled.formatAt(epoch + 7_000_000)?.height)
            assertEquals(listOf(720), controlled.availableFormatsAt(epoch + 7_000_000)?.map { it.height })
        } finally { controlled.close() }
    }

    @Test fun alignedPrimaryStillDiscoversShortMiddleWindow() {
        val network = Network().apply { response = media(0..4) }
        val controlled = controlled(network)
        try {
            val initial = media(0..2).replace("2024-01-01T00:00:00Z", "2024-01-01T00:00:01.235Z")
            publish(controlled, initial)
            val result = awaitPublication(controlled, media(0..4, setOf(3))) { it.segments.size == 5 }
            assertEquals(epoch + 1_235_000L, result.startTimeUs)
            assertTrue(result.segments.none { it.url.contains("vaft") })
        } finally { controlled.close() }
    }

    @Test fun expiredWindowRecoversOntoCleanReplacementWhilePrimaryRemainsMarked() {
        val network = Network().apply { response = media(100..104) }
        val controlled = controlled(network)
        try {
            val before = publish(controlled, media(0..2))
            Thread.sleep(8_100)
            val after = awaitPublication(controlled, media(100..104, (100..104).toSet())) {
                it.startTimeUs >= epoch + 200_000_000L
            }
            assertTrue(after.mediaSequence > before.mediaSequence)
            assertEquals(before.discontinuitySequence + 1, after.discontinuitySequence)
            assertTrue(after.segments.none { it.url.contains("vaft") })
            assertTrue(controlled.consumeRecovery(after.startTimeUs))
            assertFalse(controlled.consumeRecovery(after.startTimeUs))
            assertEquals(720, controlled.formatAt(after.startTimeUs)?.height)
        } finally { controlled.close() }
    }

    @Test fun coldMarkedEntryRetriesWithoutBlockingParser() {
        val network = Network().apply { response = media(0..4); delayMs = 1_000 }
        val controlled = controlled(network)
        try {
            val raw = media(0..4, (0..4).toSet())
            val start = SystemClock.elapsedRealtime()
            try { publish(controlled, raw); fail("Cold marked media must wait") }
            catch (_: ControlledVaftPlaylist.WaitingForVerifiedMediaException) { }
            assertTrue(SystemClock.elapsedRealtime() - start < 100)
            val result = awaitPublication(controlled, raw) { it.segments.isNotEmpty() }
            assertTrue(result.segments.none { it.url.contains("vaft") })
        } finally { controlled.close() }
    }

    @Test fun slowKnownReplacementDoesNotBlockAnotherPlayerType() {
        val network = Network().apply { response = media(0..4) }
        val controlled = controlled(network)
        try {
            publish(controlled, media(0..2))
            awaitPublication(controlled, media(0..4, setOf(3, 4))) { it.segments.size == 5 }
            val freshMaster = parse("""
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720,FRAME-RATE=30,CODECS="avc1.4d401f"
                fresh.m3u8
            """.trimIndent()) as HlsMultivariantPlaylist
            val field = ControlledVaftPlaylist::class.java.getDeclaredField("candidateMasters").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val masters = field.get(controlled) as ConcurrentHashMap<String, HlsMultivariantPlaylist>
            TwitchVaftController.PLAYER_TYPES.forEach { masters[it] = freshMaster }
            network.delays["replacement.m3u8"] = 2_500L
            network.responses["fresh.m3u8"] = media(0..6)
            Thread.sleep(550)
            val start = SystemClock.elapsedRealtime()
            val result = awaitPublication(controlled, media(0..6, (3..6).toSet())) { it.segments.size == 7 }
            assertTrue("Other player types must beat the slow cached refresh", SystemClock.elapsedRealtime() - start < 1_500L)
            assertTrue(result.segments.none { it.url.contains("vaft") })
        } finally { controlled.close() }
    }

    @Test fun freshKnownRouteKeepsVerifiedPartsBeforeNextFullSegment() {
        val network = Network().apply { response = media(0..4) }
        val controlled = controlled(network)
        try {
            publish(controlled, media(0..2))
            val primary = media(0..4, setOf(3, 4))
            awaitPublication(controlled, primary) { it.segments.size == 5 }
            network.response = media(0..4, part = true)
            val result = awaitPublication(controlled, primary) { it.trailingParts.isNotEmpty() }
            assertEquals(5, result.segments.size)
            assertEquals(1, result.trailingParts.size)
            assertTrue(result.segments.none { it.url.contains("vaft") })
        } finally { controlled.close() }
    }

    @Test fun fullyMarkedPrimaryDoesNotWaitForUnneededFingerprints() {
        val network = Network().apply {
            response = media(0..4)
            (0..2).forEach { delays["s$it.ts"] = 2_500L }
        }
        val controlled = controlled(network)
        try {
            publish(controlled, media(0..2))
            val start = SystemClock.elapsedRealtime()
            val result = awaitPublication(controlled, media(0..4, (0..4).toSet())) { it.segments.size == 5 }
            assertTrue("No clean inputs means there is nothing to fingerprint", SystemClock.elapsedRealtime() - start < 1_500L)
            assertTrue(result.segments.none { it.url.contains("vaft") })
        } finally { controlled.close() }
    }

    @Test fun rejectedReplacementDropsPrefetchAndReleaseCancelsWork() {
        val network = Network().apply { response = media(0..4, part = true) }
        val controlled = controlled(network)
        try {
            publish(controlled, media(0..2))
            val raw = media(0..4, setOf(3, 4))
            awaitPublication(controlled, raw) { it.segments.size == 5 }
            network.response = media(0..4, setOf(4), part = true)
            Thread.sleep(650)
            publish(controlled, raw)
            Thread.sleep(650)
            val result = publish(controlled, raw)
            assertTrue("Rejected replacement must not expose cached prefetch", result.trailingParts.isEmpty())
            network.delayMs = 3_000
            Thread.sleep(650)
            publish(controlled, raw)
            controlled.close()
            Thread.sleep(200)
            val count = network.opens.get()
            Thread.sleep(800)
            assertEquals("Release must cancel remaining discovery", count, network.opens.get())
        } finally { controlled.close() }
    }

    @Test fun expiredWindowRecoversWithMonotonicSequenceAndExplicitSeek() {
        val controlled = controlled(Network())
        try {
            val before = publish(controlled, media(0..2))
            Thread.sleep(8_100)
            val after = publish(controlled, media(100..104))
            assertTrue(after.mediaSequence > before.mediaSequence)
            assertEquals(before.discontinuitySequence + 1, after.discontinuitySequence)
            assertEquals(epoch + 204_000_000, after.startTimeUs)
            assertFalse(controlled.consumeRecovery(before.startTimeUs))
            assertTrue(controlled.consumeRecovery(after.startTimeUs))
            assertFalse(controlled.consumeRecovery(after.startTimeUs))
        } finally { controlled.close() }
    }
}
