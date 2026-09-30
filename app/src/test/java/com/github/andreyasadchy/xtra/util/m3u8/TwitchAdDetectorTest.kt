package com.github.andreyasadchy.xtra.util.m3u8

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchAdDetectorTest {

    @Test
    fun plannedDurationKeepsAllDigitsAndDoesNotBecomeDuration() {
        val playlist = parsePlaylist("""
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-PROGRAM-DATE-TIME:2024-01-01T00:00:05Z
            #EXT-X-DATERANGE:ID="stitched-ad-1",START-DATE="2024-01-01T00:00:00Z",PLANNED-DURATION=10.5
            #EXTINF:6.0,
            segment.ts
        """.trimIndent())
        org.junit.Assert.assertEquals(10.5f, playlist.dateRanges.single().plannedDuration)
        org.junit.Assert.assertNull(playlist.dateRanges.single().duration)
        assertTrue(TwitchAdDetector.isAd(playlist))
    }

    @Test
    fun laterClosureReplacesOpenRangeAndSegmentDatesAdvance() {
        val playlist = parsePlaylist("""
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-PROGRAM-DATE-TIME:2024-01-01T00:00:00Z
            #EXT-X-DATERANGE:ID="stitched-ad-1",CLASS="twitch-stitched-mid",START-DATE="2024-01-01T00:00:00Z",PLANNED-DURATION=10.5,X-TV-TWITCH-AD-POD="true"
            #EXTINF:6.0,
            first.ts
            #EXT-X-DATERANGE:ID="stitched-ad-1",END-DATE="2024-01-01T00:00:06Z"
            #EXTINF:6.0,
            second.ts
        """.trimIndent())
        org.junit.Assert.assertEquals(1, playlist.dateRanges.size)
        org.junit.Assert.assertEquals("twitch-stitched-mid", playlist.dateRanges.single().rangeClass)
        org.junit.Assert.assertEquals(10.5f, playlist.dateRanges.single().plannedDuration)
        assertTrue(playlist.dateRanges.single().ad)
        org.junit.Assert.assertEquals("2024-01-01T00:00:06Z", playlist.segments.last().programDateTime)
        assertFalse(TwitchAdDetector.isAd(playlist))
    }

    @Test
    fun recognizesMaintainedVaftDaterangeClassFamilies() {
        listOf("twitch-stitched-mid", "twitch-maf-ad", "twitch-trigger").forEach { adClass ->
            val playlist = parsePlaylist(
                """
                    #EXTM3U
                    #EXT-X-TARGETDURATION:6
                    #EXT-X-PROGRAM-DATE-TIME:2024-01-01T00:00:05Z
                    #EXT-X-DATERANGE:ID="ad-1",CLASS="$adClass",START-DATE="2024-01-01T00:00:00Z",END-DATE="2024-01-01T00:01:00Z"
                    #EXTINF:6.0,
                    ad-segment.ts
                """.trimIndent(),
            )

            assertTrue("Expected $adClass to identify an active ad", TwitchAdDetector.isAd(playlist))
        }
    }

    @Test
    fun doesNotTreatKnownSessionMetadataAsAnAd() {
        val playlist = parsePlaylist(
            """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-PROGRAM-DATE-TIME:2024-01-01T00:00:05Z
                #EXT-X-DATERANGE:ID="session-1",CLASS="twitch-session",START-DATE="2024-01-01T00:00:00Z",END-DATE="2024-01-01T00:01:00Z"
                #EXTINF:6.0,
                content-segment.ts
            """.trimIndent(),
        )

        assertFalse(TwitchAdDetector.isAd(playlist))
    }

    private fun parsePlaylist(text: String): MediaPlaylist = PlaylistUtils.parseMediaPlaylist(
        ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)),
    )
}
