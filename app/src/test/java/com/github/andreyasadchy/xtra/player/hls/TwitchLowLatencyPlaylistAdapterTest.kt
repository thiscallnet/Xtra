package com.github.andreyasadchy.xtra.player.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchLowLatencyPlaylistAdapterTest {

    @Test
    fun normalLatencyPreservesTargetDurationAndPrefetchTags() {
        val result = TwitchLowLatencyPlaylistAdapter.adapt(
            playlist(
                "#EXT-X-TARGETDURATION:6",
                "#EXTINF:2.001,",
                "segment-current.ts",
                "#EXT-X-TWITCH-PREFETCH:segment-next.ts",
                "#EXT-X-TWITCH-PREFETCH:segment-after-next.ts",
            ),
            enabled = false,
        )

        assertEquals(6_000L, result.diagnostics.declaredTargetDurationMs)
        assertEquals(null, result.diagnostics.effectiveReloadTargetDurationMs)
        assertEquals(2, result.diagnostics.twitchPrefetchCount)
        assertFalse(result.diagnostics.twitchPrefetchTranslated)
        assertTrue(result.playlistText.contains("#EXT-X-TWITCH-PREFETCH:segment-next.ts"))
        assertFalse(result.playlistText.contains("#EXT-X-PART:"))
    }

    @Test
    fun lowLatencyTranslatesPrefetchIntoDistinctSegmentsWithEffectiveReloadTarget() {
        val result = TwitchLowLatencyPlaylistAdapter.adapt(
            playlist(
                "#EXT-X-TARGETDURATION:6",
                "#EXTINF:2.001,",
                "segment-current.ts",
                "#EXT-X-TWITCH-PREFETCH:segment-next.ts",
                "#EXT-X-TWITCH-PREFETCH:segment-after-next.ts",
            ),
            enabled = true,
        )

        assertEquals(6_000L, result.diagnostics.declaredTargetDurationMs)
        assertEquals(2_000L, result.diagnostics.effectiveReloadTargetDurationMs)
        assertTrue(result.diagnostics.twitchPrefetchTranslated)
        assertTrue(result.playlistText.contains("#EXT-X-TARGETDURATION:2"))
        assertEquals(2_001L, result.diagnostics.averageSegmentDurationMs)
        assertEquals(3, result.playlistText.lines().count { it.startsWith("#EXTINF:") })
        assertEquals(
            listOf("segment-current.ts", "segment-next.ts", "segment-after-next.ts"),
            mediaUris(result.playlistText),
        )
        assertFalse(result.playlistText.contains("#EXT-X-PART:"))
    }

    @Test
    fun standardLowLatencyPlaylistIsNotDoubleTranslated() {
        val raw = playlist(
            "#EXT-X-TARGETDURATION:6",
            "#EXT-X-PART-INF:PART-TARGET=0.333",
            "#EXT-X-PART:DURATION=0.333,URI=\"part.0.m4s\"",
            "#EXTINF:2.001,",
            "segment.m4s",
            "#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"part.1.m4s\"",
        )

        val result = TwitchLowLatencyPlaylistAdapter.adapt(raw, enabled = true)

        assertEquals(raw, result.playlistText)
        assertTrue(result.diagnostics.standardLowLatencyTagsPresent)
        assertFalse(result.diagnostics.twitchPrefetchTranslated)
        assertEquals(null, result.diagnostics.effectiveReloadTargetDurationMs)
    }

    @Test
    fun playlistWithoutPrefetchIsUnchanged() {
        val raw = playlist(
            "#EXT-X-TARGETDURATION:6",
            "#EXTINF:2.0,",
            "segment.ts",
        )

        val result = TwitchLowLatencyPlaylistAdapter.adapt(raw, enabled = true)

        assertEquals(raw, result.playlistText)
        assertFalse(result.diagnostics.twitchPrefetchDetected)
    }

    @Test
    fun fmp4MapIsPreservedBeforeTranslatedSegments() {
        val result = TwitchLowLatencyPlaylistAdapter.adapt(
            playlist(
                "#EXT-X-TARGETDURATION:6",
                "#EXT-X-MAP:URI=\"init.mp4\"",
                "#EXTINF:2.0,",
                "segment-1.m4s",
                "#EXT-X-TWITCH-PREFETCH:segment-2.m4s",
            ),
            enabled = true,
        )

        assertTrue(result.diagnostics.hasExtXMap)
        assertEquals("fMP4/CMAF", result.diagnostics.container)
        assertTrue(result.playlistText.indexOf("#EXT-X-MAP:") < result.playlistText.indexOf("#EXTINF:2"))
        assertEquals(listOf("segment-1.m4s", "segment-2.m4s"), mediaUris(result.playlistText))
        assertFalse(result.playlistText.contains("#EXT-X-PART:"))
    }

    @Test
    fun discontinuityBeforePrefetchSuppressesTranslation() {
        val result = TwitchLowLatencyPlaylistAdapter.adapt(
            playlist(
                "#EXT-X-TARGETDURATION:6",
                "#EXTINF:2.0,",
                "segment-current.ts",
                "#EXT-X-DISCONTINUITY",
                "#EXT-X-TWITCH-PREFETCH:possible-ad.ts",
            ),
            enabled = true,
        )

        assertTrue(result.diagnostics.twitchPrefetchSuppressed)
        assertFalse(result.diagnostics.twitchPrefetchTranslated)
        assertEquals(null, result.diagnostics.effectiveReloadTargetDurationMs)
        assertFalse(result.playlistText.contains("#EXT-X-PART:"))
    }

    @Test
    fun adDateRangeSuppressesTranslation() {
        val result = TwitchLowLatencyPlaylistAdapter.adapt(
            playlist(
                "#EXT-X-TARGETDURATION:6",
                "#EXT-X-DATERANGE:ID=\"stitched-ad-1\",CLASS=\"twitch-stitched-ad\",START-DATE=\"2024-01-01T00:00:00Z\"",
                "#EXTINF:2.0,",
                "ad.ts",
                "#EXT-X-TWITCH-PREFETCH:next-ad.ts",
            ),
            enabled = true,
        )

        assertTrue(result.diagnostics.twitchPrefetchSuppressed)
        assertFalse(result.diagnostics.twitchPrefetchTranslated)
        assertEquals(null, result.diagnostics.effectiveReloadTargetDurationMs)
        assertFalse(result.playlistText.contains("#EXT-X-PART:"))
        assertTrue(result.playlistText.contains("CLASS=\"com.apple.hls.interstitial\""))
        assertTrue(result.playlistText.contains("X-ASSET-URI=\"\""))
    }

    @Test
    fun recentSegmentDurationsDriveSyntheticSegmentDuration() {
        val result = TwitchLowLatencyPlaylistAdapter.adapt(
            playlist(
                "#EXT-X-TARGETDURATION:8",
                "#EXTINF:1.8,",
                "one.ts",
                "#EXTINF:2.1,",
                "two.ts",
                "#EXTINF:2.0,",
                "three.ts",
                "#EXTINF:2.2,",
                "four.ts",
                "#EXT-X-TWITCH-PREFETCH:five.ts",
            ),
            enabled = true,
        )

        assertEquals(8_000L, result.diagnostics.declaredTargetDurationMs)
        assertEquals(2_000L, result.diagnostics.effectiveReloadTargetDurationMs)
        assertEquals(2_025L, result.diagnostics.averageSegmentDurationMs)
        assertTrue(result.playlistText.contains("#EXTINF:2.025,"))
        assertFalse(result.playlistText.contains("#EXT-X-PART:"))
    }

    @Test
    fun vodPlaylistDoesNotReceiveLowLatencyTransformation() {
        val raw = playlist(
            "#EXT-X-PLAYLIST-TYPE:VOD",
            "#EXT-X-TARGETDURATION:6",
            "#EXTINF:2.0,",
            "segment.ts",
            "#EXT-X-TWITCH-PREFETCH:unused.ts",
            "#EXT-X-ENDLIST",
        )

        val result = TwitchLowLatencyPlaylistAdapter.adapt(raw, enabled = true)

        assertEquals(raw, result.playlistText)
        assertFalse(result.diagnostics.twitchPrefetchTranslated)
    }

    @Test
    fun titleOnlyAdBoundarySuppressesPrefetchTranslation() {
        val result = TwitchLowLatencyPlaylistAdapter.adapt(
            playlist(
                "#EXT-X-TARGETDURATION:6",
                "#EXTINF:2.0,Amazon",
                "ad.ts",
                "#EXT-X-TWITCH-PREFETCH:next-ad.ts",
                "#EXT-X-TWITCH-PREFETCH:after-next-ad.ts",
            ),
            enabled = true,
        )

        assertTrue(result.diagnostics.twitchPrefetchSuppressed)
        assertFalse(result.diagnostics.twitchPrefetchTranslated)
        assertEquals(1, result.playlistText.lines().count { it.startsWith("#EXTINF:") })
        assertTrue(result.playlistText.contains("#EXT-X-TWITCH-PREFETCH:next-ad.ts"))
    }

    @Test
    fun effectiveReloadTargetUsesObservedSegmentsOnlyForTranslatedPrefetch() {
        assertEquals(
            2,
            TwitchLowLatencyPlaylistAdapter.effectiveTwitchTargetDurationSeconds(
                declaredSeconds = 6,
                committedDurations = listOf(1.8, 2.1, 2.0, 2.2),
            ),
        )
        assertEquals(
            2,
            TwitchLowLatencyPlaylistAdapter.effectiveTwitchTargetDurationSeconds(
                declaredSeconds = 6,
                committedDurations = emptyList(),
            ),
        )
    }

    @Test
    fun durationEstimationIgnoresInvalidValuesAndHasBoundedFallback() {
        assertEquals(
            2.0,
            TwitchLowLatencyPlaylistAdapter.estimateUpcomingDuration(listOf(0.0, -1.0, Double.NaN)),
            0.0,
        )
        assertEquals(
            10.0,
            TwitchLowLatencyPlaylistAdapter.estimateUpcomingDuration(listOf(20.0)),
            0.0,
        )
    }

    @Test
    fun prefetchSnapshotsKeepEachFutureSegmentDistinct() {
        val first = TwitchLowLatencyPlaylistAdapter.adapt(
            playlist(
                "#EXT-X-TARGETDURATION:6",
                "#EXTINF:2.0,",
                "segment-100.ts",
                "#EXT-X-TWITCH-PREFETCH:segment-101.ts",
                "#EXT-X-TWITCH-PREFETCH:segment-102.ts",
            ),
            enabled = true,
        )
        val second = TwitchLowLatencyPlaylistAdapter.adapt(
            playlist(
                "#EXT-X-TARGETDURATION:6",
                "#EXTINF:2.0,",
                "segment-100.ts",
                "#EXTINF:2.0,",
                "segment-101.ts",
                "#EXT-X-TWITCH-PREFETCH:segment-102.ts",
                "#EXT-X-TWITCH-PREFETCH:segment-103.ts",
            ),
            enabled = true,
        )

        assertEquals(
            listOf("segment-100.ts", "segment-101.ts", "segment-102.ts"),
            mediaUris(first.playlistText),
        )
        assertEquals(
            listOf("segment-100.ts", "segment-101.ts", "segment-102.ts", "segment-103.ts"),
            mediaUris(second.playlistText),
        )
        assertEquals(3, first.playlistText.lines().count { it.startsWith("#EXTINF:") })
        assertEquals(4, second.playlistText.lines().count { it.startsWith("#EXTINF:") })
    }

    private fun playlist(vararg lines: String): String =
        (listOf("#EXTM3U", "#EXT-X-MEDIA-SEQUENCE:100") + lines).joinToString("\n")

    private fun mediaUris(playlist: String): List<String> = playlist.lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
}
