package com.github.andreyasadchy.xtra.repository.gamefeed

import com.github.andreyasadchy.xtra.db.GameFeedState
import com.github.andreyasadchy.xtra.repository.streamfeed.RefreshReason
import java.util.Locale

data class GameFeedKey(val value: String) {
    override fun toString(): String = value

    companion object {
        fun top(tags: Iterable<String>?): GameFeedKey = GameFeedKey("games:tags=${list(tags)}")

        private fun list(values: Iterable<String>?): String = values?.toList().orEmpty()
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .joinToString(",") { component(it) }

        private fun component(value: String): String = buildString {
            value.forEach { character ->
                if (character.isLetterOrDigit() || character == '-' || character == '_' || character == '.') {
                    append(character)
                } else {
                    append('%')
                    append(character.code.toString(16).padStart(2, '0'))
                }
            }
        }
    }
}

data class GameFeedSpec(
    val key: GameFeedKey,
    val loader: com.github.andreyasadchy.xtra.repository.datasource.GameFeedPageLoader,
)

enum class GameRefreshDecision {
    REFRESH,
    SKIP_FRESH,
    SKIP_DEBOUNCED,
    SKIP_BACKOFF,
    JOIN,
}

fun gameRefreshDecision(
    nowMs: Long,
    lastSuccessAt: Long?,
    lastAttemptAt: Long?,
    failureBackoffUntil: Long?,
    rateLimitUntil: Long?,
    force: Boolean,
): GameRefreshDecision {
    if ((rateLimitUntil ?: 0L) > nowMs) return GameRefreshDecision.SKIP_BACKOFF
    if (!force && (failureBackoffUntil ?: 0L) > nowMs) return GameRefreshDecision.SKIP_BACKOFF
    if (!force && GameFeedFreshnessPolicy.isFresh(nowMs, lastSuccessAt)) return GameRefreshDecision.SKIP_FRESH
    if (!force && lastAttemptAt?.let { nowMs - it < GameFeedFreshnessPolicy.AUTO_REFRESH_DEBOUNCE_MS } == true) {
        return GameRefreshDecision.SKIP_DEBOUNCED
    }
    return GameRefreshDecision.REFRESH
}

fun shouldLaunchInitialGameRefresh(nowMs: Long, state: GameFeedState?): Boolean {
    if (state == null) return true
    if ((state.rateLimitUntil ?: 0L) > nowMs || (state.failureBackoffUntil ?: 0L) > nowMs) return false
    if (state.lastAttemptAt?.let { nowMs - it < GameFeedFreshnessPolicy.AUTO_REFRESH_DEBOUNCE_MS } == true) return false
    return !GameFeedFreshnessPolicy.isFresh(nowMs, state.lastSuccessAt)
}

object GameFeedFreshnessPolicy {
    const val SOFT_TTL_MS = 5 * 60_000L
    const val VISIBLE_REVALIDATION_INTERVAL_MS = SOFT_TTL_MS
    const val FOREGROUND_THRESHOLD_MS = 60_000L
    const val AUTO_REFRESH_DEBOUNCE_MS = 20_000L
    const val AUTOMATIC_FAILURE_BACKOFF_MS = 60_000L
    const val RATE_LIMIT_SAFETY_MARGIN_MS = 5_000L
    const val MAX_AUTOMATIC_TAIL_PREFETCH_PAGES = 1
    const val MAX_RETAINED_STALE_GENERATIONS = 1
    const val MAX_RETAINED_STALE_TAIL_AGE_MS = 7 * 24 * 60 * 60_000L

    fun isFresh(nowMs: Long, lastSuccessAt: Long?): Boolean {
        return lastSuccessAt?.let { nowMs - it < SOFT_TTL_MS } == true
    }

    fun meaningfulForeground(awayMs: Long): Boolean = awayMs >= FOREGROUND_THRESHOLD_MS
}

data class GameRefreshResult(
    val feedKey: GameFeedKey,
    val reason: RefreshReason,
    val decision: GameRefreshDecision,
)
