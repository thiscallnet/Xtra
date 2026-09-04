package com.github.andreyasadchy.xtra.repository.datasource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.util.C
import java.io.IOException
import kotlinx.coroutines.CancellationException

internal class SearchStreamsDataSource(
    private val query: String,
    private val gqlHeaders: Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val helixHeaders: Map<String, String>,
    private val helixRepository: HelixRepository,
    private val networkLibrary: String?,
) : PagingSource<SearchPageKey, Stream>() {

    override suspend fun load(params: LoadParams<SearchPageKey>): LoadResult<SearchPageKey, Stream> {
        if (query.isBlank()) {
            return LoadResult.Page(
                data = emptyList(),
                prevKey = null,
                nextKey = null,
            )
        }

        return try {
            params.key?.let { key ->
                loadFromApi(params.loadSize, key)
            } ?: loadFirstPage(params.loadSize)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LoadResult.Error(error)
        }
    }

    private suspend fun loadFirstPage(loadSize: Int): LoadResult<SearchPageKey, Stream> {
        return try {
            loadGql(loadSize, cursor = null)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            loadHelix(loadSize, cursor = null)
        }
    }

    private suspend fun loadFromApi(
        loadSize: Int,
        key: SearchPageKey,
    ): LoadResult<SearchPageKey, Stream> = when (key.api) {
        C.GQL -> loadGql(loadSize, key.cursor)
        C.HELIX -> loadHelix(loadSize, key.cursor)
        else -> throw IOException("Unknown search stream API: ${key.api}")
    }

    private suspend fun loadGql(
        loadSize: Int,
        cursor: String?,
    ): LoadResult<SearchPageKey, Stream> {
        val response = graphQLRepository.loadQuerySearchStreams(
            networkLibrary,
            gqlHeaders,
            query,
            loadSize,
            cursor,
        )
        val data = response.data?.searchStreams
            ?: throw IOException(
                buildString {
                    append("SearchStreamsQuery returned no stream data")
                    response.errors
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { errors ->
                            append(": ")
                            append(errors.joinToString("; ") { it.message })
                        }
                }
            )
        val edges = data.edges.orEmpty()
        val list = edges.mapNotNull { item ->
            item.node?.let {
                Stream(
                    id = it.id,
                    channelId = it.broadcaster?.id,
                    channelLogin = it.broadcaster?.login,
                    channelName = it.broadcaster?.displayName,
                    channelImageURL = it.broadcaster?.profileImageURL,
                    gameId = it.game?.id,
                    gameSlug = it.game?.slug,
                    gameName = it.game?.displayName,
                    title = it.broadcaster?.broadcastSettings?.title,
                    thumbnailURL = it.previewImageURL,
                    createdAt = it.createdAt?.toString(),
                    viewerCount = it.viewersCount,
                    tags = it.freeformTags?.mapNotNull { tag -> tag.name },
                ).takeIf { stream ->
                    stream.channelId != null || stream.channelLogin != null
                }
            }
        }
        return LoadResult.Page(
            data = list,
            prevKey = null,
            nextKey = nextSearchPageKey(
                api = C.GQL,
                currentCursor = cursor,
                candidate = edges.lastOrNull()?.cursor,
            ).takeIf { data.pageInfo?.hasNextPage == true },
        )
    }

    private suspend fun loadHelix(
        loadSize: Int,
        cursor: String?,
    ): LoadResult<SearchPageKey, Stream> {
        val response = helixRepository.getSearchChannels(
            networkLibrary = networkLibrary,
            headers = helixHeaders,
            query = query,
            limit = loadSize,
            offset = cursor,
            live = true,
        )
        val list = response.data.mapNotNull {
            if (it.isLive == true) {
                Stream(
                    channelId = it.id,
                    channelLogin = it.login,
                    channelName = it.displayName,
                    channelImageURL = it.profileImageURL,
                    gameId = it.gameId,
                    gameName = it.gameName,
                    title = it.title,
                    createdAt = it.startedAt,
                    tags = it.tags,
                ).takeIf { stream ->
                    stream.channelId != null || stream.channelLogin != null
                }
            } else null
        }
        return LoadResult.Page(
            data = list,
            prevKey = null,
            nextKey = nextSearchPageKey(C.HELIX, cursor, response.pagination?.cursor),
        )
    }

    override fun getRefreshKey(state: PagingState<SearchPageKey, Stream>): SearchPageKey? = null
}
