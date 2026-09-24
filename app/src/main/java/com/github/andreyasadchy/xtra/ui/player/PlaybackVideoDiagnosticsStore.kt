package com.github.andreyasadchy.xtra.ui.player

import androidx.media3.common.C
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.player.hls.TwitchHlsPlaylistDiagnostics
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Small lock-free snapshot store shared by Media3 callbacks and the service command path. */
class PlaybackVideoDiagnosticsStore {
    private data class PendingVideoSize(val width: Int, val height: Int)

    private val state = AtomicReference(
        PlaybackVideoInfo(media3Version = MediaLibraryInfo.VERSION),
    )
    private val pendingRenderedVideoSize = AtomicReference<PendingVideoSize?>(null)
    private val awaitingRenderedFirstFrame = AtomicBoolean(false)
    private val renderedFirstFrameObserved = AtomicBoolean(false)
    private val previousSelectedVideoQuality = AtomicReference<VideoQuality?>(null)
    private val lastConfirmedVideoQuality = AtomicReference<VideoQuality?>(null)

    fun update(block: (PlaybackVideoInfo) -> PlaybackVideoInfo) {
        state.updateAndGet(block)
    }

    fun snapshot(): PlaybackVideoInfo = state.get()

    fun confirmedVideoQuality(): VideoQuality? = lastConfirmedVideoQuality.get()

    fun recordVideoInputFormat(format: androidx.media3.common.Format) {
        if (format.width <= 0 || format.height <= 0) return
        val frameRate = format.frameRate.takeIf { it > 0f && it.isFinite() }?.roundToInt()
        val qualityName = buildString {
            append(format.height)
            append('p')
            frameRate?.takeIf { it > 30 }?.let(::append)
        }
        lastConfirmedVideoQuality.set(
            VideoQuality(
                name = qualityName,
                codecs = format.codecs,
                bitrate = format.bitrate.takeIf { it > 0 },
            ),
        )
        update { current ->
            current.copy(
                selectedVideoWidth = format.width,
                selectedVideoHeight = format.height,
                videoFrameRate = format.frameRate.takeIf { it > 0f },
                videoBitrate = format.bitrate.takeIf { it > 0 },
                videoCodec = format.codecs,
                videoMimeType = format.sampleMimeType,
            )
        }
    }

    fun snapshot(player: Player): PlaybackVideoInfo {
        val current = state.get()
        return current.copy(
            bufferMs = player.totalBufferedDuration.takeIf { it >= 0L },
            liveOffsetMs = player.currentLiveOffset.takeIf { it != C.TIME_UNSET },
        )
    }

    fun recordRenderedVideoSize(width: Int, height: Int, tracks: Tracks) {
        if (width <= 0 || height <= 0) return

        val pending = PendingVideoSize(width, height)
        if (awaitingRenderedFirstFrame.get()) {
            pendingRenderedVideoSize.set(pending)
            if (renderedFirstFrameObserved.get()) {
                confirmPendingRenderedVideoSize(tracks, allowSelectedTrackFallback = false)
            }
        } else if (sizeMatchesSelectedTrack(width, height, tracks)) {
            recordConfirmedRenderedVideoSize(pending, tracks)
        } else {
            pendingRenderedVideoSize.set(pending)
        }
    }

    fun recordRenderedFirstFrame(tracks: Tracks) {
        renderedFirstFrameObserved.set(true)
        confirmPendingRenderedVideoSize(tracks, allowSelectedTrackFallback = true)
    }

    fun confirmPendingRenderedVideoSizeAfterTracksChanged(tracks: Tracks) {
        selectedVideoQuality(tracks)?.let { selectedQuality ->
            val previousQuality = previousSelectedVideoQuality.getAndSet(selectedQuality)
            if (previousQuality != null && previousQuality.name != selectedQuality.name) {
                val previousRenderedVideo = state.get().let { current ->
                    val width = current.renderedVideoWidth
                    val height = current.renderedVideoHeight
                    if (width != null && height != null && width > 0 && height > 0) {
                        PendingVideoSize(width, height)
                    } else {
                        null
                    }
                }
                pendingRenderedVideoSize.set(previousRenderedVideo)
                awaitingRenderedFirstFrame.set(true)
                renderedFirstFrameObserved.set(false)
            }
        }

        if (renderedFirstFrameObserved.get()) {
            confirmPendingRenderedVideoSize(tracks, allowSelectedTrackFallback = true)
        } else if (!awaitingRenderedFirstFrame.get()) {
            confirmPendingRenderedVideoSize(tracks, allowSelectedTrackFallback = false)
        }
    }

    private fun confirmPendingRenderedVideoSize(
        tracks: Tracks,
        allowSelectedTrackFallback: Boolean,
    ) {
        val selectedSize = selectedVideoSize(tracks) ?: return
        val pending = pendingRenderedVideoSize.get()
        if (pending == selectedSize) {
            pendingRenderedVideoSize.set(null)
            recordConfirmedRenderedVideoSize(pending, tracks)
        } else if (pending == null && allowSelectedTrackFallback) {
            recordConfirmedRenderedVideoSize(selectedSize, tracks)
        } else {
            return
        }
        awaitingRenderedFirstFrame.set(false)
    }

    private fun selectedVideoSize(tracks: Tracks): PendingVideoSize? =
        tracks.groups.firstNotNullOfOrNull { group ->
            if (group.type != C.TRACK_TYPE_VIDEO) return@firstNotNullOfOrNull null
            (0 until group.length).firstNotNullOfOrNull { index ->
                if (!group.isTrackSelected(index)) return@firstNotNullOfOrNull null
                val format = group.getTrackFormat(index)
                if (format.width <= 0 || format.height <= 0) return@firstNotNullOfOrNull null
                PendingVideoSize(format.width, format.height)
            }
        }

    private fun sizeMatchesSelectedTrack(width: Int, height: Int, tracks: Tracks): Boolean =
        tracks.groups.any { group ->
            group.type == C.TRACK_TYPE_VIDEO && (0 until group.length).any { index ->
                val format = group.getTrackFormat(index)
                group.isTrackSelected(index) && format.width == width && format.height == height
            }
        }

    private fun recordConfirmedRenderedVideoSize(videoSize: PendingVideoSize, tracks: Tracks) {
        pendingRenderedVideoSize.set(null)
        update { current ->
            current.copy(
                selectedVideoWidth = videoSize.width,
                selectedVideoHeight = videoSize.height,
                renderedVideoWidth = videoSize.width,
                renderedVideoHeight = videoSize.height,
            )
        }
    }

    private fun selectedVideoQuality(tracks: Tracks): VideoQuality? =
        tracks.groups.asSequence()
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group ->
                (0 until group.length).asSequence()
                    .filter(group::isTrackSelected)
                    .map(group::getTrackFormat)
            }
            .firstOrNull { it.width > 0 && it.height > 0 }
            ?.let { format ->
                val frameRate = format.frameRate.takeIf { it > 0f && it.isFinite() }?.roundToInt()
                val name = buildString {
                    append(format.height)
                    append('p')
                    frameRate?.takeIf { it > 30 }?.let(::append)
                }
                VideoQuality(
                    name = name,
                    codecs = format.codecs,
                    bitrate = format.bitrate.takeIf { it > 0 },
                )
            }

    fun resetRenderedVideoSize() {
        pendingRenderedVideoSize.set(null)
        awaitingRenderedFirstFrame.set(true)
        renderedFirstFrameObserved.set(false)
        update { current ->
            current.copy(renderedVideoWidth = null, renderedVideoHeight = null)
        }
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

    fun resetForNewMedia(preserveConfirmedVideoQuality: Boolean = false) {
        pendingRenderedVideoSize.set(null)
        awaitingRenderedFirstFrame.set(true)
        renderedFirstFrameObserved.set(false)
        previousSelectedVideoQuality.set(null)
        if (!preserveConfirmedVideoQuality) {
            lastConfirmedVideoQuality.set(null)
        }
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
                twitchPrefetchSuppressed = null,
                declaredTargetDurationMs = null,
                effectiveReloadTargetDurationMs = null,
                averageSegmentDurationMs = null,
                partTargetDurationMs = null,
                manifestLoadCount = 0L,
                manifestBytesLoaded = 0L,
                mediaLoadCount = 0L,
                mediaBytesLoaded = 0L,
            )
        }
    }

    fun recordTwitchHlsDiagnostics(diagnostics: TwitchHlsPlaylistDiagnostics) {
        update { current ->
            current.copy(
                hlsContainer = diagnostics.container ?: current.hlsContainer,
                twitchPrefetchPresent = diagnostics.twitchPrefetchDetected,
                twitchPrefetchActive = diagnostics.twitchPrefetchActive,
                twitchPrefetchSuppressed = diagnostics.twitchPrefetchSuppressed,
                declaredTargetDurationMs = diagnostics.declaredTargetDurationMs,
                effectiveReloadTargetDurationMs = diagnostics.effectiveReloadTargetDurationMs,
                averageSegmentDurationMs = diagnostics.averageSegmentDurationMs,
                partTargetDurationMs = diagnostics.partTargetDurationMs,
            )
        }
    }

    fun recordTwitchHlsPlaylist(
        diagnostics: TwitchHlsPlaylistDiagnostics,
        parsed: HlsPlaylist,
    ) {
        val mediaPlaylist = parsed as? HlsMediaPlaylist
        val partTargetDurationMs = mediaPlaylist?.partTargetDurationUs
            ?.takeIf { it != C.TIME_UNSET }
            ?.div(1_000L)
        recordTwitchHlsDiagnostics(
            diagnostics.copy(
                twitchPrefetchActive = diagnostics.twitchPrefetchActive,
                partTargetDurationMs = partTargetDurationMs,
            ),
        )
    }
}
