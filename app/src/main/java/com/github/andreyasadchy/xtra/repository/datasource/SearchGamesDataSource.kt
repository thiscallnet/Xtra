package com.github.andreyasadchy.xtra.repository.datasource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.util.C
import java.io.IOException
import kotlinx.coroutines.CancellationException

internal class SearchGamesDataSource(
    private val query: String,
    private val gqlHeaders: Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val helixHeaders: Map<String, String>,
    private val helixRepository: HelixRepository,
    private val networkLibrary: String?,
) : PagingSource<SearchPageKey, Game>() {

    override suspend fun load(params: LoadParams<SearchPageKey>): LoadResult<SearchPageKey, Game> {
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

    private suspend fun loadFirstPage(loadSize: Int): LoadResult<SearchPageKey, Game> {
        return try {
            loadGql(loadSize, cursor = null)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            try {
                loadPersisted(cursor = null)
            } catch (error2: Exception) {
                if (error2 is CancellationException) throw error2
                loadHelix(loadSize, cursor = null)
            }
        }
    }

    private suspend fun loadFromApi(
        loadSize: Int,
        key: SearchPageKey,
    ): LoadResult<SearchPageKey, Game> = when (key.api) {
        C.GQL -> loadGql(loadSize, key.cursor)
        C.GQL_PERSISTED_QUERY -> loadPersisted(key.cursor)
        C.HELIX -> loadHelix(loadSize, key.cursor)
        else -> throw IOException("Unknown search game API: ${key.api}")
    }

    private suspend fun loadGql(
        loadSize: Int,
        cursor: String?,
    ): LoadResult<SearchPageKey, Game> {
        val response = graphQLRepository.loadQuerySearchGames(
            networkLibrary,
            gqlHeaders,
            query,
            loadSize,
            cursor,
        )
        val data = response.data?.searchCategories
            ?: throw IOException(
                buildString {
                    append("SearchGamesQuery returned no game data")
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
                Game(
                    id = it.id,
                    slug = it.slug,
                    name = it.displayName,
                    boxArtURL = it.boxArtURL,
                    viewerCount = it.viewersCount ?: 0,
                    broadcasterCount = it.broadcastersCount ?: 0,
                    tags = it.tags?.map { tag ->
                        Tag(
                            id = tag.id,
                            name = tag.localizedName,
                        )
                    },
                )
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

    private suspend fun loadPersisted(cursor: String?): LoadResult<SearchPageKey, Game> {
        val response = graphQLRepository.loadSearchGames(networkLibrary, gqlHeaders, query, cursor)
        val data = response.data?.searchFor?.games
            ?: throw IOException(
                buildString {
                    append("SearchResultsPage_SearchResults returned no game data")
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
                Game(
                    id = it.id,
                    slug = it.slug,
                    name = it.displayName,
                    boxArtURL = it.boxArtURL,
                    viewerCount = it.viewersCount ?: 0,
                    tags = it.tags?.map { tag ->
                        Tag(
                            id = tag.id,
                            name = tag.localizedName,
                        )
                    },
                )
            }
        }
        return LoadResult.Page(
            data = list,
            prevKey = null,
            nextKey = nextSearchPageKey(C.GQL_PERSISTED_QUERY, cursor, data.cursor),
        )
    }

    private suspend fun loadHelix(
        loadSize: Int,
        cursor: String?,
    ): LoadResult<SearchPageKey, Game> {
        if (helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            throw IOException("Helix search requires authentication")
        }
        val response = helixRepository.getSearchGames(
            networkLibrary = networkLibrary,
            headers = helixHeaders,
            query = query,
            limit = loadSize,
            offset = cursor,
        )
        val list = response.data.map {
            Game(
                id = it.id,
                name = it.name,
                boxArtURL = it.boxArtURL,
            )
        }
        return LoadResult.Page(
            data = list,
            prevKey = null,
            nextKey = nextSearchPageKey(C.HELIX, cursor, response.pagination?.cursor),
        )
    }

    override fun getRefreshKey(state: PagingState<SearchPageKey, Game>): SearchPageKey? = null
}
