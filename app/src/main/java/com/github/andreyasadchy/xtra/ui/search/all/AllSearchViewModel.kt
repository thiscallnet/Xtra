package com.github.andreyasadchy.xtra.ui.search.all

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.PagingSource
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.datasource.SearchChannelsDataSource
import com.github.andreyasadchy.xtra.repository.datasource.SearchGamesDataSource
import com.github.andreyasadchy.xtra.repository.datasource.SearchStreamsDataSource
import com.github.andreyasadchy.xtra.repository.datasource.SearchVideosDataSource
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * The best few channels, categories, live streams and videos for a query in one list, reusing the
 * same search sources as the individual tabs (including their fallbacks), so people do not have to
 * pick a category before searching.
 */
class AllSearchViewModel(
    private val applicationContext: Context,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
) : ViewModel() {

    /** [failed] is true only when no source answered; sources that fail alone just show nothing. */
    data class Remote(
        val query: String = "",
        val loading: Boolean = false,
        val channels: List<User> = emptyList(),
        val games: List<Game> = emptyList(),
        val streams: List<Stream> = emptyList(),
        val videos: List<Video> = emptyList(),
        val failed: Boolean = false,
    )

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query
    private val retryCount = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val remote: StateFlow<Remote> = combine(_query, retryCount) { query, _ -> query }
        .flatMapLatest { query ->
            flow {
                if (query.isBlank()) {
                    emit(Remote())
                    return@flow
                }
                emit(Remote(query, loading = true))
                val result = coroutineScope {
                    val channels = async { loadChannels(query) }
                    val games = async { loadGames(query) }
                    val streams = async { loadStreams(query) }
                    val videos = async { loadVideos(query) }
                    Remote(
                        query = query,
                        channels = channels.await() ?: emptyList(),
                        games = games.await() ?: emptyList(),
                        streams = streams.await() ?: emptyList(),
                        videos = videos.await() ?: emptyList(),
                        failed = channels.await() == null && games.await() == null &&
                            streams.await() == null && videos.await() == null,
                    )
                }
                emit(result)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), Remote())

    fun setQuery(newQuery: String) {
        _query.value = newQuery.trim()
    }

    fun retry() {
        retryCount.value++
    }

    private val networkLibrary get() = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)

    private suspend fun <T : Any> firstPage(load: suspend () -> PagingSource.LoadResult<*, T>): List<T>? = try {
        (load() as? PagingSource.LoadResult.Page<*, T>)?.data
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun loadChannels(query: String) = firstPage {
        SearchChannelsDataSource(
            query = query,
            gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true),
            graphQLRepository = graphQLRepository,
            helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext),
            helixRepository = helixRepository,
            networkLibrary = networkLibrary,
        ).load(PagingSource.LoadParams.Refresh(null, CHANNEL_PAGE, false))
    }

    private suspend fun loadGames(query: String) = firstPage {
        SearchGamesDataSource(
            query = query,
            gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true),
            graphQLRepository = graphQLRepository,
            helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext),
            helixRepository = helixRepository,
            networkLibrary = networkLibrary,
        ).load(PagingSource.LoadParams.Refresh(null, GAME_PAGE, false))
    }

    private suspend fun loadStreams(query: String) = firstPage {
        SearchStreamsDataSource(
            queries = listOf(query),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true),
            graphQLRepository = graphQLRepository,
            helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext),
            helixRepository = helixRepository,
            networkLibrary = networkLibrary,
        ).load(PagingSource.LoadParams.Refresh(null, STREAM_PAGE, false))
    }

    private suspend fun loadVideos(query: String) = firstPage {
        SearchVideosDataSource(
            query = query,
            gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true),
            graphQLRepository = graphQLRepository,
            networkLibrary = networkLibrary,
        ).load(PagingSource.LoadParams.Refresh(null, VIDEO_PAGE, false))
    }

    companion object {
        private const val CHANNEL_PAGE = 6
        private const val GAME_PAGE = 5
        private const val STREAM_PAGE = 5
        private const val VIDEO_PAGE = 5

        val AllSearchViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                AllSearchViewModel(application.applicationContext, xtraModule.graphQLRepository, xtraModule.helixRepository)
            }
        }
    }
}
