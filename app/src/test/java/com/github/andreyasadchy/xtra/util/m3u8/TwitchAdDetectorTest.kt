package com.github.andreyasadchy.xtra.util.m3u8

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchAdDetectorTest {

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
