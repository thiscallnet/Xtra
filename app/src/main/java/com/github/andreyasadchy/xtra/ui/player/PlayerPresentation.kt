package com.github.andreyasadchy.xtra.ui.player

import androidx.media3.common.C
import androidx.media3.common.Player
import com.github.andreyasadchy.xtra.model.VideoQuality

/**
 * Describes where the canonical playback session is being presented.
 *
 * This is deliberately separate from playWhenReady: moving a player between
 * these presentations must never imply a pause, stop, or media replacement.
 */
enum class PlayerPresentation {
    FULL,
    MINI,
    PIP,
    BACKGROUND,
}

enum class PlaybackContentType {
    LIVE,
    VOD,
    CLIP,
    OFFLINE,
    UNKNOWN,
}

data class PlaybackError(
    val errorCode: Int,
    val httpStatusCode: Int? = null,
)

data class PlaybackUiState(
    val contentType: PlaybackContentType = PlaybackContentType.UNKNOWN,
    val playWhenReady: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val error: PlaybackError? = null,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    val bufferedPositionMs: Long = 0L,
    val videoAspectRatio: Float? = null,
    val isLive: Boolean = false,
    val liveOffsetMs: Long? = null,
    val isAtLiveEdge: Boolean = false,
    val quality: VideoQuality? = null,
    val presentation: PlayerPresentation = PlayerPresentation.BACKGROUND,
)

fun Player.toPlaybackUiState(
    presentation: PlayerPresentation,
    contentType: PlaybackContentType = when {
        currentMediaItem?.mediaId?.startsWith("stream:") == true -> PlaybackContentType.LIVE
        currentMediaItem?.mediaId?.startsWith("clip:") == true -> PlaybackContentType.CLIP
        currentMediaItem?.mediaId?.startsWith("offline:") == true -> PlaybackContentType.OFFLINE
        currentMediaItem != null -> PlaybackContentType.VOD
        else -> PlaybackContentType.UNKNOWN
    },
    quality: VideoQuality? = null,
): PlaybackUiState {
    val duration = duration.takeIf { it != C.TIME_UNSET && it >= 0L }
    val liveOffset = currentLiveOffset.takeIf { it != C.TIME_UNSET && it >= 0L }
    val videoAspectRatio = videoSize.takeIf { it.width > 0 && it.height > 0 }?.let {
        (it.width * it.pixelWidthHeightRatio) / it.height
    }
    val liveTolerance = 5_000L
    return PlaybackUiState(
        contentType = contentType,
        playWhenReady = playWhenReady,
        isPlaying = isPlaying,
        isBuffering = playbackState == Player.STATE_BUFFERING,
        error = playerError?.let { PlaybackError(it.errorCode) },
        positionMs = currentPosition.coerceAtLeast(0L),
        durationMs = duration,
        bufferedPositionMs = bufferedPosition.coerceAtLeast(0L),
        videoAspectRatio = videoAspectRatio,
        isLive = isCurrentMediaItemLive,
        liveOffsetMs = liveOffset,
        isAtLiveEdge = !isCurrentMediaItemLive || liveOffset == null || liveOffset <= liveTolerance,
        quality = quality,
        presentation = presentation,
    )
}
