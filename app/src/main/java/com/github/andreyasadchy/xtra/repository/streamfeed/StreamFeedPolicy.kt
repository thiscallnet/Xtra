package com.github.andreyasadchy.xtra.repository.streamfeed

import com.github.andreyasadchy.xtra.db.StreamFeedState
import com.github.andreyasadchy.xtra.repository.datasource.StreamFeedPageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A stable, credential-free identity for one stream feed variant. */
data class StreamFeedKey(val value: String) {
    override fun toString(): String = value

    companion object {
        fun top(sort: String, tags: Iterable<String>?, languages: Iterable<String>?): StreamFeedKey {
            return StreamFeedKey("top:${component(sort)}:tags=${list(tags)}:languages=${list(languages)}")
        }

        fun followed(accountId: String?): StreamFeedKey {
            val normalized = accountId?.trim()?.takeIf { it.isNotEmpty() }
            return if (normalized == null) {
                StreamFeedKey("followed:local")
            } else {
                StreamFeedKey("followed:account:${component(normalized)}")
            }
        }

        fun game(
            gameId: String?,
            gameSlug: String?,
            gameName: String?,
            sort: String,
            tags: Iterable<String>?,
            languages: Iterable<String>?,
        ): StreamFeedKey {
            val identity = gameId?.trim()?.takeIf { it.isNotEmpty() }?.let { "id:${component(it)}" }
                ?: gameSlug?.trim()?.takeIf { it.isNotEmpty() }?.let { "slug:${component(it)}" }
                ?: "name:${component(gameName.orEmpty())}"
            return StreamFeedKey(
                "game:$identity:${component(sort)}:tags=${list(tags)}:languages=${list(languages)}"
            )
        }

        internal fun canonicalValues(values: Iterable<String>?): List<String> {
            return values?.toList().orEmpty()
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
        }

        private fun list(values: Iterable<String>?): String {
            return canonicalValues(values).joinToString(",", transform = ::component)
        }

        /** Keep keys readable while preventing delimiters from becoming ambiguous. */
        private fun component(value: String): String {
            return buildString {
                value.trim().lowercase(Locale.ROOT).forEach { character ->
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
}

enum class RefreshReason {
    INITIAL,
    APP_FOREGROUND,
    SCREEN_VISIBLE,
    PLAYBACK_RETURN,
    NETWORK_RESTORED,
    USER_PULL,
    FOLLOW_STATE_CHANGED,
    FILTER_CHANGED,
    BACKGROUND_PREWARM,
}

enum class RefreshDecision {
    REFRESH,
    SKIP_FRESH,
    SKIP_DEBOUNCED,
    SKIP_BACKOFF,
    JOIN,
}

internal fun refreshDecision(
    nowMs: Long,
    lastSuccessAt: Long?,
    lastAttemptAt: Long?,
    failureBackoffUntil: Long?,
    rateLimitUntil: Long?,
    force: Boolean,
    ignoreFreshness: Boolean = false,
): RefreshDecision {
    if ((rateLimitUntil ?: 0L) > nowMs) return RefreshDecision.SKIP_BACKOFF
    if (!force && (failureBackoffUntil ?: 0L) > nowMs) return RefreshDecision.SKIP_BACKOFF
    if (!force && !ignoreFreshness && StreamFeedFreshnessPolicy.isFresh(nowMs, lastSuccessAt)) return RefreshDecision.SKIP_FRESH
    if (!force && lastAttemptAt?.let { (nowMs - it).coerceAtLeast(0L) < StreamFeedFreshnessPolicy.AUTO_REFRESH_DEBOUNCE_MS } == true) {
        return RefreshDecision.SKIP_DEBOUNCED
    }
    return RefreshDecision.REFRESH
}

internal fun shouldLaunchInitialRefresh(nowMs: Long, state: StreamFeedState?): Boolean {
    if (state == null) return true
    if ((state.rateLimitUntil ?: 0L) > nowMs || (state.failureBackoffUntil ?: 0L) > nowMs) return false
    if (state.nextCursor?.isNotBlank() == true && state.nextCursorApi.isNullOrBlank()) return true
    if (state.lastAttemptAt?.let { (nowMs - it).coerceAtLeast(0L) < StreamFeedFreshnessPolicy.AUTO_REFRESH_DEBOUNCE_MS } == true) {
        return false
    }
    return !StreamFeedFreshnessPolicy.isFresh(nowMs, state.lastSuccessAt)
}

internal data class SingleFlightResult<T>(val value: T, val joined: Boolean)

/** Small keyed single-flight primitive shared by all refresh triggers. */
internal class StreamFeedSingleFlight<T>(private val scope: CoroutineScope) {
    private val flights = ConcurrentHashMap<String, Deferred<T>>()

    suspend fun run(key: String, block: suspend () -> T): SingleFlightResult<T> {
        val existing = flights[key]
        if (existing != null) {
            return SingleFlightResult(existing.await(), joined = true)
        }
        var joined = false
        val deferred = synchronized(flights) {
            flights[key]?.also {
                joined = true
            } ?: scope.async(start = CoroutineStart.LAZY) { block() }.also { created ->
                flights[key] = created
                created.invokeOnCompletion { flights.remove(key, created) }
            }
        }
        deferred.start()
        return SingleFlightResult(deferred.await(), joined)
    }

    suspend fun awaitIfRunning(key: String) {
        flights[key]?.await()
    }
}

/** Central policy for volatile live-stream metadata. */
object StreamFeedFreshnessPolicy {
    const val LIVE_STREAM_SOFT_TTL_MS = 90_000L
    const val VISIBLE_REVALIDATION_INTERVAL_MS = 2 * 60_000L
    const val APP_BACKGROUND_THRESHOLD_MS = 30_000L
    const val PLAYBACK_RETURN_THRESHOLD_MS = 45_000L
    const val AUTO_REFRESH_DEBOUNCE_MS = 20_000L
    const val AUTOMATIC_FAILURE_BACKOFF_MS = 60_000L
    const val RATE_LIMIT_SAFETY_MARGIN_MS = 5_000L
    const val PREWARM_DEFAULT_DELAY_MS = 15 * 60_000L
    const val PREWARM_LEAD_TIME_MS = 2 * 60_000L
    const val PREWARM_MIN_DELAY_MS = 10 * 60_000L
    const val PREWARM_MAX_DELAY_MS = 6 * 60 * 60_000L
    /** Keep the retained tail useful without eagerly refetching a deep feed. */
    const val MAX_AUTOMATIC_TAIL_PREFETCH_PAGES = 1

    fun cacheAge(nowMs: Long, lastSuccessAt: Long?): Long? {
        return lastSuccessAt?.let { maxOf(0L, nowMs - it) }
    }

    fun isFresh(nowMs: Long, lastSuccessAt: Long?): Boolean {
        return cacheAge(nowMs, lastSuccessAt)?.let { it < LIVE_STREAM_SOFT_TTL_MS } == true
    }

    fun backgroundWasMeaningful(awayMs: Long): Boolean = awayMs >= APP_BACKGROUND_THRESHOLD_MS

    fun playbackWasMeaningful(viewedMs: Long): Boolean = viewedMs >= PLAYBACK_RETURN_THRESHOLD_MS

    fun prewarmDelayMs(predictedReturnMs: Long?): Long {
        if (predictedReturnMs == null) return PREWARM_DEFAULT_DELAY_MS
        val predicted = predictedReturnMs
        return (predicted - PREWARM_LEAD_TIME_MS).coerceIn(PREWARM_MIN_DELAY_MS, PREWARM_MAX_DELAY_MS)
    }

    fun updateReturnIntervalEwma(previousMs: Long?, sampleMs: Long): Long {
        val sample = sampleMs.coerceAtLeast(0L)
        return if (previousMs == null) {
            sample
        } else {
            (previousMs * 0.5 + sample * 0.5).toLong()
        }
    }
}

data class StreamFeedSpec(
    val key: StreamFeedKey,
    val loader: StreamFeedPageLoader,
)
