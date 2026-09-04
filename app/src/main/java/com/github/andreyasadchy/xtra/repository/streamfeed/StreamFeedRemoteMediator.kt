package com.github.andreyasadchy.xtra.repository.streamfeed

import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import com.github.andreyasadchy.xtra.db.CachedStreamFeedItem
import kotlinx.coroutines.CancellationException

@OptIn(androidx.paging.ExperimentalPagingApi::class)
class StreamFeedRemoteMediator(
    private val spec: StreamFeedSpec,
    private val cache: StreamFeedCacheStore,
    private val coordinator: StreamFeedRefreshCoordinator,
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
) : RemoteMediator<Int, CachedStreamFeedItem>() {

    private var initialRefreshRequested = false

    override suspend fun initialize(): InitializeAction {
        val now = wallClockMs()
        cache.touchAccess(spec.key, now)
        val state = cache.state(spec.key)
        initialRefreshRequested = shouldLaunchInitialRefresh(now, state)
        return if (initialRefreshRequested) {
            InitializeAction.LAUNCH_INITIAL_REFRESH
        } else {
            InitializeAction.SKIP_INITIAL_REFRESH
        }
    }

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, CachedStreamFeedItem>,
    ): MediatorResult {
        return try {
            when (loadType) {
                LoadType.PREPEND -> MediatorResult.Success(endOfPaginationReached = true)
                LoadType.REFRESH -> {
                    // The initial stale/missing refresh is still automatic and
                    // must respect failure backoff. Only an explicit adapter
                    // refresh bypasses the freshness gate.
                    val isInitialRefresh = initialRefreshRequested
                    // A failed initial load must not leave the next retry
                    // pretending to be the same Paging generation forever.
                    // An explicit retry becomes a force refresh, while the
                    // coordinator still honors Twitch's rate-limit deadline.
                    initialRefreshRequested = false
                    val result = if (isInitialRefresh) {
                        coordinator.maybeRefresh(spec, RefreshReason.INITIAL)
                    } else {
                        coordinator.forceRefresh(spec, RefreshReason.USER_PULL)
                    }
                    when (result.decision) {
                        RefreshDecision.SKIP_BACKOFF,
                        RefreshDecision.SKIP_DEBOUNCED,
                        RefreshDecision.SKIP_FRESH,
                        -> MediatorResult.Success(endOfPaginationReached = true)
                        RefreshDecision.REFRESH,
                        RefreshDecision.JOIN,
                        -> MediatorResult.Success(endOfPaginationReached = false)
                    }
                }
                LoadType.APPEND -> {
                    val result = coordinator.append(spec)
                    MediatorResult.Success(result.endOfPaginationReached)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            MediatorResult.Error(error)
        }
    }
}
