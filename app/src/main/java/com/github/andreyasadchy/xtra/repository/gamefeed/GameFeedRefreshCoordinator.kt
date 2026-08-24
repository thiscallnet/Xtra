package com.github.andreyasadchy.xtra.repository.gamefeed

import com.github.andreyasadchy.xtra.repository.TwitchApiException
import com.github.andreyasadchy.xtra.repository.datasource.GameFeedCursor
import com.github.andreyasadchy.xtra.repository.datasource.GameFeedPage
import com.github.andreyasadchy.xtra.repository.streamfeed.RefreshReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

private data class Flight<T>(val value: kotlinx.coroutines.Deferred<T>, val joined: Boolean)

private class SingleFlight<T>(private val scope: CoroutineScope) {
    private val flights = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<T>>()

    suspend fun run(key: String, block: suspend () -> T): Flight<T> {
        val existing = flights[key]
        if (existing != null) return Flight(existing, joined = true)
        var joined = false
        val deferred = synchronized(flights) {
            flights[key]?.also { joined = true } ?: scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) { block() }.also { created ->
                flights[key] = created
                created.invokeOnCompletion { flights.remove(key, created) }
            }
        }
        deferred.start()
        return Flight(deferred, joined)
    }
}

data class GameAppendResult(
    val page: GameFeedPage?,
    val endOfPaginationReached: Boolean,
)

class GameFeedRefreshCoordinator(
    private val cache: GameFeedCacheStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val refreshFlights = SingleFlight<GameRefreshResult>(scope)
    private val appendFlights = SingleFlight<GameAppendResult>(scope)
    private val feedLocks = ConcurrentHashMap<String, Mutex>()

    @Volatile
    private var visibleFeed: GameFeedSpec? = null

    fun setVisibleFeed(spec: GameFeedSpec) {
        visibleFeed = spec
    }

    fun clearVisibleFeed(feedKey: GameFeedKey) {
        if (visibleFeed?.key == feedKey) visibleFeed = null
    }

    fun onAppForeground(awayMs: Long) {
        if (!GameFeedFreshnessPolicy.meaningfulForeground(awayMs)) return
        visibleFeed?.let { spec ->
            scope.launch {
                runCatching { maybeRefresh(spec, RefreshReason.APP_FOREGROUND) }
            }
        }
    }

    suspend fun maybeRefresh(spec: GameFeedSpec, reason: RefreshReason): GameRefreshResult = requestRefresh(spec, reason, force = false)

    suspend fun forceRefresh(spec: GameFeedSpec, reason: RefreshReason): GameRefreshResult = requestRefresh(spec, reason, force = true)

    private suspend fun requestRefresh(spec: GameFeedSpec, reason: RefreshReason, force: Boolean): GameRefreshResult {
        val flight = refreshFlights.run(spec.key.value) { executeRefresh(spec, reason, force) }
        return flight.value.await().let { if (flight.joined) it.copy(decision = GameRefreshDecision.JOIN) else it }
    }

    private suspend fun executeRefresh(spec: GameFeedSpec, reason: RefreshReason, force: Boolean): GameRefreshResult {
        val lock = feedLocks.getOrPut(spec.key.value) { Mutex() }
        return lock.withLock {
            val now = wallClockMs()
            val state = cache.state(spec.key)
            when (gameRefreshDecision(now, state?.lastSuccessAt, state?.lastAttemptAt, state?.failureBackoffUntil, state?.rateLimitUntil, force)) {
                GameRefreshDecision.SKIP_BACKOFF -> return@withLock GameRefreshResult(spec.key, reason, GameRefreshDecision.SKIP_BACKOFF)
                GameRefreshDecision.SKIP_FRESH -> return@withLock GameRefreshResult(spec.key, reason, GameRefreshDecision.SKIP_FRESH)
                GameRefreshDecision.SKIP_DEBOUNCED -> return@withLock GameRefreshResult(spec.key, reason, GameRefreshDecision.SKIP_DEBOUNCED)
                GameRefreshDecision.REFRESH,
                GameRefreshDecision.JOIN,
                -> Unit
            }
            val preserveTail = reason != RefreshReason.USER_PULL && reason != RefreshReason.FILTER_CHANGED
            val cachedItemCount = if (preserveTail) cache.itemCount(spec.key) else 0
            cache.markAttempt(spec.key, now)
            try {
                val page = spec.loader.load(null)
                val completedAt = wallClockMs()
                cache.replaceAfterRefresh(spec.key, page, completedAt, preserveTail, pruneStaleOnEnd = !preserveTail)
                if (shouldPrefetchTail(
                        reason = reason,
                        preserveTail = preserveTail,
                        cachedItemCount = cachedItemCount,
                        firstPageItemCount = page.items.size,
                        nextCursorPresent = page.nextCursor != null,
                    )
                ) {
                    scope.launch {
                        try {
                            var prefetchedPages = 0
                            while (prefetchedPages < GameFeedFreshnessPolicy.MAX_AUTOMATIC_TAIL_PREFETCH_PAGES) {
                                prefetchedPages++
                                if (append(spec, speculative = true).endOfPaginationReached) break
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            // Tail prefetch is opportunistic; the refreshed first page remains valid.
                        }
                    }
                }
                cache.cleanup(completedAt)
                GameRefreshResult(spec.key, reason, GameRefreshDecision.REFRESH)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val failedAt = wallClockMs()
                val limitedUntil = rateLimitUntil(error, failedAt)
                cache.recordFailure(
                    spec.key,
                    failedAt,
                    failureBackoffUntil = if (limitedUntil == null) failedAt + GameFeedFreshnessPolicy.AUTOMATIC_FAILURE_BACKOFF_MS else null,
                    rateLimitUntil = limitedUntil,
                )
                throw error
            }
        }
    }

    suspend fun append(spec: GameFeedSpec): GameAppendResult {
        return append(spec, speculative = false)
    }

    private suspend fun append(spec: GameFeedSpec, speculative: Boolean): GameAppendResult {
        val flightKey = if (speculative) "speculative:${spec.key.value}" else spec.key.value
        val flight = appendFlights.run(flightKey) { executeAppend(spec, speculative) }
        return flight.value.await()
    }

    private suspend fun executeAppend(spec: GameFeedSpec, speculative: Boolean): GameAppendResult {
        val lock = feedLocks.getOrPut(spec.key.value) { Mutex() }
        return lock.withLock {
            val state = cache.state(spec.key)
            val cursor = state?.nextCursor?.takeIf { it.isNotBlank() }?.let { value ->
                state.nextCursorApi?.let { api -> GameFeedCursor(api, value) }
            }
            if (cursor == null) {
                if (!speculative) cache.pruneStaleGeneration(spec.key)
                return@withLock GameAppendResult(null, endOfPaginationReached = true)
            }
            val now = wallClockMs()
            val blockedUntil = maxOf(state.rateLimitUntil ?: 0L, state.failureBackoffUntil ?: 0L)
            if (blockedUntil > now) throw IllegalStateException("Game feed refresh is backed off")
            cache.markAttempt(spec.key, now)
            try {
                val page = spec.loader.load(cursor)
                cache.appendPage(spec.key, page, wallClockMs(), pruneStaleOnEnd = !speculative)
                GameAppendResult(page, page.nextCursor == null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val failedAt = wallClockMs()
                val limitedUntil = rateLimitUntil(error, failedAt)
                cache.recordFailure(
                    spec.key,
                    failedAt,
                    failureBackoffUntil = if (limitedUntil == null && !speculative) failedAt + GameFeedFreshnessPolicy.AUTOMATIC_FAILURE_BACKOFF_MS else null,
                    rateLimitUntil = limitedUntil,
                )
                throw error
            }
        }
    }

    private fun shouldPrefetchTail(
        reason: RefreshReason,
        preserveTail: Boolean,
        cachedItemCount: Int,
        firstPageItemCount: Int,
        nextCursorPresent: Boolean,
    ): Boolean = GameFeedFreshnessPolicy.MAX_AUTOMATIC_TAIL_PREFETCH_PAGES > 0 &&
        reason != RefreshReason.BACKGROUND_PREWARM &&
        preserveTail &&
        cachedItemCount > firstPageItemCount &&
        nextCursorPresent

    private fun rateLimitUntil(error: Exception, nowMs: Long): Long? {
        val twitchError = error as? TwitchApiException ?: return null
        if (twitchError.statusCode != 429 && twitchError.rateLimitRemaining != 0L) return null
        val reset = twitchError.rateLimitResetEpochSeconds
        return if (reset != null) {
            maxOf(nowMs + GameFeedFreshnessPolicy.RATE_LIMIT_SAFETY_MARGIN_MS, reset * 1_000L + GameFeedFreshnessPolicy.RATE_LIMIT_SAFETY_MARGIN_MS)
        } else if (twitchError.statusCode == 429) {
            nowMs + 60_000L + GameFeedFreshnessPolicy.RATE_LIMIT_SAFETY_MARGIN_MS
        } else null
    }
}
