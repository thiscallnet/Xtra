package com.github.andreyasadchy.xtra.repository.datasource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.github.andreyasadchy.xtra.model.ui.DropStreamFilter
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.matchesDropStream
import com.github.andreyasadchy.xtra.model.ui.matchesDropsEnabledTag
import com.github.andreyasadchy.xtra.model.ui.matchesGame
import com.github.andreyasadchy.xtra.repository.DropsRepository
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.util.C
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal class SearchStreamsDataSource(
    private val queries: List<String>,
    private val gqlHeaders: Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val helixHeaders: Map<String, String>,
    private val helixRepository: HelixRepository,
    private val networkLibrary: String?,
    private val dropsFilters: List<DropStreamFilter> = emptyList(),
    private val dropsRepository: DropsRepository? = null,
) : PagingSource<SearchPageKey, Stream>() {

    private companion object {
        const val FIRST_PAGE = "first"
        const val MAX_DROP_SEARCH_PAGES = 3
    }

    override suspend fun load(params: LoadParams<SearchPageKey>): LoadResult<SearchPageKey, Stream> {
        val queryIndex = params.key?.queryIndex ?: 0
        val query = queries.getOrNull(queryIndex)?.takeIf { it.isNotBlank() }
        if (query == null) {
            return LoadResult.Page(
                data = emptyList(),
                prevKey = null,
                nextKey = null,
            )
        }

        return try {
            val page = loadPage(
                loadSize = params.loadSize,
                query = query,
                state = params.key?.queryStates?.getOrNull(queryIndex),
            )
            val pageWithNextQuery = page.withNextQuery(
                currentKey = params.key,
                queryIndex = queryIndex,
            )
            if (dropsFilters.isEmpty()) pageWithNextQuery else filterDropPage(pageWithNextQuery)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LoadResult.Error(error)
        }
    }

    private suspend fun filterDropPage(
        page: LoadResult<SearchPageKey, Stream>,
    ): LoadResult<SearchPageKey, Stream> {
        val current = page as? LoadResult.Page ?: return page
        return LoadResult.Page(
            data = filterEligibleStreams(current.data),
            prevKey = current.prevKey,
            nextKey = current.nextKey,
        )
    }

    private suspend fun filterEligibleStreams(streams: List<Stream>): List<Stream> {
        val filters = dropsFilters
        val repository = dropsRepository ?: return emptyList()
        val candidates = streams.filter { stream ->
            filters.any { filter ->
                filter.matchesGame(stream.gameId, stream.gameName) &&
                    filter.matchesDropsEnabledTag(stream.tags)
            }
        }
        return coroutineScope {
            candidates.map { stream ->
                async {
                    val campaigns = stream.channelId?.let { channelId ->
                        repository.refreshChannelDropCatalog(channelId)
                    }
                    stream.takeIf { campaigns?.let { available ->
                        filters.matchesDropStream(stream.gameId, stream.gameName, available)
                    } == true }?.also { it.dropsAvailable = true }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun loadPage(
        loadSize: Int,
        query: String,
        state: SearchQueryPageState?,
    ): LoadedStreamPage {
        val api = state?.api
        if (api == null) {
            return try {
                LoadedStreamPage(loadGql(loadSize, query, cursor = null), C.GQL)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                LoadedStreamPage(loadHelix(loadSize, query, cursor = null), C.HELIX)
            }
        }
        return LoadedStreamPage(
            result = when (api) {
                C.GQL -> loadGql(loadSize, query, state.cursor)
                C.HELIX -> loadHelix(loadSize, query, state.cursor)
                else -> throw IOException("Unknown search stream API: $api")
            },
            api = api,
        )
    }

    private data class LoadedStreamPage(
        val result: LoadResult<SearchPageKey, Stream>,
        val api: String,
    )

    private suspend fun loadGql(
        loadSize: Int,
        query: String,
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
        query: String,
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

    private fun LoadedStreamPage.withNextQuery(
        currentKey: SearchPageKey?,
        queryIndex: Int,
    ): LoadResult<SearchPageKey, Stream> {
        val page = result as? LoadResult.Page ?: return result
        val states = List(queries.size) { index ->
            currentKey?.queryStates?.getOrNull(index) ?: SearchQueryPageState()
        }
        val pagesLoaded = states[queryIndex].pagesLoaded + 1
        val updatedStates = states.toMutableList().apply {
            this[queryIndex] = SearchQueryPageState(
                api = api,
                cursor = page.nextKey?.cursor,
                exhausted = isSearchQueryExhausted(
                    hasNextPage = page.nextKey != null,
                    pagesLoaded = pagesLoaded,
                    pageBudget = MAX_DROP_SEARCH_PAGES.takeIf { dropsFilters.isNotEmpty() },
                ),
                pagesLoaded = pagesLoaded,
            )
        }
        val nextIndex = nextSearchQueryIndex(queryIndex, updatedStates)
            ?: return page.copy(nextKey = null)
        val nextState = updatedStates[nextIndex]
        return page.copy(
            nextKey = SearchPageKey(
                api = nextState.api ?: FIRST_PAGE,
                cursor = nextState.cursor.orEmpty(),
                queryIndex = nextIndex,
                queryStates = updatedStates,
            ),
        )
    }
}
