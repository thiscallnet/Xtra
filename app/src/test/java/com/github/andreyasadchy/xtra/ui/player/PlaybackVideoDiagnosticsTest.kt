package com.github.andreyasadchy.xtra.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackVideoDiagnosticsTest {

    @Test
    fun `dropped frame reports accumulate`() {
        val store = PlaybackVideoDiagnosticsStore()

        store.recordDroppedVideoFrames(3)
        store.recordDroppedVideoFrames(4)
        store.recordDroppedVideoFrames(-1)

        assertEquals(7L, store.snapshot().droppedVideoFrames)
    }

    @Test
    fun `reset clears media session values but keeps backend`() {
        val store = PlaybackVideoDiagnosticsStore()
        store.update {
            it.copy(
                networkBackend = "OkHttp",
                contentProtocol = "HLS",
                videoDecoderName = "decoder",
                droppedVideoFrames = 12L,
                manifestLoadCount = 4L,
                mediaLoadCount = 20L,
            )
        }

        store.resetForNewMedia()
        val reset = store.snapshot()

        assertEquals("OkHttp", reset.networkBackend)
        assertEquals(null, reset.contentProtocol)
        assertEquals(null, reset.videoDecoderName)
        assertEquals(0L, reset.droppedVideoFrames)
        assertEquals(0L, reset.manifestLoadCount)
        assertEquals(0L, reset.mediaLoadCount)
    }

    @Test
    fun `bitrate fallback prefers average then peak then bitrate`() {
        assertEquals(2_000, firstPositiveBitrate(2_000, 3_000, 4_000))
        assertEquals(3_000, firstPositiveBitrate(0, 3_000, 4_000))
        assertEquals(4_000, firstPositiveBitrate(0, 0, 4_000))
        assertEquals(null, firstPositiveBitrate(0, -1, 0))
    }

    @Test
    fun `bitrate formatting keeps video values readable`() {
        assertEquals("4489 Kbps", formatBitrate(4_489_000L))
        assertEquals("223 Mbps", formatBitrate(223_000_000L))
    }

    @Test
    fun `unknown live offset renders as a dash`() {
        assertEquals("—", formatDurationMs(null))
        assertEquals("—", formatDurationMs(androidx.media3.common.C.TIME_UNSET))
        assertEquals("1.8 s", formatDurationMs(1_750L))
    }

    @Test
    fun `latency mode is only shown for live content`() {
        assertEquals(
            "—",
            latencyMode(PlaybackVideoInfo(contentProtocol = "HLS")),
        )
        assertEquals(
            "Normal",
            latencyMode(PlaybackVideoInfo(contentProtocol = "HLS", isLiveContent = true)),
        )
    }

    @Test
    fun `copy text strips URL and credential material`() {
        val info = PlaybackVideoInfo(
            videoCodec = "https://example.test/live.m3u8?sig=secret&token=private",
            networkBackend = "token=private",
            contentProtocol = "HLS",
        )

        val copyText = info.toSanitizedText(PlaybackVideoViewMetrics())

        assertFalse(copyText.contains("https", ignoreCase = true))
        assertFalse(copyText.contains("secret"))
        assertFalse(copyText.contains("private"))
        assertTrue(copyText.contains("<redacted>"))
    }
}
