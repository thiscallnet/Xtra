package com.github.andreyasadchy.xtra.ui.player

/** Tracks bounded recovery state for an active live source. */
internal class LivePlaybackStallRecoveryState(
    private val stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    private var generation = 0L
    private var hasPlayed = false
    private var bufferingSinceMs: Long? = null
    private var attempts = 0
    private var recoveryInFlight = false
    private var endedRecoveryAttempts = 0
    private var playbackStartedAtMs: Long? = null

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
        endedRecoveryAttempts = 0
        playbackStartedAtMs = null
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
    fun onPlaybackStarted(sourceGeneration: Long, nowMs: Long? = null): Boolean {
        if (sourceGeneration != generation) return false
        hasPlayed = true
        bufferingSinceMs = null
        playbackStartedAtMs = nowMs
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

    /** Claims one fresh-status-confirmed retry for a live item that reached STATE_ENDED. */
    @Synchronized
    fun claimEndedRecovery(sourceGeneration: Long, nowMs: Long): Int? {
        if (sourceGeneration != generation) return null
        val startedAtMs = playbackStartedAtMs
        if (startedAtMs != null && nowMs >= startedAtMs &&
            nowMs - startedAtMs >= ENDED_RECOVERY_STABILITY_MS
        ) {
            endedRecoveryAttempts = 0
        }
        playbackStartedAtMs = null
        if (endedRecoveryAttempts >= maxAttempts) return null
        endedRecoveryAttempts++
        return endedRecoveryAttempts
    }

    @Synchronized
    fun resetEndedRecoveryBudget() {
        endedRecoveryAttempts = 0
        playbackStartedAtMs = null
    }

    @Synchronized
    fun endedRecoveryAttempts(): Int = endedRecoveryAttempts

    @Synchronized
    fun isEndedRecoveryExhausted(): Boolean = endedRecoveryAttempts >= maxAttempts

    companion object {
        const val DEFAULT_STALL_TIMEOUT_MS = 30_000L
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val ENDED_RECOVERY_STABILITY_MS = 120_000L
    }
}
