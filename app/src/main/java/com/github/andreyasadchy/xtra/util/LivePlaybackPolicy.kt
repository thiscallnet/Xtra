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

data class LivePlaybackPolicySet(
    val normal: LivePlaybackPolicy,
    val minimumDelay: LivePlaybackPolicy,
    val custom: LivePlaybackPolicy,
    val buffered: LivePlaybackPolicy,
    val recovering: LivePlaybackPolicy,
) {
    fun forMode(mode: LivePlaybackMode): LivePlaybackPolicy = when (mode) {
        LivePlaybackMode.MINIMUM_DELAY -> minimumDelay
        LivePlaybackMode.CUSTOM -> custom
        LivePlaybackMode.NORMAL -> normal
    }

    fun forAdaptiveState(state: AdaptiveLivePlaybackController.State): LivePlaybackPolicy = when (state) {
        AdaptiveLivePlaybackController.State.NORMAL -> normal
        AdaptiveLivePlaybackController.State.LOW_LATENCY -> minimumDelay
        AdaptiveLivePlaybackController.State.BUFFERED -> buffered
        AdaptiveLivePlaybackController.State.RECOVERING -> recovering
    }
}

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
            bufferForPlaybackAfterRebufferMs = 1_000,
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

    fun forSettings(settings: LivePlaybackSettings): LivePlaybackPolicySet {
        val customBuffers = NORMAL.buffers.withOverrides(settings.buffers)
        return LivePlaybackPolicySet(
            normal = NORMAL,
            minimumDelay = LOW_LATENCY,
            custom = LivePlaybackPolicy(
                lowLatency = true,
                buffers = customBuffers,
                targetOffsetMs = settings.targetOffsetMs ?: NORMAL.targetOffsetMs,
            ),
            buffered = BUFFERED,
            recovering = RECOVERING,
        )
    }

    private fun PlaybackBufferPolicy.withOverrides(overrides: PlaybackBufferOverrides): PlaybackBufferPolicy {
        val requestedMin = overrides.minBufferMs
        val requestedMax = overrides.maxBufferMs
        val min = requestedMin ?: if (requestedMax != null && requestedMax <= minBufferMs) {
            (requestedMax / 2).coerceAtLeast(250)
        } else {
            minBufferMs
        }
        val max = requestedMax ?: if (requestedMin != null && requestedMin >= maxBufferMs) {
            (requestedMin * 2).coerceAtMost(120_000)
        } else {
            maxBufferMs
        }
        val validPair = min < max
        val effectiveMin = if (validPair) min else minBufferMs
        val effectiveMax = if (validPair) max else maxBufferMs
        return copy(
            minBufferMs = effectiveMin,
            maxBufferMs = effectiveMax,
            bufferForPlaybackMs = (overrides.bufferForPlaybackMs ?: bufferForPlaybackMs).coerceAtMost(effectiveMin),
            bufferForPlaybackAfterRebufferMs =
                (overrides.bufferForPlaybackAfterRebufferMs ?: bufferForPlaybackAfterRebufferMs).coerceAtMost(effectiveMin),
        )
    }

}

