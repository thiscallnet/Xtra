package com.github.andreyasadchy.xtra.repository.streamfeed

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import com.github.andreyasadchy.xtra.db.CachedStreamFeedItem
import com.github.andreyasadchy.xtra.db.StreamFeedState
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.datasource.StreamFeedPage
import com.github.andreyasadchy.xtra.repository.datasource.StreamFeedCursor
import com.github.andreyasadchy.xtra.repository.datasource.StreamFeedPageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalPagingApi::class)
class StreamFeedRemoteMediatorTest {

    @Test
    fun cancellationIsNotConvertedToMediatorError() = runBlocking {
        val now = 1_000_000L
        val key = StreamFeedKey("top:mediator-cancellation")
        val cache = FakeCache(key, emptyList(), StreamFeedState(key.value, lastSuccessAt = 0L))
        val cancellation = CancellationException("cancelled")
        val loader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
                throw cancellation
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mediator = StreamFeedRemoteMediator(
            StreamFeedSpec(key, loader),
            cache,
            StreamFeedRefreshCoordinator(cache, scope, { now }, { 1_000L }, false),
            { now },
        )

        mediator.initialize()

        assertThrows(CancellationException::class.java) {
            runBlocking { mediator.load(LoadType.REFRESH, emptyPagingState()) }
        }
        scope.cancel()
    }

    @Test
    fun initialFailureCanBeRetriedWithoutLeavingTheMediatorLoading() = runBlocking {
        val now = 1_000_000L
        val key = StreamFeedKey("top:mediator-retry")
        val cache = FakeCache(key, emptyList(), StreamFeedState(key.value, lastSuccessAt = 0L))
        var loads = 0
        val loader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
                loads++
                if (loads == 1) error("offline")
                return StreamFeedPage(listOf(Stream(channelId = "fresh")), null)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mediator = StreamFeedRemoteMediator(
            StreamFeedSpec(key, loader),
            cache,
            StreamFeedRefreshCoordinator(cache, scope, { now }, { 1_000L }, false),
            { now },
        )

        assertEquals(RemoteMediator.InitializeAction.LAUNCH_INITIAL_REFRESH, mediator.initialize())
        assertTrue(mediator.load(LoadType.REFRESH, emptyPagingState()) is RemoteMediator.MediatorResult.Error)
        val retry = mediator.load(LoadType.REFRESH, emptyPagingState())

        assertTrue(retry is RemoteMediator.MediatorResult.Success)
        assertEquals(2, loads)
        assertEquals(listOf("channel:fresh"), cache.rows.map { it.itemKey })
        scope.cancel()
    }

    @Test
    fun appendRetryIgnoresAppendFailureBackoff() = runBlocking {
        val now = 1_000_000L
        val key = StreamFeedKey("top:mediator-append-retry")
        val cache = FakeCache(
            key,
            emptyList(),
            StreamFeedState(
                key.value,
                nextCursor = "cursor",
                nextCursorApi = "gql",
                failureBackoffUntil = now + 60_000L,
            ),
        )
        var loads = 0
        val loader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
                loads++
                assertEquals(StreamFeedCursor("gql", "cursor"), cursor)
                return StreamFeedPage(emptyList(), null)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mediator = StreamFeedRemoteMediator(
            StreamFeedSpec(key, loader),
            cache,
            StreamFeedRefreshCoordinator(cache, scope, { now }, { 1_000L }, false),
            { now },
        )

        assertTrue(mediator.load(LoadType.APPEND, emptyPagingState()) is RemoteMediator.MediatorResult.Success)
        assertEquals(1, loads)
        scope.cancel()
    }

    @Test
    fun persistedBackoffSkipsLaunchingInitialRefresh() = runBlocking {
        val now = 1_000_000L
        val key = StreamFeedKey("top:mediator-backoff")
        val cache = FakeCache(
            key,
            emptyList(),
            StreamFeedState(key.value, failureBackoffUntil = now + 60_000L),
        )
        var loads = 0
        val loader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
                loads++
                return StreamFeedPage(emptyList(), null)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mediator = StreamFeedRemoteMediator(
            StreamFeedSpec(key, loader),
            cache,
            StreamFeedRefreshCoordinator(cache, scope, { now }, { 1_000L }, false),
            { now },
        )

        assertEquals(RemoteMediator.InitializeAction.SKIP_INITIAL_REFRESH, mediator.initialize())
        assertEquals(0, loads)
        scope.cancel()
    }

    @Test
    fun noOpRefreshSettlesAsEndOfPagination() = runBlocking {
        val now = 1_000_000L
        val key = StreamFeedKey("top:mediator-noop")
        val cache = FakeCache(key, emptyList(), StreamFeedState(key.value, lastSuccessAt = 0L))
        var loads = 0
        val loader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
                loads++
                return StreamFeedPage(emptyList(), null)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mediator = StreamFeedRemoteMediator(
            StreamFeedSpec(key, loader),
            cache,
            StreamFeedRefreshCoordinator(cache, scope, { now }, { 1_000L }, false),
            { now },
        )

        assertEquals(RemoteMediator.InitializeAction.LAUNCH_INITIAL_REFRESH, mediator.initialize())
        cache.currentState = cache.currentState!!.copy(failureBackoffUntil = Long.MAX_VALUE)
        val result = mediator.load(LoadType.REFRESH, emptyPagingState())

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(0, loads)
        scope.cancel()
    }

    private fun emptyPagingState(): PagingState<Int, CachedStreamFeedItem> {
        return PagingState(
            pages = emptyList(),
            anchorPosition = null,
            config = PagingConfig(pageSize = 30),
            leadingPlaceholderCount = 0,
        )
    }

    private class FakeCache(
        private val key: StreamFeedKey,
        var rows: List<CachedStreamFeedItem>,
        var currentState: StreamFeedState?,
    ) : StreamFeedCacheStore {
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
        ) {
            rows = refreshCachedItems(feedKey.value, page.items)
            currentState = StreamFeedState(
                feedKey.value,
                page.nextCursor?.value,
                nowMs,
                nowMs,
                nowMs,
                nextCursorApi = page.nextCursor?.api,
            )
        }

        override suspend fun appendPage(
            feedKey: StreamFeedKey,
            page: StreamFeedPage,
            nowMs: Long,
        ) = Unit

        override suspend fun pruneStaleGeneration(feedKey: StreamFeedKey) {
            rows = rows.filter { it.generation == currentState?.activeGeneration }
        }

        override suspend fun recordFailure(feedKey: StreamFeedKey, nowMs: Long, failureBackoffUntil: Long?, rateLimitUntil: Long?) {
            currentState = (currentState ?: StreamFeedState(feedKey.value)).copy(
                lastAttemptAt = nowMs,
                failureBackoffUntil = failureBackoffUntil,
                rateLimitUntil = rateLimitUntil,
            )
        }

        override suspend fun invalidatePrefix(prefix: String, nowMs: Long) = Unit

        override suspend fun cleanup(nowMs: Long) = Unit
    }
}
