package com.github.andreyasadchy.xtra.ui.player

/**
 * Exponential retry state for a playback source that has not reached READY.
 *
 * A source replacement is not a successful recovery. The caller must reset
 * this state only after READY or when the user starts different content.
 */
internal class PlaybackRecoveryPolicy(
    private val initialDelayMs: Long = 500L,
    private val maximumAttempt: Int = 4,
) {

    var attempt: Int = 0
        private set

    fun nextDelayMs(): Long {
        val delay = initialDelayMs * (1L shl attempt.coerceAtMost(maximumAttempt))
        attempt = (attempt + 1).coerceAtMost(maximumAttempt)
        return delay
    }

    fun reset() {
        attempt = 0
    }
}
