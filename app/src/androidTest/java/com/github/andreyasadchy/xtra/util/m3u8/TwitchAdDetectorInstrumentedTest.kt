package com.github.andreyasadchy.xtra.util.m3u8

import androidx.media3.common.C
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TwitchAdDetectorInstrumentedTest {
    @Test
    fun rawAndMedia3RangesAgreeAtBoundariesAndForOngoingAds() {
        data class Case(val second: Int, val end: String, val endUs: Long, val durationUs: Long, val plannedUs: Long, val expected: Boolean)
        val unset = C.TIME_UNSET
        listOf(
            Case(5, ",END-DATE=\"2024-01-01T00:00:10Z\"", 10_000_000, unset, unset, true),
            Case(10, ",END-DATE=\"2024-01-01T00:00:10Z\"", 10_000_000, unset, unset, false),
            Case(15, ",END-DATE=\"2024-01-01T00:00:10Z\"", 10_000_000, unset, unset, false),
            Case(5, "", unset, unset, unset, true),
            Case(-1, "", unset, unset, unset, false),
            Case(5, ",PLANNED-DURATION=10", unset, unset, 10_000_000, true),
            Case(5, ",DURATION=10", unset, 10_000_000, unset, true),
        ).forEach { case ->
            val startUs = 1_704_067_200_000_000L
            val instant = java.time.Instant.ofEpochSecond(1_704_067_200L + case.second)
            val raw = parsePlaylist("""
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-PROGRAM-DATE-TIME:$instant
                #EXT-X-DATERANGE:ID="stitched-ad-1",START-DATE="2024-01-01T00:00:00Z"${case.end}
                #EXTINF:6.0,
                segment.ts
            """.trimIndent())
            val marker = HlsMediaPlaylist.Interstitial(
                "stitched-ad-1", android.net.Uri.parse("https://example.invalid/ad.m3u8"), null, startUs,
                if (case.endUs == unset) unset else startUs + case.endUs,
                case.durationUs, case.plannedUs, emptyList(), false,
                unset, unset, emptyList(), emptyList(), emptyList(), false,
                "POINT", "HIGHLIGHT", unset, unset, null,
            )
            val segment = HlsMediaPlaylist.Segment("segment.ts", null, "", 6_000_000, 0, 0,
                null, null, null, 0, -1, false, emptyList())
            val parsed = HlsMediaPlaylist(
                HlsMediaPlaylist.PLAYLIST_TYPE_UNKNOWN, "https://example.invalid/live.m3u8", emptyList(), unset, false,
                startUs + case.second * 1_000_000L, false, 0, 0, 3, 6_000_000, unset,
                false, false, true, null, listOf(segment), emptyList(),
                HlsMediaPlaylist.ServerControl(unset, false, unset, unset, false),
                emptyMap(), listOf(marker), null,
            )
            assertEquals("raw $case", case.expected, TwitchAdDetector.isAd(raw))
            assertEquals("Media3 $case", case.expected, TwitchAdDetector.isAd(parsed))
        }
    }

    private fun parsePlaylist(text: String): MediaPlaylist =
        PlaylistUtils.parseMediaPlaylist(text.byteInputStream())
}
