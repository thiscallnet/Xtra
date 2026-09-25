package com.github.andreyasadchy.xtra.ui.player

/** Tracks when an already-playing live source is eligible for stalled-buffer recovery. */
internal class LivePlaybackStallRecoveryState(
    private val stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    private var generation = 0L
    private var hasPlayed = false
    private var bufferingSinceMs: Long? = null
    private var attempts = 0
    private var recoveryInFlight = false

    @Synchronized
    fun currentGeneration(): Long = generation

    /** Starts a user initiated source or quality generation and clears its recovery budget. */
    @Synchronized
    fun beginUserGeneration(): Long {
        generation++
        hasPlayed = false
        bufferingSinceMs = null
        attempts = 0
        recoveryInFlight = false
        return generation
    }

    /** Starts a recovery generation while retaining the outage's bounded retry budget. */
    @Synchronized
    fun beginRecoveryGeneration(): Long {
        generation++
        hasPlayed = false
        bufferingSinceMs = null
        recoveryInFlight = true
        return generation
    }

    /** Call only after actual playback resumes, not on READY/onPrepared alone. */
    @Synchronized
    fun onPlaybackStarted(sourceGeneration: Long): Boolean {
        if (sourceGeneration != generation) return false
        hasPlayed = true
        bufferingSinceMs = null
        if (recoveryInFlight) {
            attempts = 0
            recoveryInFlight = false
        }
        return true
    }

    @Synchronized
    fun onBufferingChanged(
        sourceGeneration: Long,
        isBuffering: Boolean,
        nowMs: Long,
    ): Boolean {
        if (sourceGeneration != generation) return false
        if (!hasPlayed || recoveryInFlight) {
            bufferingSinceMs = null
            return false
        }
        if (isBuffering) {
            if (bufferingSinceMs == null) bufferingSinceMs = nowMs
            return true
        }
        bufferingSinceMs = null
        return false
    }

    /** Returns the attempt number once per continuous, post-startup buffering episode. */
    @Synchronized
    fun claimStalledRecovery(sourceGeneration: Long, nowMs: Long): Int? {
        val startedAt = bufferingSinceMs ?: return null
        if (sourceGeneration != generation || !hasPlayed || recoveryInFlight ||
            nowMs - startedAt < stallTimeoutMs || attempts >= maxAttempts
        ) {
            return null
        }
        attempts++
        recoveryInFlight = true
        bufferingSinceMs = null
        return attempts
    }

    /** Terminal errors share the same bounded budget as watchdog recoveries. */
    @Synchronized
    fun claimErrorRecovery(recoveryPending: Boolean = false): Int? {
        if (recoveryPending || attempts >= maxAttempts) return null
        attempts++
        recoveryInFlight = true
        bufferingSinceMs = null
        return attempts
    }

    @Synchronized
    fun isRecoveryExhausted(): Boolean = attempts >= maxAttempts

    @Synchronized
    fun recoveryAttempts(): Int = attempts

    companion object {
        const val DEFAULT_STALL_TIMEOUT_MS = 30_000L
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}
