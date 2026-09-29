package com.github.andreyasadchy.xtra.repository.datasource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.util.C
import kotlinx.coroutines.CancellationException

class ChannelSuggestionsDataSource(
    private val channelLogin: String?,
    private val gqlHeaders: Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val helixHeaders: Map<String, String>,
    private val helixRepository: HelixRepository,
    private val networkLibrary: String?,
) : PagingSource<Int, Stream>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Stream> {
        return try {
            val response = graphQLRepository.loadChannelSuggestions(networkLibrary, gqlHeaders, channelLogin)
            val list = response.data!!.sideNav.sections.edges.find {
                it.node.id == "provider-side-nav-similar-streamer-currently-watching-1"
            }?.node?.content?.edges?.map { item ->
                item.node.let {
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
                        viewerCount = it.viewersCount,
                        tags = it.freeformTags?.mapNotNull { tag -> tag.name },
                    )
                }
            } ?: emptyList()
            val streams = list.withHelixBroadcasterTypes(networkLibrary, helixHeaders, helixRepository)
            LoadResult.Page(
                data = streams,
                prevKey = null,
                nextKey = null
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Stream>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
