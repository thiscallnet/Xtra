package com.github.andreyasadchy.xtra.util

import android.content.SharedPreferences
import androidx.media3.common.MediaItem

enum class LivePlaybackMode(val preferenceValue: String) {
    MINIMUM_DELAY(C.PLAYER_LIVE_MODE_MINIMUM_DELAY),
    ADAPTIVE(C.PLAYER_LIVE_MODE_ADAPTIVE),
    NORMAL(C.PLAYER_LIVE_MODE_NORMAL);

    val lowLatencyEnabled: Boolean
        get() = this != NORMAL

    companion object {
        fun fromPreferences(preferences: SharedPreferences): LivePlaybackMode {
            return when (preferences.getString(C.PLAYER_LIVE_MODE, null)) {
                C.PLAYER_LIVE_MODE_MINIMUM_DELAY -> MINIMUM_DELAY
                C.PLAYER_LIVE_MODE_ADAPTIVE -> ADAPTIVE
                C.PLAYER_LIVE_MODE_NORMAL -> NORMAL
                // Keep a user's older Low latency toggle choice when no mode has been saved yet.
                else -> if (preferences.getBoolean(C.PLAYER_LOW_LATENCY, C.DEFAULT_PLAYER_LOW_LATENCY)) {
                    MINIMUM_DELAY
                } else {
                    NORMAL
                }
            }
        }
    }
}

data class PlaybackBufferOverrides(
    val minBufferMs: Int? = null,
    val maxBufferMs: Int? = null,
    val bufferForPlaybackMs: Int? = null,
    val bufferForPlaybackAfterRebufferMs: Int? = null,
)

/** User-selected live latency settings. Null buffer/offset values use each mode's default. */
data class LivePlaybackSettings(
    val mode: LivePlaybackMode,
    val targetOffsetMs: Long?,
    val buffers: PlaybackBufferOverrides,
    val minPlaybackSpeed: Float,
    val maxPlaybackSpeed: Float,
) {
    val lowLatencyEnabled: Boolean
        get() = mode.lowLatencyEnabled

    val adaptiveEnabled: Boolean
        get() = mode == LivePlaybackMode.ADAPTIVE

    fun policies(): LivePlaybackPolicySet = LivePlaybackPolicies.forSettings(this)

    fun liveConfiguration(policy: LivePlaybackPolicy): MediaItem.LiveConfiguration =
        MediaItem.LiveConfiguration.Builder()
            .setTargetOffsetMs(policy.targetOffsetMs)
            .setMinPlaybackSpeed(minPlaybackSpeed)
            .setMaxPlaybackSpeed(maxPlaybackSpeed)
            .apply {
                if (mode == LivePlaybackMode.MINIMUM_DELAY) {
                    // Keep Media3's adaptive live target from increasing the user's low-delay
                    // target after rebuffers. The speed controller still speeds up toward it.
                    setMinOffsetMs(policy.targetOffsetMs)
                    setMaxOffsetMs(policy.targetOffsetMs)
                }
            }
            .build()

    companion object {
        fun defaults(): LivePlaybackSettings = LivePlaybackSettings(
            mode = LivePlaybackMode.MINIMUM_DELAY,
            targetOffsetMs = null,
            buffers = PlaybackBufferOverrides(),
            minPlaybackSpeed = C.DEFAULT_PLAYER_LIVE_MIN_SPEED,
            maxPlaybackSpeed = C.DEFAULT_PLAYER_LIVE_MAX_SPEED,
        )

        fun fromPreferences(preferences: SharedPreferences): LivePlaybackSettings {
            val minBuffer = preferences.readInt(C.PLAYER_BUFFER_MIN, MIN_BUFFER_RANGE)
            val maxBuffer = preferences.readInt(C.PLAYER_BUFFER_MAX, MAX_BUFFER_RANGE)
            val validBufferPair = minBuffer == null || maxBuffer == null || minBuffer < maxBuffer
            val minSpeed = preferences.readFloat(C.PLAYER_LIVE_MIN_SPEED, MIN_SPEED_RANGE)
            val maxSpeed = preferences.readFloat(C.PLAYER_LIVE_MAX_SPEED, MAX_SPEED_RANGE)
            val effectiveMinSpeed = minSpeed ?: C.DEFAULT_PLAYER_LIVE_MIN_SPEED
            val effectiveMaxSpeed = maxSpeed ?: C.DEFAULT_PLAYER_LIVE_MAX_SPEED
            val validSpeedPair = effectiveMinSpeed < effectiveMaxSpeed

            return LivePlaybackSettings(
                mode = LivePlaybackMode.fromPreferences(preferences),
                targetOffsetMs = preferences.getString(C.PLAYER_LIVE_TARGET_OFFSET, null)
                    ?.toLongOrNull()
                    ?.takeIf { it in TARGET_OFFSET_RANGE },
                buffers = PlaybackBufferOverrides(
                    minBufferMs = minBuffer.takeIf { validBufferPair },
                    maxBufferMs = maxBuffer.takeIf { validBufferPair },
                    bufferForPlaybackMs = preferences.readInt(C.PLAYER_BUFFER_PLAYBACK, START_BUFFER_RANGE),
                    bufferForPlaybackAfterRebufferMs = preferences.readInt(C.PLAYER_BUFFER_REBUFFER, REBUFFER_RANGE),
                ),
                minPlaybackSpeed = if (validSpeedPair) effectiveMinSpeed else C.DEFAULT_PLAYER_LIVE_MIN_SPEED,
                maxPlaybackSpeed = if (validSpeedPair) effectiveMaxSpeed else C.DEFAULT_PLAYER_LIVE_MAX_SPEED,
            )
        }

        private val MIN_BUFFER_RANGE = 250..60_000
        private val MAX_BUFFER_RANGE = 500..120_000
        private val START_BUFFER_RANGE = 100..60_000
        private val REBUFFER_RANGE = 100..60_000
        private val TARGET_OFFSET_RANGE = 500L..30_000L
        private val MIN_SPEED_RANGE = 0.5f..1.0f
        private val MAX_SPEED_RANGE = 1.0f..1.5f
    }
}

private fun SharedPreferences.readInt(key: String, range: IntRange): Int? =
    getString(key, null)?.toIntOrNull()?.takeIf { it in range }

private fun SharedPreferences.readFloat(key: String, range: ClosedFloatingPointRange<Float>): Float? =
    getString(key, null)?.trim()?.replace(',', '.')?.toFloatOrNull()?.takeIf { it.isFinite() && it in range }
