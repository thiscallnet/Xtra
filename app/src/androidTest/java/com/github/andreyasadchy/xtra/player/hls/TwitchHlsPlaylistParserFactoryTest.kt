package com.github.andreyasadchy.xtra.player.hls

import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import com.github.andreyasadchy.xtra.ui.player.PlaybackVideoDiagnosticsStore
import com.github.andreyasadchy.xtra.util.m3u8.TwitchAdDetector
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchHlsPlaylistParserFactoryTest {

    @Test
    fun normalHlsKeepsTheServerTargetDurationAndDoesNotCreateParts() {
        val parser = TwitchHlsPlaylistParserFactory(lowLatencyEnabled = false)
            .createPlaylistParser()
        val playlist = parser.parse(
            Uri.parse("https://video.example.test/live/stream.m3u8"),
            ByteArrayInputStream(
                """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-MEDIA-SEQUENCE:100
                #EXTINF:2.0,
                segment-100.ts
                #EXT-X-TWITCH-PREFETCH:segment-101.ts
                """.trimIndent().toByteArray(StandardCharsets.UTF_8),
            ),
        ) as HlsMediaPlaylist

        assertEquals(6_000_000L, playlist.targetDurationUs)
        assertTrue(playlist.segments.any { it.url == "segment-100.ts" })
        assertTrue(playlist.trailingParts.isEmpty())
    }

    @Test
    fun translatedFmp4PrefetchSegmentsAreParsedWithTheActiveMap() {
        var diagnostics: TwitchHlsPlaylistDiagnostics? = null
        val parser = TwitchHlsPlaylistParserFactory(
            lowLatencyEnabled = true,
            diagnostics = TwitchHlsDiagnosticsSink { parsedDiagnostics, _ ->
                diagnostics = parsedDiagnostics
            },
        )
            .createPlaylistParser()
        val playlist = parser.parse(
            Uri.parse("https://video.example.test/live/stream.m3u8"),
            ByteArrayInputStream(
                """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-TARGETDURATION:6
                #EXT-X-MEDIA-SEQUENCE:100
                #EXT-X-MAP:URI="init.mp4"
                #EXTINF:2.0,
                segment-100.m4s
                #EXT-X-TWITCH-PREFETCH:segment-101.m4s
                #EXT-X-TWITCH-PREFETCH:segment-102.m4s
                """.trimIndent().toByteArray(StandardCharsets.UTF_8),
            ),
        ) as HlsMediaPlaylist

        assertEquals(2_000_000L, playlist.targetDurationUs)
        assertEquals(6_000L, diagnostics?.declaredTargetDurationMs)
        assertEquals(2_000L, diagnostics?.effectiveReloadTargetDurationMs)
        assertTrue(playlist.trailingParts.isEmpty())
        assertEquals(
            listOf("segment-100.m4s", "segment-101.m4s", "segment-102.m4s"),
            playlist.segments.map { it.url },
        )
        assertEquals(2_000_000L, playlist.segments[1].durationUs)
        assertNotNull(playlist.segments[1].initializationSegment)
        assertEquals("init.mp4", playlist.segments[1].initializationSegment?.url)
        assertTrue(playlist.segments.all { it.initializationSegment?.url == "init.mp4" })
    }

    @Test
    fun prefetchSnapshotsRemainDistinctMediaSequences() {
        val parser = TwitchHlsPlaylistParserFactory(lowLatencyEnabled = true)
            .createPlaylistParser()
        val first = parser.parse(
            Uri.parse("https://video.example.test/live/stream.m3u8"),
            playlistBytes(
                """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-MEDIA-SEQUENCE:100
                #EXTINF:2.0,
                segment-100.ts
                #EXT-X-TWITCH-PREFETCH:segment-101.ts
                #EXT-X-TWITCH-PREFETCH:segment-102.ts
                """,
            ),
        ) as HlsMediaPlaylist
        val second = parser.parse(
            Uri.parse("https://video.example.test/live/stream.m3u8"),
            playlistBytes(
                """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-MEDIA-SEQUENCE:100
                #EXTINF:2.0,
                segment-100.ts
                #EXTINF:2.0,
                segment-101.ts
                #EXT-X-TWITCH-PREFETCH:segment-102.ts
                #EXT-X-TWITCH-PREFETCH:segment-103.ts
                """,
            ),
        ) as HlsMediaPlaylist

        assertEquals(listOf("segment-100.ts", "segment-101.ts", "segment-102.ts"), first.segments.map { it.url })
        assertEquals(
            listOf("segment-100.ts", "segment-101.ts", "segment-102.ts", "segment-103.ts"),
            second.segments.map { it.url },
        )
        assertEquals(listOf(100L, 101L, 102L), first.segments.indices.map { first.mediaSequence + it })
        assertEquals(listOf(100L, 101L, 102L, 103L), second.segments.indices.map { second.mediaSequence + it })
        assertTrue(first.trailingParts.isEmpty())
        assertTrue(second.trailingParts.isEmpty())
    }

    @Test
    fun lowLatencyWithoutPrefetchKeepsServerTargetDuration() {
        val parser = TwitchHlsPlaylistParserFactory(lowLatencyEnabled = true)
            .createPlaylistParser()
        val playlist = parser.parse(
            Uri.parse("https://video.example.test/live/stream.m3u8"),
            playlistBytes(
                """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-MEDIA-SEQUENCE:100
                #EXTINF:2.0,
                segment-100.ts
                """,
            ),
        ) as HlsMediaPlaylist

        assertEquals(6_000_000L, playlist.targetDurationUs)
    }

    @Test
    fun standardLowLatencyPlaylistKeepsItsServerTargetDuration() {
        val parser = TwitchHlsPlaylistParserFactory(lowLatencyEnabled = true)
            .createPlaylistParser()
        val playlist = parser.parse(
            Uri.parse("https://video.example.test/live/stream.m3u8"),
            playlistBytes(
                """
                #EXTM3U
                #EXT-X-VERSION:9
                #EXT-X-TARGETDURATION:6
                #EXT-X-PART-INF:PART-TARGET=0.333
                #EXTINF:2.0,
                segment-100.m4s
                #EXT-X-PART:DURATION=0.333,URI="part-0.m4s"
                #EXT-X-PRELOAD-HINT:TYPE=PART,URI="part-1.m4s"
                """,
            ),
        ) as HlsMediaPlaylist

        assertEquals(6_000_000L, playlist.targetDurationUs)
    }

    @Test
    fun stitchedAdDaterangeWithoutAssetUriRemainsDetectable() {
        val parser = TwitchHlsPlaylistParserFactory(lowLatencyEnabled = true)
            .createPlaylistParser()
        val playlist = parser.parse(
            Uri.parse("https://video.example.test/live/stream.m3u8"),
            playlistBytes(
                """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-MEDIA-SEQUENCE:100
                #EXT-X-PROGRAM-DATE-TIME:2024-01-01T00:00:00Z
                #EXT-X-DATERANGE:ID="stitched-ad-1",CLASS="twitch-stitched-ad",START-DATE="2024-01-01T00:00:00Z",DURATION=30
                #EXTINF:2.0,ad
                ad-100.ts
                #EXT-X-TWITCH-PREFETCH:ad-101.ts
                """,
            ),
        ) as HlsMediaPlaylist

        assertTrue(playlist.interstitials.isNotEmpty())
        assertTrue(TwitchAdDetector.isAd(playlist))
    }

    @Test
    fun titleOnlyAdSuppressesPrefetchTranslationAndRemainsDetectable() {
        val parser = TwitchHlsPlaylistParserFactory(lowLatencyEnabled = true)
            .createPlaylistParser()
        val playlist = parser.parse(
            Uri.parse("https://video.example.test/live/stream.m3u8"),
            playlistBytes(
                """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-MEDIA-SEQUENCE:100
                #EXTINF:2.0,Amazon
                ad-100.ts
                #EXT-X-TWITCH-PREFETCH:ad-101.ts
                #EXT-X-TWITCH-PREFETCH:ad-102.ts
                """,
            ),
        ) as HlsMediaPlaylist

        assertEquals(6_000_000L, playlist.targetDurationUs)
        assertEquals(listOf("ad-100.ts"), playlist.segments.map { it.url })
        assertTrue(TwitchAdDetector.isAd(playlist))
    }

    @Test
    fun suppressedPrefetchKeepsServerTargetDuration() {
        val parser = TwitchHlsPlaylistParserFactory(lowLatencyEnabled = true)
            .createPlaylistParser()
        val playlist = parser.parse(
            Uri.parse("https://video.example.test/live/stream.m3u8"),
            playlistBytes(
                """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-MEDIA-SEQUENCE:100
                #EXTINF:2.0,
                segment-100.ts
                #EXT-X-DISCONTINUITY
                #EXT-X-TWITCH-PREFETCH:possible-ad.ts
                """,
            ),
        ) as HlsMediaPlaylist

        assertEquals(6_000_000L, playlist.targetDurationUs)
    }

    @Test
    fun unavailableQualitiesAreAddedOnceAlongsideRealVariants() {
        val parser = TwitchHlsPlaylistParserFactory(lowLatencyEnabled = false)
            .createPlaylistParser()
        val playlist = parser.parse(
            Uri.parse("https://video.example.test/live/master.m3u8"),
            playlistBytes(
                """
                #EXTM3U
                #EXT-X-SESSION-DATA:DATA-ID="com.amazon.ivs.unavailable-media",VALUE="W3siSVZTX05BTUUiOiI3MjBwNjAiLCJCQU5EV0lEVEgiOjMwMDAwMDAsIkNPREVDUyI6ImF2YzEuNjQwMDJBIiwiUkVTT0xVVElPTiI6IjEyODB4NzIwIiwiRlJBTUUtUkFURSI6NjAsIlNUQUJMRS1WQVJJQU5ULUlEIjoiNzIwcDYwIn0seyJJVlNfTkFNRSI6IjQ4MHAzMCIsIkJBTkRXSURUSCI6MTUwMDAwMCwiQ09ERUNTIjoiYXZjMS40RDQwMUYiLCJSRVNPTFVUSU9OIjoiODU0eDQ4MCIsIkZSQU1FLVJBVEUiOjMwLCJTVEFCTEUtVkFSSUFOVC1JRCI6IjQ4MHAzMCJ9XQ=="
                #EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=1920x1080,CODECS="avc1.64002A",IVS-NAME="1080p60",STABLE-VARIANT-ID="1080p60"
                1080p60/index-dvr.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1280x720,CODECS="avc1.4D401F",IVS-NAME="720p30",STABLE-VARIANT-ID="720p30"
                720p30/index-dvr.m3u8
                """,
            ),
        ) as androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist

        assertEquals(4, playlist.variants.size)
        assertEquals(4, playlist.variants.map { it.url }.toSet().size)
        assertEquals(4, playlist.variants.mapNotNull { it.stableVariantId }.toSet().size)
        assertTrue(playlist.variants.any { it.format.label == "720p60" })
        assertTrue(playlist.variants.any { it.format.label == "480p30" })
    }

    @Test
    fun masterPlaylistRetainsCea608CompatibilityTrack() {
        val parser = TwitchHlsPlaylistParserFactory(lowLatencyEnabled = false)
            .createPlaylistParser()
        val playlist = parser.parse(
            Uri.parse("https://video.example.test/live/master.m3u8"),
            playlistBytes(
                """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=1920x1080,CODECS="avc1.64002A"
                video/index.m3u8
                """,
            ),
        ) as androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist

        assertTrue(
            playlist.muxedCaptionFormats.orEmpty().any {
                it.sampleMimeType == MimeTypes.APPLICATION_CEA608
            },
        )
    }

    @Test
    fun genericTrailingPartsDoNotReportTwitchPrefetchAsActive() {
        val parser = TwitchHlsPlaylistParserFactory(lowLatencyEnabled = true)
            .createPlaylistParser()
        val playlist = parser.parse(
            Uri.parse("https://video.example.test/live/stream.m3u8"),
            playlistBytes(
                """
                #EXTM3U
                #EXT-X-VERSION:9
                #EXT-X-TARGETDURATION:6
                #EXT-X-PART-INF:PART-TARGET=0.333
                #EXTINF:2.0,
                segment-100.m4s
                #EXT-X-PART:DURATION=0.333,URI="part-0.m4s"
                #EXT-X-PRELOAD-HINT:TYPE=PART,URI="part-1.m4s"
                """,
            ),
        ) as HlsMediaPlaylist

        assertTrue(playlist.trailingParts.any { !it.isPreload })
        val store = PlaybackVideoDiagnosticsStore()
        store.recordTwitchHlsPlaylist(
            TwitchHlsPlaylistDiagnostics(twitchPrefetchActive = false),
            playlist,
        )

        assertFalse(store.snapshot().twitchPrefetchActive == true)
        assertEquals(333L, store.snapshot().partTargetDurationMs)
    }

    private fun playlistBytes(value: String): ByteArrayInputStream =
        ByteArrayInputStream(value.trimIndent().toByteArray(StandardCharsets.UTF_8))
}
