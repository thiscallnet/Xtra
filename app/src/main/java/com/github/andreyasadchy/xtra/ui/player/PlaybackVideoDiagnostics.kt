package com.github.andreyasadchy.xtra.ui.player

import android.os.Bundle
import androidx.media3.common.MediaLibraryInfo

/**
 * Playback facts collected by the service and presented by the player UI.
 *
 * Viewport dimensions intentionally do not belong here. They are owned by the
 * Fragment because the service has no view hierarchy.
 */
data class PlaybackVideoInfo(
    val selectedVideoWidth: Int? = null,
    val selectedVideoHeight: Int? = null,
    val renderedVideoWidth: Int? = null,
    val renderedVideoHeight: Int? = null,
    val videoFrameRate: Float? = null,
    val videoBitrate: Int? = null,
    val bandwidthEstimateBitsPerSecond: Long? = null,
    val videoCodec: String? = null,
    val videoMimeType: String? = null,
    val audioCodec: String? = null,
    val audioMimeType: String? = null,
    val videoDecoderName: String? = null,
    val videoDecoderHardwareAccelerated: Boolean? = null,
    val droppedVideoFrames: Long = 0L,
    val bufferMs: Long? = null,
    val liveOffsetMs: Long? = null,
    val networkBackend: String? = null,
    val negotiatedProtocol: String? = null,
    val contentProtocol: String? = null,
    val isLiveContent: Boolean = false,
    val hlsContainer: String? = null,
    val lowLatencyRequested: Boolean = false,
    val twitchPrefetchPresent: Boolean? = null,
    val twitchPrefetchActive: Boolean? = null,
    val declaredTargetDurationMs: Long? = null,
    val averageSegmentDurationMs: Long? = null,
    val partTargetDurationMs: Long? = null,
    val manifestLoadCount: Long = 0L,
    val manifestBytesLoaded: Long = 0L,
    val mediaLoadCount: Long = 0L,
    val mediaBytesLoaded: Long = 0L,
    val media3Version: String = MediaLibraryInfo.VERSION,
) {

    fun toBundle(): Bundle = Bundle().apply {
        putNullableInt(KEY_SELECTED_VIDEO_WIDTH, selectedVideoWidth)
        putNullableInt(KEY_SELECTED_VIDEO_HEIGHT, selectedVideoHeight)
        putNullableInt(KEY_RENDERED_VIDEO_WIDTH, renderedVideoWidth)
        putNullableInt(KEY_RENDERED_VIDEO_HEIGHT, renderedVideoHeight)
        putNullableFloat(KEY_VIDEO_FRAME_RATE, videoFrameRate)
        putNullableInt(KEY_VIDEO_BITRATE, videoBitrate)
        putNullableLong(KEY_BANDWIDTH_ESTIMATE, bandwidthEstimateBitsPerSecond)
        putNullableString(KEY_VIDEO_CODEC, videoCodec)
        putNullableString(KEY_VIDEO_MIME_TYPE, videoMimeType)
        putNullableString(KEY_AUDIO_CODEC, audioCodec)
        putNullableString(KEY_AUDIO_MIME_TYPE, audioMimeType)
        putNullableString(KEY_VIDEO_DECODER_NAME, videoDecoderName)
        putNullableBoolean(KEY_VIDEO_DECODER_HARDWARE, videoDecoderHardwareAccelerated)
        putLong(KEY_DROPPED_VIDEO_FRAMES, droppedVideoFrames)
        putNullableLong(KEY_BUFFER_MS, bufferMs)
        putNullableLong(KEY_LIVE_OFFSET_MS, liveOffsetMs)
        putNullableString(KEY_NETWORK_BACKEND, networkBackend)
        putNullableString(KEY_NEGOTIATED_PROTOCOL, negotiatedProtocol)
        putNullableString(KEY_CONTENT_PROTOCOL, contentProtocol)
        putBoolean(KEY_IS_LIVE_CONTENT, isLiveContent)
        putNullableString(KEY_HLS_CONTAINER, hlsContainer)
        putBoolean(KEY_LOW_LATENCY_REQUESTED, lowLatencyRequested)
        putNullableBoolean(KEY_TWITCH_PREFETCH_PRESENT, twitchPrefetchPresent)
        putNullableBoolean(KEY_TWITCH_PREFETCH_ACTIVE, twitchPrefetchActive)
        putNullableLong(KEY_DECLARED_TARGET_DURATION, declaredTargetDurationMs)
        putNullableLong(KEY_AVERAGE_SEGMENT_DURATION, averageSegmentDurationMs)
        putNullableLong(KEY_PART_TARGET_DURATION, partTargetDurationMs)
        putLong(KEY_MANIFEST_LOAD_COUNT, manifestLoadCount)
        putLong(KEY_MANIFEST_BYTES_LOADED, manifestBytesLoaded)
        putLong(KEY_MEDIA_LOAD_COUNT, mediaLoadCount)
        putLong(KEY_MEDIA_BYTES_LOADED, mediaBytesLoaded)
        putString(KEY_MEDIA3_VERSION, media3Version)
    }

    companion object {
        private const val KEY_SELECTED_VIDEO_WIDTH = "selectedVideoWidth"
        private const val KEY_SELECTED_VIDEO_HEIGHT = "selectedVideoHeight"
        private const val KEY_RENDERED_VIDEO_WIDTH = "renderedVideoWidth"
        private const val KEY_RENDERED_VIDEO_HEIGHT = "renderedVideoHeight"
        private const val KEY_VIDEO_FRAME_RATE = "videoFrameRate"
        private const val KEY_VIDEO_BITRATE = "videoBitrate"
        private const val KEY_BANDWIDTH_ESTIMATE = "bandwidthEstimateBitsPerSecond"
        private const val KEY_VIDEO_CODEC = "videoCodec"
        private const val KEY_VIDEO_MIME_TYPE = "videoMimeType"
        private const val KEY_AUDIO_CODEC = "audioCodec"
        private const val KEY_AUDIO_MIME_TYPE = "audioMimeType"
        private const val KEY_VIDEO_DECODER_NAME = "videoDecoderName"
        private const val KEY_VIDEO_DECODER_HARDWARE = "videoDecoderHardwareAccelerated"
        private const val KEY_DROPPED_VIDEO_FRAMES = "droppedVideoFrames"
        private const val KEY_BUFFER_MS = "bufferMs"
        private const val KEY_LIVE_OFFSET_MS = "liveOffsetMs"
        private const val KEY_NETWORK_BACKEND = "networkBackend"
        private const val KEY_NEGOTIATED_PROTOCOL = "negotiatedProtocol"
        private const val KEY_CONTENT_PROTOCOL = "contentProtocol"
        private const val KEY_IS_LIVE_CONTENT = "isLiveContent"
        private const val KEY_HLS_CONTAINER = "hlsContainer"
        private const val KEY_LOW_LATENCY_REQUESTED = "lowLatencyRequested"
        private const val KEY_TWITCH_PREFETCH_PRESENT = "twitchPrefetchPresent"
        private const val KEY_TWITCH_PREFETCH_ACTIVE = "twitchPrefetchActive"
        private const val KEY_DECLARED_TARGET_DURATION = "declaredTargetDurationMs"
        private const val KEY_AVERAGE_SEGMENT_DURATION = "averageSegmentDurationMs"
        private const val KEY_PART_TARGET_DURATION = "partTargetDurationMs"
        private const val KEY_MANIFEST_LOAD_COUNT = "manifestLoadCount"
        private const val KEY_MANIFEST_BYTES_LOADED = "manifestBytesLoaded"
        private const val KEY_MEDIA_LOAD_COUNT = "mediaLoadCount"
        private const val KEY_MEDIA_BYTES_LOADED = "mediaBytesLoaded"
        private const val KEY_MEDIA3_VERSION = "media3Version"

        fun fromBundle(bundle: Bundle): PlaybackVideoInfo = PlaybackVideoInfo(
            selectedVideoWidth = bundle.getNullableInt(KEY_SELECTED_VIDEO_WIDTH),
            selectedVideoHeight = bundle.getNullableInt(KEY_SELECTED_VIDEO_HEIGHT),
            renderedVideoWidth = bundle.getNullableInt(KEY_RENDERED_VIDEO_WIDTH),
            renderedVideoHeight = bundle.getNullableInt(KEY_RENDERED_VIDEO_HEIGHT),
            videoFrameRate = bundle.getNullableFloat(KEY_VIDEO_FRAME_RATE),
            videoBitrate = bundle.getNullableInt(KEY_VIDEO_BITRATE),
            bandwidthEstimateBitsPerSecond = bundle.getNullableLong(KEY_BANDWIDTH_ESTIMATE),
            videoCodec = bundle.getString(KEY_VIDEO_CODEC),
            videoMimeType = bundle.getString(KEY_VIDEO_MIME_TYPE),
            audioCodec = bundle.getString(KEY_AUDIO_CODEC),
            audioMimeType = bundle.getString(KEY_AUDIO_MIME_TYPE),
            videoDecoderName = bundle.getString(KEY_VIDEO_DECODER_NAME),
            videoDecoderHardwareAccelerated = bundle.getNullableBoolean(KEY_VIDEO_DECODER_HARDWARE),
            droppedVideoFrames = bundle.getLong(KEY_DROPPED_VIDEO_FRAMES, 0L),
            bufferMs = bundle.getNullableLong(KEY_BUFFER_MS),
            liveOffsetMs = bundle.getNullableLong(KEY_LIVE_OFFSET_MS),
            networkBackend = bundle.getString(KEY_NETWORK_BACKEND),
            negotiatedProtocol = bundle.getString(KEY_NEGOTIATED_PROTOCOL),
            contentProtocol = bundle.getString(KEY_CONTENT_PROTOCOL),
            isLiveContent = bundle.getBoolean(KEY_IS_LIVE_CONTENT, false),
            hlsContainer = bundle.getString(KEY_HLS_CONTAINER),
            lowLatencyRequested = bundle.getBoolean(KEY_LOW_LATENCY_REQUESTED, false),
            twitchPrefetchPresent = bundle.getNullableBoolean(KEY_TWITCH_PREFETCH_PRESENT),
            twitchPrefetchActive = bundle.getNullableBoolean(KEY_TWITCH_PREFETCH_ACTIVE),
            declaredTargetDurationMs = bundle.getNullableLong(KEY_DECLARED_TARGET_DURATION),
            averageSegmentDurationMs = bundle.getNullableLong(KEY_AVERAGE_SEGMENT_DURATION),
            partTargetDurationMs = bundle.getNullableLong(KEY_PART_TARGET_DURATION),
            manifestLoadCount = bundle.getLong(KEY_MANIFEST_LOAD_COUNT, 0L),
            manifestBytesLoaded = bundle.getLong(KEY_MANIFEST_BYTES_LOADED, 0L),
            mediaLoadCount = bundle.getLong(KEY_MEDIA_LOAD_COUNT, 0L),
            mediaBytesLoaded = bundle.getLong(KEY_MEDIA_BYTES_LOADED, 0L),
            media3Version = bundle.getString(KEY_MEDIA3_VERSION) ?: MediaLibraryInfo.VERSION,
        )
    }
}

internal interface PlaybackVideoInfoHost {
    fun showVideoInfoDialog()

    fun requestVideoInfo(
        onInfo: (PlaybackVideoInfo, PlaybackVideoViewMetrics) -> Unit,
    )
}

private fun Bundle.putNullableString(key: String, value: String?) {
    value?.let { putString(key, it) }
}

private fun Bundle.putNullableInt(key: String, value: Int?) {
    value?.let { putInt(key, it) }
}

private fun Bundle.putNullableLong(key: String, value: Long?) {
    value?.let { putLong(key, it) }
}

private fun Bundle.putNullableFloat(key: String, value: Float?) {
    value?.let { putFloat(key, it) }
}

private fun Bundle.putNullableBoolean(key: String, value: Boolean?) {
    value?.let { putBoolean(key, it) }
}

private fun Bundle.getNullableInt(key: String): Int? = if (containsKey(key)) getInt(key) else null

private fun Bundle.getNullableLong(key: String): Long? = if (containsKey(key)) getLong(key) else null

private fun Bundle.getNullableFloat(key: String): Float? = if (containsKey(key)) getFloat(key) else null

private fun Bundle.getNullableBoolean(key: String): Boolean? = if (containsKey(key)) getBoolean(key) else null
