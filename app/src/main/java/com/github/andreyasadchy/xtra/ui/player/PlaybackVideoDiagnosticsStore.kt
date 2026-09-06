package com.github.andreyasadchy.xtra.ui.player

import androidx.media3.common.C
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.Player
import java.util.concurrent.atomic.AtomicReference

/** Small lock-free snapshot store shared by Media3 callbacks and the service command path. */
class PlaybackVideoDiagnosticsStore {
    private val state = AtomicReference(
        PlaybackVideoInfo(media3Version = MediaLibraryInfo.VERSION),
    )

    fun update(block: (PlaybackVideoInfo) -> PlaybackVideoInfo) {
        state.updateAndGet(block)
    }

    fun snapshot(): PlaybackVideoInfo = state.get()

    fun snapshot(player: Player): PlaybackVideoInfo {
        val current = state.get()
        return current.copy(
            renderedVideoWidth = player.videoSize.width.takeIf { it > 0 },
            renderedVideoHeight = player.videoSize.height.takeIf { it > 0 },
            bufferMs = player.totalBufferedDuration.takeIf { it >= 0L },
            liveOffsetMs = player.currentLiveOffset.takeIf { it != C.TIME_UNSET },
        )
    }

    fun recordDroppedVideoFrames(droppedFrames: Int) {
        if (droppedFrames <= 0) return
        update { it.copy(droppedVideoFrames = it.droppedVideoFrames + droppedFrames) }
    }

    fun recordLoad(dataType: Int, bytesLoaded: Long) {
        val safeBytes = bytesLoaded.takeIf { it >= 0L } ?: 0L
        update { current ->
            if (dataType == C.DATA_TYPE_MANIFEST) {
                current.copy(
                    manifestLoadCount = current.manifestLoadCount + 1L,
                    manifestBytesLoaded = current.manifestBytesLoaded + safeBytes,
                )
            } else if (dataType == C.DATA_TYPE_MEDIA ||
                dataType == C.DATA_TYPE_MEDIA_INITIALIZATION ||
                dataType == C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE
            ) {
                current.copy(
                    mediaLoadCount = current.mediaLoadCount + 1L,
                    mediaBytesLoaded = current.mediaBytesLoaded + safeBytes,
                )
            } else {
                current
            }
        }
    }

    fun resetForNewMedia() {
        update { current ->
            current.copy(
                selectedVideoWidth = null,
                selectedVideoHeight = null,
                renderedVideoWidth = null,
                renderedVideoHeight = null,
                videoFrameRate = null,
                videoBitrate = null,
                bandwidthEstimateBitsPerSecond = null,
                videoCodec = null,
                videoMimeType = null,
                audioCodec = null,
                audioMimeType = null,
                videoDecoderName = null,
                videoDecoderHardwareAccelerated = null,
                droppedVideoFrames = 0L,
                bufferMs = null,
                liveOffsetMs = null,
                negotiatedProtocol = null,
                contentProtocol = null,
                isLiveContent = false,
                hlsContainer = null,
                lowLatencyRequested = false,
                twitchPrefetchPresent = null,
                twitchPrefetchActive = null,
                declaredTargetDurationMs = null,
                averageSegmentDurationMs = null,
                partTargetDurationMs = null,
                manifestLoadCount = 0L,
                manifestBytesLoaded = 0L,
                mediaLoadCount = 0L,
                mediaBytesLoaded = 0L,
            )
        }
    }
}
