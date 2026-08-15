package com.github.andreyasadchy.xtra.repository.streamfeed

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.github.andreyasadchy.xtra.model.ui.Stream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@OptIn(androidx.paging.ExperimentalPagingApi::class)
class StreamFeedPager(
    private val cache: StreamFeedCache,
    private val coordinator: StreamFeedRefreshCoordinator,
) {
    fun flow(spec: StreamFeedSpec, config: PagingConfig): Flow<PagingData<Stream>> {
        return Pager(
            config = config,
            remoteMediator = StreamFeedRemoteMediator(spec, cache, coordinator),
        ) {
            cache.pagingSource(spec.key)
        }.flow.map { pagingData ->
            pagingData.map { it.toStream() }
        }
    }
}
