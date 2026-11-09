package com.github.andreyasadchy.xtra.util

import android.content.SharedPreferences
import androidx.media3.common.MediaItem

enum class LivePlaybackMode(val preferenceValue: String) {
    MINIMUM_DELAY(C.PLAYER_LIVE_MODE_MINIMUM_DELAY),
    CUSTOM(C.PLAYER_LIVE_MODE_CUSTOM),
    NORMAL(C.PLAYER_LIVE_MODE_NORMAL);

    val lowLatencyEnabled: Boolean
        get() = this != NORMAL

    companion object {
        fun fromPreferences(preferences: SharedPreferences): LivePlaybackMode {
            return when (preferences.getString(C.PLAYER_LIVE_MODE, null)) {
                C.PLAYER_LIVE_MODE_MINIMUM_DELAY -> MINIMUM_DELAY
                C.PLAYER_LIVE_MODE_CUSTOM, C.PLAYER_LIVE_MODE_ADAPTIVE -> CUSTOM
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

/** User-selected live settings. Only Custom uses the optional buffer and delay overrides. */
data class LivePlaybackSettings(
    val mode: LivePlaybackMode,
    val targetOffsetMs: Long?,
    val buffers: PlaybackBufferOverrides,
) {
    val lowLatencyEnabled: Boolean
        get() = mode.lowLatencyEnabled

    fun policies(): LivePlaybackPolicySet = LivePlaybackPolicies.forSettings(this)

    fun liveConfiguration(policy: LivePlaybackPolicy): MediaItem.LiveConfiguration =
        MediaItem.LiveConfiguration.Builder()
            .setTargetOffsetMs(policy.targetOffsetMs)
            .setMinPlaybackSpeed(1f)
            .setMaxPlaybackSpeed(1f)
            // Keep the selected target stable through rebuffers in every mode.
            .setMinOffsetMs(policy.targetOffsetMs)
            .setMaxOffsetMs(policy.targetOffsetMs)
            .build()

    companion object {
        fun defaults(): LivePlaybackSettings = LivePlaybackSettings(
            mode = LivePlaybackMode.MINIMUM_DELAY,
            targetOffsetMs = null,
            buffers = PlaybackBufferOverrides(),
        )

        fun fromPreferences(preferences: SharedPreferences): LivePlaybackSettings {
            val minBuffer = preferences.readInt(C.PLAYER_BUFFER_MIN, MIN_BUFFER_RANGE)
            val maxBuffer = preferences.readInt(C.PLAYER_BUFFER_MAX, MAX_BUFFER_RANGE)
            val validBufferPair = minBuffer == null || maxBuffer == null || minBuffer < maxBuffer
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
            )
        }

        private val MIN_BUFFER_RANGE = 250..60_000
        private val MAX_BUFFER_RANGE = 500..120_000
        private val START_BUFFER_RANGE = 100..60_000
        private val REBUFFER_RANGE = 100..60_000
        private val TARGET_OFFSET_RANGE = 500L..30_000L
    }
}

private fun SharedPreferences.readInt(key: String, range: IntRange): Int? =
    getString(key, null)?.toIntOrNull()?.takeIf { it in range }
