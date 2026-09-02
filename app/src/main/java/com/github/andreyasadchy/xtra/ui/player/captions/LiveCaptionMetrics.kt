package com.github.andreyasadchy.xtra.ui.player.captions

data class LiveCaptionMetrics(
    val engineId: String,
    val engineInitMs: Long = 0,
    val firstOutputAfterStartMs: Long? = null,
    val lastInferenceMs: Long = 0,
    val maxInferenceMs: Long = 0,
    val inferenceCalls: Long = 0,
    val droppedAudioBuffers: Int = 0,
    val realTimeFactor: Double = 0.0,
    val pcmBuffersReceived: Long = 0,
    val acceptedAudioMs: Long = 0,
)
