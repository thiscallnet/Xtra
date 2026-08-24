package com.github.andreyasadchy.xtra.repository.gamefeed

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.github.andreyasadchy.xtra.model.ui.Game
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@OptIn(androidx.paging.ExperimentalPagingApi::class)
class GameFeedPager(
    private val cache: GameFeedCache,
    private val coordinator: GameFeedRefreshCoordinator,
) {
    fun flow(spec: GameFeedSpec, config: PagingConfig): Flow<PagingData<Game>> = Pager(
        config = config,
        remoteMediator = GameFeedRemoteMediator(spec, cache, coordinator),
    ) {
        cache.pagingSource(spec.key)
    }.flow.map { pagingData -> pagingData.map { it.toGame() } }
}
