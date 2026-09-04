package com.github.andreyasadchy.xtra.repository.datasource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.util.C
import java.io.IOException
import kotlinx.coroutines.CancellationException

internal class SearchVideosDataSource(
    private val query: String,
    private val gqlHeaders: Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val networkLibrary: String?,
) : PagingSource<SearchPageKey, Video>() {

    override suspend fun load(params: LoadParams<SearchPageKey>): LoadResult<SearchPageKey, Video> {
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

    private suspend fun loadFirstPage(loadSize: Int): LoadResult<SearchPageKey, Video> {
        return try {
            loadGql(loadSize, cursor = null)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            loadPersisted(cursor = null)
        }
    }

    private suspend fun loadFromApi(
        loadSize: Int,
        key: SearchPageKey,
    ): LoadResult<SearchPageKey, Video> = when (key.api) {
        C.GQL -> loadGql(loadSize, key.cursor)
        C.GQL_PERSISTED_QUERY -> loadPersisted(key.cursor)
        else -> throw IOException("Unknown search video API: ${key.api}")
    }

    private suspend fun loadGql(
        loadSize: Int,
        cursor: String?,
    ): LoadResult<SearchPageKey, Video> {
        val response = graphQLRepository.loadQuerySearchVideos(
            networkLibrary,
            gqlHeaders,
            query,
            loadSize,
            cursor,
        )
        val data = response.data?.searchFor?.videos
            ?: throw IOException(
                buildString {
                    append("SearchVideosQuery returned no video data")
                    response.errors
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { errors ->
                            append(": ")
                            append(errors.joinToString("; ") { it.message })
                        }
                }
            )
        val items = data.items.orEmpty()
        val list = items.map {
            Video(
                id = it.id,
                channelId = it.owner?.id,
                channelLogin = it.owner?.login,
                channelName = it.owner?.displayName,
                channelImageURL = it.owner?.profileImageURL,
                gameId = it.game?.id,
                gameSlug = it.game?.slug,
                gameName = it.game?.displayName,
                title = it.title,
                thumbnailURL = it.previewThumbnailURL,
                createdAt = it.createdAt?.toString(),
                viewCount = it.viewCount,
                durationSeconds = it.lengthSeconds,
                type = it.broadcastType?.toString(),
                animatedPreviewURL = it.animatedPreviewURL,
            )
        }
        return LoadResult.Page(
            data = list,
            prevKey = null,
            nextKey = nextSearchPageKey(
                api = C.GQL,
                currentCursor = cursor,
                candidate = data.cursor,
            ).takeIf { data.pageInfo?.hasNextPage == true },
        )
    }

    private suspend fun loadPersisted(cursor: String?): LoadResult<SearchPageKey, Video> {
        val response = graphQLRepository.loadSearchVideos(networkLibrary, gqlHeaders, query, cursor)
        val data = response.data?.searchFor?.videos
            ?: throw IOException(
                buildString {
                    append("SearchResultsPage_SearchResults returned no video data")
                    response.errors
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { errors ->
                            append(": ")
                            append(errors.joinToString("; ") { it.message ?: "unknown GraphQL error" })
                        }
                }
            )
        val list = data.edges.map { item ->
            item.item.let {
                Video(
                    id = it.id,
                    channelId = it.owner?.id,
                    channelLogin = it.owner?.login,
                    channelName = it.owner?.displayName,
                    gameId = it.game?.id,
                    gameSlug = it.game?.slug,
                    gameName = it.game?.displayName,
                    title = it.title,
                    thumbnailURL = it.previewThumbnailURL,
                    createdAt = it.createdAt,
                    viewCount = it.viewCount,
                    durationSeconds = it.lengthSeconds,
                )
            }
        }
        return LoadResult.Page(
            data = list,
            prevKey = null,
            nextKey = nextSearchPageKey(C.GQL_PERSISTED_QUERY, cursor, data.cursor),
        )
    }

    override fun getRefreshKey(state: PagingState<SearchPageKey, Video>): SearchPageKey? = null
}
