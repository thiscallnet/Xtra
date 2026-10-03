package com.github.andreyasadchy.xtra.util.m3u8

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchVaftDetectorTest {

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
        assertTrue(TwitchVaftDetector.requiresVaft(playlist))
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
        assertTrue(playlist.dateRanges.single().vaftMarker)
        org.junit.Assert.assertEquals("2024-01-01T00:00:06Z", playlist.segments.last().programDateTime)
        assertFalse(TwitchVaftDetector.requiresVaft(playlist))
    }

    @Test
    fun distinguishesExplicitVaftFromScheduleOnlyClasses() {
        listOf("twitch-stitched-mid", "twitch-maf-ad").forEach { vaftClass ->
            val playlist = parsePlaylist(
                """
                    #EXTM3U
                    #EXT-X-TARGETDURATION:6
                    #EXT-X-PROGRAM-DATE-TIME:2024-01-01T00:00:05Z
                    #EXT-X-DATERANGE:ID="VAFT-1",CLASS="$vaftClass",START-DATE="2024-01-01T00:00:00Z",END-DATE="2024-01-01T00:01:00Z"
                    #EXTINF:6.0,
                    VAFT-segment.ts
                """.trimIndent(),
            )

            org.junit.Assert.assertEquals(
                "A bare schedule class must not withhold normal media",
                vaftClass.startsWith("twitch-stitched"), TwitchVaftDetector.requiresVaft(playlist),
            )
        }
    }

    @Test
    fun doesNotTreatKnownSessionMetadataAsAnVaft() {
        val playlist = parsePlaylist(
            """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-PROGRAM-DATE-TIME:2024-01-01T00:00:05Z
                #EXT-X-DATERANGE:ID="session-1",CLASS="twitch-session",START-DATE="2024-01-01T00:00:00Z",END-DATE="2024-01-01T00:01:00Z"
                #EXT-X-DATERANGE:ID="trigger-1",CLASS="twitch-trigger",START-DATE="2024-01-01T00:00:00Z",END-ON-NEXT=YES,X-TV-TWITCH-TRIGGER-URL="https://example.invalid/trigger"
                #EXTINF:6.0,
                content-segment.ts
            """.trimIndent(),
        )

        assertFalse(TwitchVaftDetector.requiresVaft(playlist))
    }

    private fun parsePlaylist(text: String): MediaPlaylist = PlaylistUtils.parseMediaPlaylist(
        ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)),
    )
}
