package com.github.andreyasadchy.xtra.ui.multiview.playback

/**
 * Pure state machine for Smart quality recovery. Keeping this separate from
 * ExoPlayer makes the important hysteresis rules testable without an Android
 * player or a network connection.
 */
data class MultiviewQualityRecoveryState(
    val hasReachedReady: Boolean = false,
    val downgradeLevel: Int = 0,
    val resourcePressure: Boolean = false,
    val rebufferTimes: List<Long> = emptyList(),
    val lastInstabilityAt: Long = 0L,
    val lastDowngradeAt: Long = 0L,
)

object MultiviewQualityRecovery {
    const val REBUFFER_THRESHOLD = 3
    const val REBUFFER_WINDOW_MS = 60_000L
    const val DOWNGRADE_COOLDOWN_MS = 30_000L
    const val STABLE_PLAYBACK_MS = 180_000L
    const val MAX_DOWNGRADE_LEVEL = 2

    fun onReady(state: MultiviewQualityRecoveryState): MultiviewQualityRecoveryState {
        return state.copy(hasReachedReady = true)
    }

    fun onBuffering(
        state: MultiviewQualityRecoveryState,
        now: Long,
    ): MultiviewQualityRecoveryState {
        if (!state.hasReachedReady) return state

        val recent = (state.rebufferTimes + now)
            .filter { now - it <= REBUFFER_WINDOW_MS }
        val canDowngrade = state.lastDowngradeAt == 0L ||
            now - state.lastDowngradeAt >= DOWNGRADE_COOLDOWN_MS
        return if (recent.size >= REBUFFER_THRESHOLD && canDowngrade) {
            state.copy(
                downgradeLevel = (state.downgradeLevel + 1).coerceAtMost(MAX_DOWNGRADE_LEVEL),
                rebufferTimes = emptyList(),
                lastInstabilityAt = now,
                lastDowngradeAt = now,
            )
        } else {
            state.copy(
                rebufferTimes = recent,
                lastInstabilityAt = now,
            )
        }
    }

    fun onResourceFailure(
        state: MultiviewQualityRecoveryState,
        now: Long,
    ): MultiviewQualityRecoveryState {
        return state.copy(
            downgradeLevel = (state.downgradeLevel + 1).coerceAtMost(MAX_DOWNGRADE_LEVEL),
            resourcePressure = true,
            rebufferTimes = emptyList(),
            lastInstabilityAt = now,
            lastDowngradeAt = now,
        )
    }

    fun onStablePlayback(
        state: MultiviewQualityRecoveryState,
        now: Long,
    ): MultiviewQualityRecoveryState {
        if (!state.hasReachedReady || state.lastInstabilityAt == 0L ||
            now - state.lastInstabilityAt < STABLE_PLAYBACK_MS
        ) {
            return state
        }
        return state.copy(
            downgradeLevel = (state.downgradeLevel - 1).coerceAtLeast(0),
            resourcePressure = false,
            rebufferTimes = emptyList(),
            lastInstabilityAt = now,
            lastDowngradeAt = 0L,
        )
    }
}
