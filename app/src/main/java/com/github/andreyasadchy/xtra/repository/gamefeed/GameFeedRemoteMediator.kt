package com.github.andreyasadchy.xtra.repository.gamefeed

import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import com.github.andreyasadchy.xtra.db.CachedGameFeedItem
import com.github.andreyasadchy.xtra.repository.streamfeed.RefreshReason

@OptIn(androidx.paging.ExperimentalPagingApi::class)
class GameFeedRemoteMediator(
    private val spec: GameFeedSpec,
    private val cache: GameFeedCacheStore,
    private val coordinator: GameFeedRefreshCoordinator,
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
) : RemoteMediator<Int, CachedGameFeedItem>() {
    private var initialRefreshRequested = false

    override suspend fun initialize(): InitializeAction {
        val now = wallClockMs()
        cache.touchAccess(spec.key, now)
        initialRefreshRequested = shouldLaunchInitialGameRefresh(now, cache.state(spec.key))
        return if (initialRefreshRequested) InitializeAction.LAUNCH_INITIAL_REFRESH else InitializeAction.SKIP_INITIAL_REFRESH
    }

    override suspend fun load(loadType: LoadType, state: PagingState<Int, CachedGameFeedItem>): MediatorResult {
        return try {
            when (loadType) {
                LoadType.PREPEND -> MediatorResult.Success(endOfPaginationReached = true)
                LoadType.REFRESH -> {
                    val initial = initialRefreshRequested
                    initialRefreshRequested = false
                    val result = if (initial) {
                        coordinator.maybeRefresh(spec, RefreshReason.INITIAL)
                    } else {
                        coordinator.forceRefresh(spec, RefreshReason.USER_PULL)
                    }
                    when (result.decision) {
                        GameRefreshDecision.SKIP_BACKOFF,
                        GameRefreshDecision.SKIP_DEBOUNCED,
                        GameRefreshDecision.SKIP_FRESH,
                        -> MediatorResult.Success(endOfPaginationReached = true)
                        GameRefreshDecision.REFRESH,
                        GameRefreshDecision.JOIN,
                        -> MediatorResult.Success(endOfPaginationReached = false)
                    }
                }
                LoadType.APPEND -> MediatorResult.Success(coordinator.append(spec).endOfPaginationReached)
            }
        } catch (error: Exception) {
            MediatorResult.Error(error)
        }
    }
}
