package com.github.andreyasadchy.xtra.ui.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackVideoDiagnosticsBundleTest {

    @Test
    fun videoInfoBundleRoundTripPreservesNullableAndScalarFields() {
        val original = PlaybackVideoInfo(
            selectedVideoWidth = 1920,
            selectedVideoHeight = 1080,
            renderedVideoWidth = 1920,
            renderedVideoHeight = 1080,
            videoFrameRate = 59.94f,
            videoBitrate = 4_489_000,
            bandwidthEstimateBitsPerSecond = 223_000_000L,
            videoCodec = "avc1.64002A",
            videoMimeType = "video/avc",
            audioCodec = "mp4a.40.2",
            audioMimeType = "audio/mp4",
            videoDecoderName = "c2.mtk.avc.decoder",
            videoDecoderHardwareAccelerated = true,
            droppedVideoFrames = 7L,
            bufferMs = 2_500L,
            liveOffsetMs = 1_750L,
            networkBackend = "Cronet",
            negotiatedProtocol = "h3",
            contentProtocol = "HLS",
            isLiveContent = true,
            hlsContainer = "MPEG-TS",
            lowLatencyRequested = true,
            twitchPrefetchPresent = true,
            twitchPrefetchActive = false,
            declaredTargetDurationMs = 6_000L,
            effectiveReloadTargetDurationMs = 2_000L,
            averageSegmentDurationMs = 2_001L,
            partTargetDurationMs = 2_001L,
            manifestLoadCount = 4L,
            manifestBytesLoaded = 3_000L,
            mediaLoadCount = 12L,
            mediaBytesLoaded = 9_000_000L,
            media3Version = "1.11.0",
        )

        assertEquals(original, PlaybackVideoInfo.fromBundle(original.toBundle()))
    }
}
