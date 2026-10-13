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
import com.github.andreyasadchy.xtra.ui.common.SectionLoadState
import com.github.andreyasadchy.xtra.ui.search.SearchSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
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

    data class Remote(
        val query: String = "",
        val channels: List<User> = emptyList(),
        val games: List<Game> = emptyList(),
        val streams: List<Stream> = emptyList(),
        val videos: List<Video> = emptyList(),
        val stages: Map<SearchSection, SectionLoadState> = emptyMap(),
    ) {
        val loading get() = query.isNotBlank() && SearchSection.entries.any { stage(it) == SectionLoadState.LOADING }
        val failed get() = stages.values.any { it == SectionLoadState.FAILED }
        fun stage(section: SearchSection) = stages[section] ?: SectionLoadState.LOADING
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query
    private val retrySections = MutableSharedFlow<Set<SearchSection>>(extraBufferCapacity = 1)
    private var cachedRemote = Remote()

    @OptIn(ExperimentalCoroutinesApi::class)
    val remote: StateFlow<Remote> = _query.flatMapLatest { query ->
            channelFlow {
                if (query.isBlank()) {
                    cachedRemote = Remote()
                    send(cachedRemote)
                    return@channelFlow
                }
                // Cache only this query. Restarting collection may refresh it, but must not
                // erase useful results while requests are running or when one fails.
                var current = cachedRemote.takeIf { it.query == query } ?: Remote(query)
                val jobs = mutableMapOf<SearchSection, Job>()
                suspend fun publish(value: Remote) {
                    currentCoroutineContext().ensureActive()
                    current = value
                    cachedRemote = value
                    send(value)
                }
                fun refresh(section: SearchSection) {
                    if (jobs[section]?.isActive == true) return
                    jobs[section] = launch {
                        publish(current.copy(stages = current.stages + (section to SectionLoadState.LOADING)))
                        fun stage(success: Boolean) = current.stages + (section to if (success) SectionLoadState.READY else SectionLoadState.FAILED)
                        val result = when (section) {
                            SearchSection.CHANNELS -> loadChannels(query).let { current.copy(channels = it ?: current.channels, stages = stage(it != null)) }
                            SearchSection.CATEGORIES -> loadGames(query).let { current.copy(games = it ?: current.games, stages = stage(it != null)) }
                            SearchSection.STREAMS -> loadStreams(query).let { current.copy(streams = it ?: current.streams, stages = stage(it != null)) }
                            SearchSection.VIDEOS -> loadVideos(query).let { current.copy(videos = it ?: current.videos, stages = stage(it != null)) }
                        }
                        publish(result)
                    }
                }
                publish(current.copy(stages = SearchSection.entries.associateWith { SectionLoadState.LOADING }))
                SearchSection.entries.forEach(::refresh)
                retrySections.collect { sections -> sections.forEach(::refresh) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), Remote())

    fun setQuery(newQuery: String) {
        _query.value = newQuery.trim()
    }

    fun retry(section: SearchSection? = null) {
        val result = remote.value.takeIf { it.query == query.value && it.query.isNotBlank() } ?: return
        val sections = if (section == null) SearchSection.entries.filter { result.stage(it) == SectionLoadState.FAILED }.toSet()
            else setOf(section).filter { result.stage(it) == SectionLoadState.FAILED }.toSet()
        if (sections.isNotEmpty()) retrySections.tryEmit(sections)
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
