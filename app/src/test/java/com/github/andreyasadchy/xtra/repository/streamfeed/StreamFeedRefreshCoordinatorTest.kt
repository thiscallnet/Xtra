package com.github.andreyasadchy.xtra.repository.streamfeed

import androidx.paging.PagingSource
import com.github.andreyasadchy.xtra.db.CachedStreamFeedItem
import com.github.andreyasadchy.xtra.db.StreamFeedState
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.TwitchApiException
import com.github.andreyasadchy.xtra.repository.datasource.StreamFeedCursor
import com.github.andreyasadchy.xtra.repository.datasource.StreamFeedPage
import com.github.andreyasadchy.xtra.repository.datasource.StreamFeedPageLoader
import com.github.andreyasadchy.xtra.util.C
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFeedRefreshCoordinatorTest {

    @Test
    fun failedRefreshDoesNotReplaceExistingRows() = runBlocking {
        val key = StreamFeedKey("top:test")
        val oldRows = refreshCachedItems(key.value, listOf(Stream(channelId = "old", title = "still visible")))
        val cache = FakeCache(key, oldRows, StreamFeedState(key.value, lastSuccessAt = 1L))
        val loader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
                error("offline")
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = StreamFeedRefreshCoordinator(cache, scope, { 1_000_000L }, { 1_000L }, false)

        assertTrue(runCatching { coordinator.maybeRefresh(StreamFeedSpec(key, loader), RefreshReason.APP_FOREGROUND) }.isFailure)
        assertEquals(oldRows, cache.rows)
        assertEquals(0, cache.replacementCount)
        assertEquals(1L, cache.currentState?.lastSuccessAt)
        scope.cancel()
    }

    @Test
    fun successfulRefreshReplacesEndedRowsAddsNewRowsAndCommitsOrder() = runBlocking {
        val key = StreamFeedKey("top:test")
        val oldRows = refreshCachedItems(key.value, listOf(Stream(channelId = "old")))
        val cache = FakeCache(key, oldRows, StreamFeedState(key.value, lastSuccessAt = 1L))
        val loader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?) = StreamFeedPage(
                listOf(Stream(channelId = "new-a"), Stream(channelId = "new-b")),
                nextCursor = null,
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val result = StreamFeedRefreshCoordinator(cache, scope, { 1_000_000L }, { 1_000L }, false).forceRefresh(
            StreamFeedSpec(key, loader),
            RefreshReason.USER_PULL,
        )

        assertEquals(RefreshDecision.REFRESH, result.decision)
        assertEquals(listOf("channel:new-a", "channel:new-b"), cache.rows.map { it.itemKey })
        assertFalse(cache.rows.any { it.itemKey == "channel:old" })
        assertEquals(listOf(0, 1), cache.rows.map { it.position })
        assertEquals(1, cache.replacementCount)
        scope.cancel()
    }

    @Test
    fun automaticRefreshHonorsPersistedBackoff() = runBlocking {
        val key = StreamFeedKey("top:test")
        val cache = FakeCache(
            key = key,
            rows = emptyList(),
            currentState = StreamFeedState(key.value, failureBackoffUntil = Long.MAX_VALUE),
        )
        var loads = 0
        val loader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
                loads++
                return StreamFeedPage(emptyList(), null)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val result = StreamFeedRefreshCoordinator(cache, scope, { 1_000_000L }, { 1_000L }, false).maybeRefresh(
            StreamFeedSpec(key, loader),
            RefreshReason.APP_FOREGROUND,
        )

        assertEquals(RefreshDecision.SKIP_BACKOFF, result.decision)
        assertEquals(0, loads)
        scope.cancel()
    }

    @Test
    fun integrityRetryReadsHeadersFromTheCurrentProvider() = runBlocking {
        val key = StreamFeedKey("top:integrity")
        val cache = FakeCache(key, emptyList(), StreamFeedState(key.value, lastSuccessAt = 0L))
        var currentHeaders = mapOf("X-Integrity" to "expired")
        val observedHeaders = mutableListOf<Map<String, String>>()
        val headers = { currentHeaders }
        val loader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
                observedHeaders += headers()
                if (headers()["X-Integrity"] == "expired") {
                    throw com.github.andreyasadchy.xtra.repository.datasource.StreamFeedIntegrityException()
                }
                return StreamFeedPage(emptyList(), null)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = StreamFeedRefreshCoordinator(cache, scope, { 1_000_000L }, { 1_000L }, false)
        val spec = StreamFeedSpec(key, loader)

        assertTrue(runCatching { coordinator.forceRefresh(spec, RefreshReason.INITIAL) }.isFailure)
        currentHeaders = mapOf("X-Integrity" to "fresh")
        assertEquals(RefreshDecision.REFRESH, coordinator.forceRefresh(spec, RefreshReason.USER_PULL).decision)
        assertEquals(listOf("expired", "fresh"), observedHeaders.map { it["X-Integrity"] })
        scope.cancel()
    }

    @Test
    fun networkRestorationBypassesTransportFailureBackoff() = runBlocking {
        val key = StreamFeedKey("top:network-restored")
        val cache = FakeCache(
            key = key,
            rows = emptyList(),
            currentState = StreamFeedState(key.value, failureBackoffUntil = Long.MAX_VALUE),
        )
        var loads = 0
        val loader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
                loads++
                return StreamFeedPage(emptyList(), null)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val result = StreamFeedRefreshCoordinator(cache, scope, { 1_000_000L }, { 1_000L }, false).forceRefresh(
            StreamFeedSpec(key, loader),
            RefreshReason.NETWORK_RESTORED,
        )

        assertEquals(RefreshDecision.REFRESH, result.decision)
        assertEquals(1, loads)
        scope.cancel()
    }

    @Test
    fun rateLimitResponsePersistsARefreshBlock() = runBlocking {
        val key = StreamFeedKey("top:test")
        val now = 1_000_000L
        val cache = FakeCache(
            key = key,
            rows = emptyList(),
            currentState = StreamFeedState(key.value, lastSuccessAt = 0L),
        )
        var loads = 0
        val loader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
                loads += 1
                throw TwitchApiException(429, 0L, message = "rate limited")
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = StreamFeedRefreshCoordinator(cache, scope, { now }, { 1_000L }, false)
        val spec = StreamFeedSpec(key, loader)

        assertTrue(runCatching { coordinator.maybeRefresh(spec, RefreshReason.APP_FOREGROUND) }.isFailure)
        assertTrue((cache.currentState?.rateLimitUntil ?: 0L) > now)
        assertEquals(RefreshDecision.SKIP_BACKOFF, coordinator.maybeRefresh(spec, RefreshReason.NETWORK_RESTORED).decision)
        assertEquals(1, loads)
        scope.cancel()
    }

    @Test
    fun shortPlaybackDoesNotRefreshButMeaningfulPlaybackDoes() = runBlocking {
        val key = StreamFeedKey("top:test")
        val cache = FakeCache(
            key = key,
            rows = emptyList(),
            currentState = StreamFeedState(key.value, lastSuccessAt = 0L),
        )
        val loaderStarted = CompletableDeferred<Unit>()
        val loader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
                loaderStarted.complete(Unit)
                return StreamFeedPage(emptyList(), null)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var elapsed = 0L
        val coordinator = StreamFeedRefreshCoordinator(cache, scope, { 1_000_000L }, { elapsed }, false)
        val spec = StreamFeedSpec(key, loader)
        coordinator.setVisibleFeed(spec)

        coordinator.playbackEntered()
        elapsed = StreamFeedFreshnessPolicy.PLAYBACK_RETURN_THRESHOLD_MS - 1
        coordinator.playbackReturned()
        assertFalse(loaderStarted.isCompleted)

        coordinator.playbackEntered()
        elapsed += StreamFeedFreshnessPolicy.PLAYBACK_RETURN_THRESHOLD_MS
        coordinator.playbackReturned()
        withTimeout(1_000L) { loaderStarted.await() }
        scope.cancel()
    }

    @Test
    fun playbackReturnCanBeRearmedAndMaximizeDoesNotResetTheViewingTimer() = runBlocking {
        val key = StreamFeedKey("top:playback-rearm")
        val cache = FakeCache(
            key = key,
            rows = emptyList(),
            currentState = StreamFeedState(key.value, lastSuccessAt = 0L),
        )
        val firstLoad = CompletableDeferred<Unit>()
        val secondLoad = CompletableDeferred<Unit>()
        var loads = 0
        val loader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
                loads++
                if (loads == 1) firstLoad.complete(Unit) else secondLoad.complete(Unit)
                return StreamFeedPage(emptyList(), null)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var now = 1_000_000L
        var elapsed = 0L
        val coordinator = StreamFeedRefreshCoordinator(cache, scope, { now }, { elapsed }, false)
        val spec = StreamFeedSpec(key, loader)
        coordinator.setVisibleFeed(spec)

        coordinator.playbackEntered()
        assertTrue(coordinator.isPlayerFullscreen)
        elapsed = StreamFeedFreshnessPolicy.PLAYBACK_RETURN_THRESHOLD_MS
        coordinator.playbackReturned()
        assertFalse(coordinator.isPlayerFullscreen)
        withTimeout(1_000L) { firstLoad.await() }

        now += StreamFeedFreshnessPolicy.LIVE_STREAM_SOFT_TTL_MS
        elapsed += 1L
        coordinator.playbackEntered()
        elapsed += StreamFeedFreshnessPolicy.PLAYBACK_RETURN_THRESHOLD_MS / 2
        // Maximizing the minimized player must keep the original session start.
        coordinator.playbackEntered()
        elapsed += StreamFeedFreshnessPolicy.PLAYBACK_RETURN_THRESHOLD_MS / 2
        coordinator.playbackReturned()
        withTimeout(1_000L) { secondLoad.await() }
        assertEquals(2, loads)
        scope.cancel()
    }

    @Test
    fun refreshCursorIsUsedByAppendAfterADeepListRefresh() = runBlocking {
        val key = StreamFeedKey("top:deep-scroll")
        val cache = FakeCache(
            key = key,
            rows = refreshCachedItems(key.value, listOf(Stream(channelId = "old"))),
            currentState = StreamFeedState(key.value, nextCursor = "old-cursor", lastSuccessAt = 0L),
        )
        val cursors = mutableListOf<StreamFeedCursor?>()
        val loader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
                cursors += cursor
                return if (cursor == null) {
                    StreamFeedPage(
                        listOf(Stream(channelId = "fresh")),
                        nextCursor = StreamFeedCursor(com.github.andreyasadchy.xtra.util.C.GQL, "new-cursor"),
                    )
                } else {
                    assertEquals("new-cursor", cursor.value)
                    StreamFeedPage(listOf(Stream(channelId = "appended")), nextCursor = null)
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = StreamFeedRefreshCoordinator(cache, scope, { 1_000_000L }, { 1_000L }, false)
        val spec = StreamFeedSpec(key, loader)

        coordinator.forceRefresh(spec, RefreshReason.USER_PULL)
        val append = coordinator.append(spec)

        assertTrue(append.endOfPaginationReached)
        assertEquals(listOf(null, StreamFeedCursor(com.github.andreyasadchy.xtra.util.C.GQL, "new-cursor")), cursors)
        assertEquals(listOf("channel:fresh", "channel:appended"), cache.rows.map { it.itemKey })
        scope.cancel()
    }

    @Test
    fun automaticRefreshKeepsDeepRowsUntilPaginationCompletes() = runBlocking {
        val key = StreamFeedKey("top:deep-scroll-automatic")
        val oldRows = refreshCachedItems(
            key.value,
            (0 until 90).map { Stream(channelId = "deep-$it") },
            generation = 1L,
        )
        val cache = FakeCache(
            key = key,
            rows = oldRows,
            currentState = StreamFeedState(
                feedKey = key.value,
                nextCursor = "old-page-4",
                nextCursorApi = com.github.andreyasadchy.xtra.util.C.GQL,
                lastSuccessAt = 0L,
                activeGeneration = 1L,
            ),
        )
        val appendCommitted = CompletableDeferred<Unit>()
        cache.appendCommitted = appendCommitted
        val cursors = mutableListOf<StreamFeedCursor?>()
        val loader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
                cursors += cursor
                return when (cursor?.value) {
                    null -> StreamFeedPage(
                        listOf(Stream(channelId = "fresh-0")),
                        StreamFeedCursor(com.github.andreyasadchy.xtra.util.C.GQL, "page-2"),
                    )
                    "page-2" -> StreamFeedPage(
                        listOf(Stream(channelId = "fresh-1")),
                        StreamFeedCursor(com.github.andreyasadchy.xtra.util.C.GQL, "page-3"),
                    )
                    else -> StreamFeedPage(listOf(Stream(channelId = "fresh-2")), null)
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = StreamFeedRefreshCoordinator(cache, scope, { 1_000_000L }, { 1_000L }, false)
        val spec = StreamFeedSpec(key, loader)

        coordinator.maybeRefresh(spec, RefreshReason.APP_FOREGROUND)
        withTimeout(1_000L) { appendCommitted.await() }

        assertTrue(cache.rows.any { it.itemKey == "channel:deep-80" })
        assertTrue(cache.rows.size > 1)
        coordinator.append(spec)
        assertFalse(cache.rows.any { it.itemKey == "channel:deep-80" })
        assertEquals(
            listOf(null, StreamFeedCursor(com.github.andreyasadchy.xtra.util.C.GQL, "page-2"), StreamFeedCursor(com.github.andreyasadchy.xtra.util.C.GQL, "page-3")),
            cursors,
        )
        scope.cancel()
    }

    @Test
    fun automaticRefreshPrefetchesOneFreshPageWhenRetainingADeepTail() = runBlocking {
        val key = StreamFeedKey("top:bounded-tail-prefetch")
        val cache = FakeCache(
            key = key,
            rows = refreshCachedItems(
                key.value,
                (0 until 90).map { Stream(channelId = "old-$it") },
                generation = 1L,
            ),
            currentState = StreamFeedState(
                feedKey = key.value,
                nextCursor = "old-page-4",
                nextCursorApi = C.GQL,
                lastSuccessAt = 0L,
                activeGeneration = 1L,
            ),
        )
        val appendCommitted = CompletableDeferred<Unit>()
        cache.appendCommitted = appendCommitted
        var loads = 0
        val loader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
                loads++
                return if (cursor == null) {
                    StreamFeedPage(
                        listOf(Stream(channelId = "fresh-0")),
                        StreamFeedCursor(C.GQL, "fresh-page-2"),
                    )
                } else {
                    assertEquals(StreamFeedCursor(C.GQL, "fresh-page-2"), cursor)
                    StreamFeedPage(listOf(Stream(channelId = "fresh-1")), StreamFeedCursor(C.GQL, "fresh-page-3"))
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = StreamFeedRefreshCoordinator(cache, scope, { 1_000_000L }, { 1_000L }, false)

        coordinator.maybeRefresh(StreamFeedSpec(key, loader), RefreshReason.APP_FOREGROUND)
        withTimeout(1_000L) { appendCommitted.await() }

        assertEquals(2, loads)
        assertTrue(cache.rows.any { it.itemKey == "channel:fresh-1" })
        assertTrue(cache.rows.any { it.itemKey == "channel:old-80" })
        scope.cancel()
    }

    @Test
    fun speculativeTailPrefetchFailureDoesNotBackoffImmediateAppend() = runBlocking {
        val key = StreamFeedKey("top:speculative-prefetch-failure")
        val cache = FakeCache(
            key = key,
            rows = refreshCachedItems(
                key.value,
                (0 until 90).map { Stream(channelId = "old-$it") },
                generation = 1L,
            ),
            currentState = StreamFeedState(
                feedKey = key.value,
                lastSuccessAt = 0L,
                activeGeneration = 1L,
            ),
        )
        val failureRecorded = CompletableDeferred<Unit>()
        cache.failureRecorded = failureRecorded
        var loads = 0
        val loader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
                loads++
                return when {
                    cursor == null -> StreamFeedPage(
                        listOf(Stream(channelId = "fresh-0")),
                        StreamFeedCursor(C.GQL, "fresh-page-2"),
                    )
                    loads == 2 -> throw IOException("temporary append failure")
                    else -> StreamFeedPage(listOf(Stream(channelId = "fresh-1")), null)
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = StreamFeedRefreshCoordinator(cache, scope, { 1_000_000L }, { 1_000L }, false)
        val spec = StreamFeedSpec(key, loader)

        coordinator.maybeRefresh(spec, RefreshReason.APP_FOREGROUND)
        withTimeout(1_000L) { failureRecorded.await() }

        val appendResult = withTimeout(1_000L) { coordinator.append(spec) }

        assertTrue(appendResult.endOfPaginationReached)
        assertEquals(3, loads)
        assertEquals(null, cache.currentState?.failureBackoffUntil)
        scope.cancel()
    }

    @Test
    fun speculativeTailPrefetchRateLimitStillBlocksImmediateAppend() = runBlocking {
        val key = StreamFeedKey("top:speculative-prefetch-rate-limit")
        val cache = FakeCache(
            key = key,
            rows = refreshCachedItems(
                key.value,
                (0 until 90).map { Stream(channelId = "old-$it") },
                generation = 1L,
            ),
            currentState = StreamFeedState(
                feedKey = key.value,
                lastSuccessAt = 0L,
                activeGeneration = 1L,
            ),
        )
        val failureRecorded = CompletableDeferred<Unit>()
        cache.failureRecorded = failureRecorded
        var loads = 0
        val loader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
                loads++
                return if (cursor == null) {
                    StreamFeedPage(
                        listOf(Stream(channelId = "fresh-0")),
                        StreamFeedCursor(C.GQL, "fresh-page-2"),
                    )
                } else {
                    throw TwitchApiException(429, 0L, message = "rate limited")
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = StreamFeedRefreshCoordinator(cache, scope, { 1_000_000L }, { 1_000L }, false)
        val spec = StreamFeedSpec(key, loader)

        coordinator.maybeRefresh(spec, RefreshReason.APP_FOREGROUND)
        withTimeout(1_000L) { failureRecorded.await() }

        assertTrue((cache.currentState?.rateLimitUntil ?: 0L) > 1_000_000L)
        assertEquals(null, cache.currentState?.failureBackoffUntil)
        assertTrue(runCatching { coordinator.append(spec) }.isFailure)
        assertEquals(2, loads)
        scope.cancel()
    }

    @Test
    fun automaticRefreshEofRetainsStaleRowsUntilRealAppendFinalizesGeneration() = runBlocking {
        val key = StreamFeedKey("top:automatic-eof")
        val cache = FakeCache(
            key = key,
            rows = refreshCachedItems(
                key.value,
                (0 until 90).map { Stream(channelId = "old-$it") },
                generation = 1L,
            ),
            currentState = StreamFeedState(key.value, lastSuccessAt = 0L, activeGeneration = 1L),
        )
        val loader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
                assertEquals(null, cursor)
                return StreamFeedPage(
                    (0 until 20).map { Stream(channelId = "fresh-$it") },
                    nextCursor = null,
                )
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = StreamFeedRefreshCoordinator(cache, scope, { 1_000_000L }, { 1_000L }, false)
        val spec = StreamFeedSpec(key, loader)

        coordinator.maybeRefresh(spec, RefreshReason.APP_FOREGROUND)

        assertTrue(cache.rows.any { it.itemKey == "channel:old-80" })
        assertEquals(null, cache.currentState?.nextCursor)
        assertTrue(coordinator.append(spec).endOfPaginationReached)
        assertFalse(cache.rows.any { it.itemKey == "channel:old-80" })
        assertEquals(20, cache.rows.size)
        scope.cancel()
    }

    @Test
    fun speculativeEofRetainsStaleRowsUntilRealAppendFinalizesGeneration() = runBlocking {
        val key = StreamFeedKey("top:speculative-eof")
        val cache = FakeCache(
            key = key,
            rows = refreshCachedItems(
                key.value,
                (0 until 90).map { Stream(channelId = "old-$it") },
                generation = 1L,
            ),
            currentState = StreamFeedState(key.value, lastSuccessAt = 0L, activeGeneration = 1L),
        )
        val appendCommitted = CompletableDeferred<Unit>()
        cache.appendCommitted = appendCommitted
        var loads = 0
        val loader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
                loads++
                return if (cursor == null) {
                    StreamFeedPage(
                        (0 until 30).map { Stream(channelId = "fresh-$it") },
                        StreamFeedCursor(C.GQL, "fresh-page-2"),
                    )
                } else {
                    assertEquals(StreamFeedCursor(C.GQL, "fresh-page-2"), cursor)
                    StreamFeedPage(
                        (30 until 50).map { Stream(channelId = "fresh-$it") },
                        nextCursor = null,
                    )
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = StreamFeedRefreshCoordinator(cache, scope, { 1_000_000L }, { 1_000L }, false)
        val spec = StreamFeedSpec(key, loader)

        coordinator.maybeRefresh(spec, RefreshReason.APP_FOREGROUND)
        withTimeout(1_000L) { appendCommitted.await() }

        assertEquals(2, loads)
        assertTrue(cache.rows.any { it.itemKey == "channel:old-80" })
        assertEquals(null, cache.currentState?.nextCursor)
        assertTrue(coordinator.append(spec).endOfPaginationReached)
        assertFalse(cache.rows.any { it.itemKey == "channel:old-80" })
        assertEquals(50, cache.rows.size)
        scope.cancel()
    }

    @Test
    fun appendUsesCursorApiPersistedByAnotherLoaderInstance() = runBlocking {
        val key = StreamFeedKey("top:cursor-affinity")
        val cache = FakeCache(
            key = key,
            rows = emptyList(),
            currentState = StreamFeedState(key.value, lastSuccessAt = 0L),
        )
        var appendCursor: StreamFeedCursor? = null
        val refreshLoader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
                return StreamFeedPage(
                    listOf(Stream(channelId = "fresh")),
                    StreamFeedCursor(C.HELIX, "helix-cursor"),
                )
            }
        }
        val appendLoader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
                appendCursor = cursor
                return StreamFeedPage(emptyList(), null)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = StreamFeedRefreshCoordinator(cache, scope, { 1_000_000L }, { 1_000L }, false)

        coordinator.forceRefresh(StreamFeedSpec(key, refreshLoader), RefreshReason.USER_PULL)
        coordinator.append(StreamFeedSpec(key, appendLoader))

        assertEquals(StreamFeedCursor(C.HELIX, "helix-cursor"), appendCursor)
        scope.cancel()
    }

    @Test
    fun nonLiveFullscreenSuppressesStreamRefreshAndDoesNotCountAsLiveViewing() = runBlocking {
        val key = StreamFeedKey("top:non-live-player")
        val cache = FakeCache(key, emptyList(), StreamFeedState(key.value, lastSuccessAt = 0L))
        var loads = 0
        val loader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
                loads++
                return StreamFeedPage(emptyList(), null)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var elapsed = 0L
        val coordinator = StreamFeedRefreshCoordinator(cache, scope, { 1_000_000L }, { elapsed }, false)
        coordinator.setVisibleFeed(StreamFeedSpec(key, loader))

        coordinator.playbackEntered(isLive = true)
        elapsed = StreamFeedFreshnessPolicy.PLAYBACK_RETURN_THRESHOLD_MS
        coordinator.playbackChanged(isLive = false)
        assertTrue(coordinator.isPlayerFullscreen)
        coordinator.playbackReturned()

        assertFalse(coordinator.isPlayerFullscreen)
        assertEquals(0, loads)
        scope.cancel()
    }

    private class FakeCache(
        private val key: StreamFeedKey,
        var rows: List<CachedStreamFeedItem>,
        var currentState: StreamFeedState?,
    ) : StreamFeedCacheStore {
        var replacementCount = 0
        var appendCommitted: CompletableDeferred<Unit>? = null
        var failureRecorded: CompletableDeferred<Unit>? = null

        override fun pagingSource(feedKey: StreamFeedKey): PagingSource<Int, CachedStreamFeedItem> = error("unused")

        override suspend fun state(feedKey: StreamFeedKey): StreamFeedState? = currentState

        override suspend fun itemCount(feedKey: StreamFeedKey): Int = rows.size

        override suspend fun touchAccess(feedKey: StreamFeedKey, nowMs: Long) {
            currentState = (currentState ?: StreamFeedState(feedKey.value)).copy(lastAccessAt = nowMs)
        }

        override suspend fun markAttempt(feedKey: StreamFeedKey, nowMs: Long) {
            currentState = (currentState ?: StreamFeedState(feedKey.value)).copy(lastAttemptAt = nowMs)
        }

        override suspend fun replaceAfterRefresh(
            feedKey: StreamFeedKey,
            page: StreamFeedPage,
            nowMs: Long,
            preserveTail: Boolean,
            pruneStaleOnEnd: Boolean,
        ) {
            val generation = (currentState?.activeGeneration ?: 0L) + 1L
            rows = if (preserveTail) {
                refreshCachedItemsPreservingTail(feedKey.value, rows, page.items, generation)
            } else {
                refreshCachedItems(feedKey.value, page.items, generation)
            }
            if (page.nextCursor == null && pruneStaleOnEnd) {
                rows = rows.filter { it.generation == generation }
            }
            currentState = StreamFeedState(
                feedKey = feedKey.value,
                nextCursor = page.nextCursor?.value,
                lastSuccessAt = nowMs,
                lastAttemptAt = nowMs,
                lastAccessAt = nowMs,
                nextCursorApi = page.nextCursor?.api,
                activeGeneration = generation,
            )
            replacementCount++
        }

        override suspend fun appendPage(
            feedKey: StreamFeedKey,
            page: StreamFeedPage,
            nowMs: Long,
            pruneStaleOnEnd: Boolean,
        ) {
            val current = currentState ?: StreamFeedState(feedKey.value)
            rows = appendCachedPage(feedKey.value, rows, page.items, current.activeGeneration)
            if (page.nextCursor == null && pruneStaleOnEnd) {
                rows = rows.filter { it.generation == current.activeGeneration }
            }
            currentState = current.copy(
                nextCursor = page.nextCursor?.value,
                nextCursorApi = page.nextCursor?.api,
                lastAttemptAt = nowMs,
                lastAccessAt = nowMs,
            )
            appendCommitted?.complete(Unit)
        }

        override suspend fun pruneStaleGeneration(feedKey: StreamFeedKey) {
            val generation = currentState?.activeGeneration ?: return
            rows = rows.filter { it.generation == generation }
        }

        override suspend fun recordFailure(feedKey: StreamFeedKey, nowMs: Long, failureBackoffUntil: Long?, rateLimitUntil: Long?) {
            currentState = (currentState ?: StreamFeedState(feedKey.value)).copy(
                lastAttemptAt = nowMs,
                failureBackoffUntil = failureBackoffUntil,
                rateLimitUntil = rateLimitUntil,
            )
            failureRecorded?.complete(Unit)
        }

        override suspend fun invalidatePrefix(prefix: String, nowMs: Long) = Unit

        override suspend fun cleanup(nowMs: Long) = Unit
    }
}
