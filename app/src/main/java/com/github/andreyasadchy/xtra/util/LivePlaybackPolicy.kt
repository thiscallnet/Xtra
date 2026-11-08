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
    val buffered: LivePlaybackPolicy,
    val recovering: LivePlaybackPolicy,
) {
    fun forMode(mode: LivePlaybackMode): LivePlaybackPolicy = when (mode) {
        LivePlaybackMode.MINIMUM_DELAY -> minimumDelay
        LivePlaybackMode.ADAPTIVE -> minimumDelay
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

    fun forSettings(settings: LivePlaybackSettings): LivePlaybackPolicySet {
        val minimumDelayBuffers = LOW_LATENCY.buffers.withOverrides(settings.buffers)
        val bufferedBuffers = BUFFERED.buffers.scaledOverridesFrom(
            base = LOW_LATENCY.buffers,
            customizedBase = minimumDelayBuffers,
            overrides = settings.buffers,
        )
        val recoveringBuffers = RECOVERING.buffers.scaledOverridesFrom(
            base = LOW_LATENCY.buffers,
            customizedBase = minimumDelayBuffers,
            overrides = settings.buffers,
        )
        val minimumDelayOffsetMs = settings.targetOffsetMs ?: LOW_LATENCY.targetOffsetMs
        return LivePlaybackPolicySet(
            normal = NORMAL.copy(
                buffers = NORMAL.buffers.withOverrides(settings.buffers),
                targetOffsetMs = settings.targetOffsetMs ?: NORMAL.targetOffsetMs,
            ),
            minimumDelay = LOW_LATENCY.copy(
                buffers = minimumDelayBuffers,
                targetOffsetMs = minimumDelayOffsetMs,
            ),
            buffered = BUFFERED.copy(
                buffers = bufferedBuffers,
                targetOffsetMs = maxOf(BUFFERED.targetOffsetMs, minimumDelayOffsetMs + 4_000L),
            ),
            recovering = RECOVERING.copy(
                buffers = recoveringBuffers,
                targetOffsetMs = maxOf(RECOVERING.targetOffsetMs, minimumDelayOffsetMs + 8_000L),
            ),
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

    private fun PlaybackBufferPolicy.scaledOverridesFrom(
        base: PlaybackBufferPolicy,
        customizedBase: PlaybackBufferPolicy,
        overrides: PlaybackBufferOverrides,
    ): PlaybackBufferPolicy {
        val requestedMin = (
            if (overrides.minBufferMs == null) minBufferMs else {
                (customizedBase.minBufferMs * minBufferMs.toFloat() / base.minBufferMs)
                    .toInt()
                    .coerceAtLeast(250)
            }
        ).coerceAtMost(60_000)
        val requestedMax = (
            if (overrides.maxBufferMs == null) maxBufferMs else {
                (customizedBase.maxBufferMs * maxBufferMs.toFloat() / base.maxBufferMs)
                    .toInt()
                    .coerceAtLeast(500)
            }
        ).coerceAtMost(120_000)
        val min = when {
            requestedMin < requestedMax -> requestedMin
            overrides.minBufferMs != null -> requestedMin
            else -> (requestedMax / 2).coerceAtLeast(250)
        }
        val max = when {
            requestedMin < requestedMax -> requestedMax
            overrides.minBufferMs != null -> (min * 2).coerceAtMost(120_000)
            else -> requestedMax
        }
        return copy(
            minBufferMs = min,
            maxBufferMs = max,
            bufferForPlaybackMs = (
                if (overrides.bufferForPlaybackMs == null) bufferForPlaybackMs else {
                    (customizedBase.bufferForPlaybackMs * bufferForPlaybackMs.toFloat() / base.bufferForPlaybackMs)
                        .toInt()
                        .coerceAtLeast(100)
                }
            ).coerceAtMost(min),
            bufferForPlaybackAfterRebufferMs = (
                if (overrides.bufferForPlaybackAfterRebufferMs == null) bufferForPlaybackAfterRebufferMs else {
                    (customizedBase.bufferForPlaybackAfterRebufferMs * bufferForPlaybackAfterRebufferMs.toFloat() / base.bufferForPlaybackAfterRebufferMs)
                        .toInt()
                        .coerceAtLeast(100)
                }
            ).coerceAtMost(min),
        )
    }
}

