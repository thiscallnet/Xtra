package com.github.andreyasadchy.xtra.util

import androidx.media3.exoplayer.DefaultLoadControl

data class PlaybackBufferPolicy(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val prioritizeTimeOverSizeThresholds: Boolean,
) {
    fun buildLoadControl(
        configure: DefaultLoadControl.Builder.() -> Unit = {},
    ): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMsForStreaming(
            minBufferMs,
            maxBufferMs,
            bufferForPlaybackMs,
            bufferForPlaybackAfterRebufferMs,
        )
        .setPrioritizeTimeOverSizeThresholdsForStreaming(prioritizeTimeOverSizeThresholds)
        .apply(configure)
        .build()
}

data class LivePlaybackPolicy(
    val lowLatency: Boolean,
    val buffers: PlaybackBufferPolicy,
    val targetOffsetMs: Long,
)

object LivePlaybackPolicies {
    val NORMAL = LivePlaybackPolicy(
        lowLatency = false,
        buffers = PlaybackBufferPolicy(
            minBufferMs = 15_000,
            maxBufferMs = 50_000,
            bufferForPlaybackMs = 2_000,
            bufferForPlaybackAfterRebufferMs = 2_000,
            prioritizeTimeOverSizeThresholds = false,
        ),
        targetOffsetMs = C.NORMAL_LATENCY_TARGET_OFFSET_MS,
    )

    val LOW_LATENCY = LivePlaybackPolicy(
        lowLatency = true,
        buffers = PlaybackBufferPolicy(
            minBufferMs = 1_500,
            maxBufferMs = 6_000,
            bufferForPlaybackMs = 250,
            bufferForPlaybackAfterRebufferMs = 500,
            prioritizeTimeOverSizeThresholds = true,
        ),
        targetOffsetMs = C.LOW_LATENCY_TARGET_OFFSET_MS,
    )

    val BUFFERED = LivePlaybackPolicy(
        lowLatency = true,
        buffers = PlaybackBufferPolicy(
            minBufferMs = 6_000,
            maxBufferMs = 18_000,
            bufferForPlaybackMs = 1_000,
            bufferForPlaybackAfterRebufferMs = 2_000,
            prioritizeTimeOverSizeThresholds = true,
        ),
        targetOffsetMs = 6_000L,
    )

    val RECOVERING = LivePlaybackPolicy(
        lowLatency = true,
        buffers = PlaybackBufferPolicy(
            minBufferMs = 12_000,
            maxBufferMs = 30_000,
            bufferForPlaybackMs = 1_500,
            bufferForPlaybackAfterRebufferMs = 3_000,
            prioritizeTimeOverSizeThresholds = true,
        ),
        targetOffsetMs = 10_000L,
    )

    fun forLowLatency(enabled: Boolean): LivePlaybackPolicy = if (enabled) LOW_LATENCY else NORMAL
}

