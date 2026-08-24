package com.github.andreyasadchy.xtra.repository.datasource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.github.andreyasadchy.xtra.graphql.type.Language
import com.github.andreyasadchy.xtra.graphql.type.StreamSort
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository

/** Compatibility PagingSource for callers outside the Room-backed feed path. */
class StreamsDataSource(
    gqlQueryLanguages: List<Language>?,
    gqlQuerySort: StreamSort?,
    gqlLanguages: List<String>?,
    gqlSort: String?,
    tags: List<String>?,
    gqlHeaders: Map<String, String>,
    graphQLRepository: GraphQLRepository,
    helixHeaders: Map<String, String>,
    helixRepository: HelixRepository,
    networkLibrary: String?,
) : PagingSource<Int, Stream>() {
    private val loader = TopStreamsPageLoader(
        gqlQueryLanguages = gqlQueryLanguages,
        gqlQuerySort = gqlQuerySort,
        gqlLanguages = gqlLanguages,
        gqlSort = gqlSort,
        tags = tags,
        gqlHeaders = { gqlHeaders },
        graphQLRepository = graphQLRepository,
        helixHeaders = { helixHeaders },
        helixRepository = helixRepository,
        networkLibrary = networkLibrary,
    )
    private var cursor: StreamFeedCursor? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Stream> {
        return try {
            val page = loader.load(cursor).also { cursor = it.nextCursor }
            LoadResult.Page(
                data = page.items,
                prevKey = null,
                nextKey = page.nextCursor?.let { (params.key ?: 1) + 1 },
            )
        } catch (error: Exception) {
            LoadResult.Error(error)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Stream>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
