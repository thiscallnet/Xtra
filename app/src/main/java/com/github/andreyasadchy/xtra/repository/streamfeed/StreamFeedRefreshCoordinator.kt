package com.github.andreyasadchy.xtra.repository.streamfeed

import android.os.SystemClock
import android.util.Log
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.repository.TwitchApiException
import com.github.andreyasadchy.xtra.repository.datasource.StreamFeedPage
import com.github.andreyasadchy.xtra.repository.datasource.StreamFeedCursor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

data class StreamRefreshResult(
    val feedKey: StreamFeedKey,
    val reason: RefreshReason,
    val decision: RefreshDecision,
    val cacheAgeMs: Long?,
    val itemCount: Int = 0,
    val durationMs: Long = 0L,
    val joined: Boolean = false,
)

data class StreamAppendResult(
    val page: StreamFeedPage?,
    val endOfPaginationReached: Boolean,
)

/**
 * App-scoped single-flight and freshness gate for all stream-feed triggers.
 * The network operation is deliberately outside the UI/Paging layer; every
 * successful result is committed through [StreamFeedCache].
 */
class StreamFeedRefreshCoordinator(
    private val cache: StreamFeedCacheStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val debugLoggingEnabled: Boolean = BuildConfig.DEBUG,
) {
    private val refreshFlights = StreamFeedSingleFlight<StreamRefreshResult>(scope)
    private val appendFlights = StreamFeedSingleFlight<StreamAppendResult>(scope)
    private val feedLocks = ConcurrentHashMap<String, Mutex>()

    @Volatile
    private var visibleFeed: StreamFeedSpec? = null

    @Volatile
    private var livePlaybackStartedElapsedMs: Long? = null

    @Volatile
    private var playerFullscreen = false

    fun setVisibleFeed(spec: StreamFeedSpec) {
        visibleFeed = spec
    }

    fun clearVisibleFeed(feedKey: StreamFeedKey) {
        if (visibleFeed?.key == feedKey) {
            visibleFeed = null
        }
    }

    fun currentVisibleFeed(): StreamFeedSpec? = visibleFeed

    suspend fun maybeRefresh(spec: StreamFeedSpec, reason: RefreshReason): StreamRefreshResult {
        return requestRefresh(spec, reason, force = false)
    }

    suspend fun forceRefresh(spec: StreamFeedSpec, reason: RefreshReason): StreamRefreshResult {
        return requestRefresh(spec, reason, force = true)
    }

    private suspend fun requestRefresh(
        spec: StreamFeedSpec,
        reason: RefreshReason,
        force: Boolean,
    ): StreamRefreshResult {
        if (force) {
            StreamThumbnailRefreshSignal.requestForceRefresh()
        }
        val result = refreshFlights.run(spec.key.value) {
            executeRefresh(spec, reason, force)
        }
        if (result.joined) {
            debug(spec, reason, RefreshDecision.JOIN, null, "in-flight")
            return result.value.copy(joined = true)
        }
        return result.value
    }

    private suspend fun executeRefresh(
        spec: StreamFeedSpec,
        reason: RefreshReason,
        force: Boolean,
    ): StreamRefreshResult {
        val lock = feedLocks.getOrPut(spec.key.value) { Mutex() }
        return lock.withLock {
            val now = wallClockMs()
            val state = cache.state(spec.key)
            val age = StreamFeedFreshnessPolicy.cacheAge(now, state?.lastSuccessAt)
            val rateLimitUntil = state?.rateLimitUntil ?: 0L
            val failureBackoffUntil = state?.failureBackoffUntil ?: 0L
            val lastAttemptAge = state?.lastAttemptAt?.let { maxOf(0L, now - it) }
            val hasLegacyCursorWithoutApi = state?.nextCursor?.isNotBlank() == true && state.nextCursorApi.isNullOrBlank()

            when (refreshDecision(
                now,
                state?.lastSuccessAt,
                state?.lastAttemptAt,
                failureBackoffUntil,
                rateLimitUntil,
                force,
                ignoreFreshness = hasLegacyCursorWithoutApi,
            )) {
                RefreshDecision.SKIP_BACKOFF -> {
                    debug(spec, reason, RefreshDecision.SKIP_BACKOFF, age, "backoff")
                    return@withLock StreamRefreshResult(spec.key, reason, RefreshDecision.SKIP_BACKOFF, age)
                }
                RefreshDecision.SKIP_FRESH -> {
                    debug(spec, reason, RefreshDecision.SKIP_FRESH, age, "ttl")
                    return@withLock StreamRefreshResult(spec.key, reason, RefreshDecision.SKIP_FRESH, age)
                }
                RefreshDecision.SKIP_DEBOUNCED -> {
                    debug(spec, reason, RefreshDecision.SKIP_DEBOUNCED, age, "attempt-age=$lastAttemptAge")
                    return@withLock StreamRefreshResult(spec.key, reason, RefreshDecision.SKIP_DEBOUNCED, age)
                }
                RefreshDecision.REFRESH -> Unit
                RefreshDecision.JOIN -> error("JOIN is handled before the feed lock")
            }

            val preserveTail = reason != RefreshReason.USER_PULL &&
                    reason != RefreshReason.FILTER_CHANGED &&
                    reason != RefreshReason.FOLLOW_STATE_CHANGED
            val cachedItemCount = if (preserveTail) cache.itemCount(spec.key) else 0
            val started = elapsedRealtimeMs()
            cache.markAttempt(spec.key, now)
            try {
                val page = spec.loader.load(cursor = null)
                val completedAt = wallClockMs()
                cache.replaceAfterRefresh(
                    spec.key,
                    page,
                    completedAt,
                    preserveTail = preserveTail,
                    pruneStaleOnEnd = !preserveTail,
                )
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
                            while (prefetchedPages < StreamFeedFreshnessPolicy.MAX_AUTOMATIC_TAIL_PREFETCH_PAGES) {
                                prefetchedPages++
                                if (append(spec, speculative = true).endOfPaginationReached) break
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            debug(
                                spec,
                                reason,
                                RefreshDecision.REFRESH,
                                age,
                                "tail-prefetch-failure=${error::class.simpleName}:${error.message}",
                            )
                        }
                    }
                }
                cache.cleanup(completedAt)
                val duration = elapsedRealtimeMs() - started
                debug(spec, reason, RefreshDecision.REFRESH, age, "success items=${page.items.size} duration=$duration")
                StreamRefreshResult(spec.key, reason, RefreshDecision.REFRESH, age, page.items.size, duration)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val failedAt = wallClockMs()
                val limitedUntil = rateLimitUntil(error, failedAt)
                val failureUntil = if (limitedUntil == null) {
                    failedAt + StreamFeedFreshnessPolicy.AUTOMATIC_FAILURE_BACKOFF_MS
                } else {
                    null
                }
                cache.recordFailure(spec.key, failedAt, failureUntil, limitedUntil)
                debug(spec, reason, RefreshDecision.REFRESH, age, "failure=${error::class.simpleName}:${error.message}")
                throw error
            }
        }
    }

    suspend fun append(spec: StreamFeedSpec): StreamAppendResult {
        return append(spec, speculative = false)
    }

    private suspend fun append(spec: StreamFeedSpec, speculative: Boolean): StreamAppendResult {
        // A user-driven append must not join an optional prefetch that is
        // about to fail. Both paths still serialize through feedLocks, so
        // there is never more than one append request on the wire.
        val flightKey = if (speculative) {
            "speculative:${spec.key.value}"
        } else {
            spec.key.value
        }
        return appendFlights.run(flightKey) {
            executeAppend(spec, speculative)
        }.value
    }

    private suspend fun executeAppend(spec: StreamFeedSpec, speculative: Boolean): StreamAppendResult {
        refreshFlights.awaitIfRunning(spec.key.value)
        val lock = feedLocks.getOrPut(spec.key.value) { Mutex() }
        return lock.withLock {
            val now = wallClockMs()
            val state = cache.state(spec.key)
            val cursor = state?.nextCursor
                ?.takeIf { it.isNotBlank() }
                ?.let { value -> state.nextCursorApi?.let { api -> StreamFeedCursor(api, value) } }
            if (cursor == null) {
                if (!speculative) {
                    cache.pruneStaleGeneration(spec.key)
                }
                return@withLock StreamAppendResult(null, endOfPaginationReached = true)
            }
            val blockedUntil = maxOf(state.rateLimitUntil ?: 0L, state.failureBackoffUntil ?: 0L)
            if (blockedUntil > now) {
                throw IllegalStateException("Stream feed refresh is backed off")
            }
            val started = elapsedRealtimeMs()
            cache.markAttempt(spec.key, now)
            try {
                val page = spec.loader.load(cursor)
                val completedAt = wallClockMs()
                cache.appendPage(
                    spec.key,
                    page,
                    completedAt,
                    pruneStaleOnEnd = !speculative,
                )
                cache.cleanup(completedAt)
                debug(spec, RefreshReason.SCREEN_VISIBLE, RefreshDecision.REFRESH, null, "append items=${page.items.size} duration=${elapsedRealtimeMs() - started}")
                StreamAppendResult(page, page.nextCursor == null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val failedAt = wallClockMs()
                val limitedUntil = rateLimitUntil(error, failedAt)
                cache.recordFailure(
                    spec.key,
                    failedAt,
                    if (limitedUntil == null && !speculative) {
                        failedAt + StreamFeedFreshnessPolicy.AUTOMATIC_FAILURE_BACKOFF_MS
                    } else {
                        null
                    },
                    limitedUntil,
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
    ): Boolean {
        return StreamFeedFreshnessPolicy.MAX_AUTOMATIC_TAIL_PREFETCH_PAGES > 0 &&
                reason != RefreshReason.BACKGROUND_PREWARM &&
                preserveTail &&
                cachedItemCount > firstPageItemCount &&
                nextCursorPresent
    }

    fun onAppForeground(awayMs: Long) {
        if (!StreamFeedFreshnessPolicy.backgroundWasMeaningful(awayMs)) return
        val spec = visibleFeed ?: return
        scope.launch {
            runCatching { maybeRefresh(spec, RefreshReason.APP_FOREGROUND) }
        }
    }

    fun onNetworkRestored() {
        val spec = visibleFeed ?: return
        scope.launch {
            runCatching { forceRefresh(spec, RefreshReason.NETWORK_RESTORED) }
        }
    }

    val isPlayerFullscreen: Boolean
        get() = playerFullscreen

    /** Mark any fullscreen player active; only a live stream starts the freshness timer. */
    fun playbackEntered(isLive: Boolean = true) {
        playerFullscreen = true
        if (isLive && livePlaybackStartedElapsedMs == null) {
            livePlaybackStartedElapsedMs = elapsedRealtimeMs()
        } else if (!isLive) {
            livePlaybackStartedElapsedMs = null
        }
    }

    /** Switch content while keeping the player fullscreen. */
    fun playbackChanged(isLive: Boolean) {
        playerFullscreen = true
        if (isLive) {
            if (livePlaybackStartedElapsedMs == null) {
                livePlaybackStartedElapsedMs = elapsedRealtimeMs()
            }
        } else {
            // Do not count VOD/clip/offline viewing as live-stream viewing and
            // do not pretend the user returned to the browsing screen.
            livePlaybackStartedElapsedMs = null
        }
    }

    fun playbackReturned() {
        playerFullscreen = false
        val started = livePlaybackStartedElapsedMs ?: return
        livePlaybackStartedElapsedMs = null
        val viewedMs = (elapsedRealtimeMs() - started).coerceAtLeast(0L)
        if (!StreamFeedFreshnessPolicy.playbackWasMeaningful(viewedMs)) return
        val spec = visibleFeed ?: return
        scope.launch {
            runCatching { maybeRefresh(spec, RefreshReason.PLAYBACK_RETURN) }
        }
    }

    /** Mark followed data stale without deleting it, then refresh only the visible variant. */
    fun invalidateFollowedFeeds() {
        scope.launch {
            val now = wallClockMs()
            cache.invalidatePrefix("followed:", now)
            visibleFeed?.takeIf { it.key.value.startsWith("followed:") }?.let {
                runCatching { forceRefresh(it, RefreshReason.FOLLOW_STATE_CHANGED) }
            }
        }
    }

    private fun rateLimitUntil(error: Exception, nowMs: Long): Long? {
        val twitchError = error as? TwitchApiException
        if (twitchError == null ||
            (twitchError.statusCode != 429 && twitchError.rateLimitRemaining != 0L)
        ) {
            return null
        }
        val reset = twitchError.rateLimitResetEpochSeconds
        if (reset != null) {
            return maxOf(
                nowMs + StreamFeedFreshnessPolicy.RATE_LIMIT_SAFETY_MARGIN_MS,
                reset * 1_000L + StreamFeedFreshnessPolicy.RATE_LIMIT_SAFETY_MARGIN_MS,
            )
        }
        return if (twitchError.statusCode == 429) {
            nowMs + 60_000L + StreamFeedFreshnessPolicy.RATE_LIMIT_SAFETY_MARGIN_MS
        } else null
    }

    private fun debug(
        spec: StreamFeedSpec,
        reason: RefreshReason,
        decision: RefreshDecision,
        cacheAgeMs: Long?,
        details: String,
    ) {
        if (debugLoggingEnabled) {
            Log.d(
                "StreamFeedRefresh",
                "feed=${spec.key.value} reason=$reason age=${cacheAgeMs ?: "missing"} decision=$decision $details",
            )
        }
    }
}
