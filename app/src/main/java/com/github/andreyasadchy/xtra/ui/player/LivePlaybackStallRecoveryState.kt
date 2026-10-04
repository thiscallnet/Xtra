package com.github.andreyasadchy.xtra.ui.player

/** Tracks retry and buffering state for an active live source. */
internal class LivePlaybackStallRecoveryState(
    private val stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
) {
    private var generation = 0L
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
        bufferingSinceMs = null
        attempts = 0
        recoveryInFlight = false
        endedRecoveryAttempts = 0
        playbackStartedAtMs = null
        return generation
    }

    /** Starts a recovery generation while retaining the outage's retry count. */
    @Synchronized
    fun beginRecoveryGeneration(): Long {
        generation++
        bufferingSinceMs = null
        recoveryInFlight = true
        return generation
    }

    /** Call only after actual playback resumes, not on READY/onPrepared alone. */
    @Synchronized
    fun onPlaybackStarted(sourceGeneration: Long, nowMs: Long? = null): Boolean {
        if (sourceGeneration != generation) return false
        bufferingSinceMs = null
        playbackStartedAtMs = nowMs
        recoveryInFlight = false
        return true
    }

    /** Releases a retry claim after the source request finishes. */
    @Synchronized
    fun finishRecoveryAttempt(sourceStarted: Boolean) {
        recoveryInFlight = false
        if (!sourceStarted) bufferingSinceMs = null
    }

    @Synchronized
    fun onBufferingChanged(
        sourceGeneration: Long,
        isBuffering: Boolean,
        nowMs: Long,
    ): Boolean {
        if (sourceGeneration != generation) return false
        if (isBuffering) {
            if (bufferingSinceMs == null) bufferingSinceMs = nowMs
            return true
        }
        bufferingSinceMs = null
        return false
    }

    /** Returns the attempt number once per continuous buffering episode. */
    @Synchronized
    fun claimStalledRecovery(sourceGeneration: Long, nowMs: Long): Int? {
        val startedAt = bufferingSinceMs ?: return null
        if (sourceGeneration != generation || recoveryInFlight ||
            nowMs - startedAt < stallTimeoutMs
        ) {
            return null
        }
        resetAttemptsAfterStablePlayback(nowMs)
        attempts++
        recoveryInFlight = true
        bufferingSinceMs = null
        return attempts
    }

    /** Terminal errors and stalled sources share one unbounded retry counter. */
    @Synchronized
    fun claimErrorRecovery(recoveryPending: Boolean = false, nowMs: Long? = null): Int? {
        if (recoveryPending || recoveryInFlight) return null
        resetAttemptsAfterStablePlayback(nowMs)
        attempts++
        recoveryInFlight = true
        bufferingSinceMs = null
        return attempts
    }

    @Synchronized
    private fun resetAttemptsAfterStablePlayback(nowMs: Long?) {
        val startedAt = playbackStartedAtMs ?: return
        val currentTime = nowMs ?: return
        if (currentTime >= startedAt && currentTime - startedAt >= STABLE_PLAYBACK_RESET_MS) {
            attempts = 0
        }
        playbackStartedAtMs = null
    }

    @Synchronized
    fun recoveryAttempts(): Int = attempts

    /** Claims a retry for a live item that reached STATE_ENDED. */
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

    companion object {
        const val DEFAULT_STALL_TIMEOUT_MS = 30_000L
        const val ENDED_RECOVERY_STABILITY_MS = 120_000L
        const val STABLE_PLAYBACK_RESET_MS = 120_000L
    }
}
