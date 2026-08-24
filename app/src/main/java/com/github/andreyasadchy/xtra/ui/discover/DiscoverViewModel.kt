package com.github.andreyasadchy.xtra.ui.discover

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.graphql.type.StreamSort
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.RecommendationsRepository
import com.github.andreyasadchy.xtra.repository.datasource.GameStreamsPageLoader
import com.github.andreyasadchy.xtra.repository.datasource.TopStreamsPageLoader
import com.github.andreyasadchy.xtra.ui.following.overview.FollowingOverviewSection
import com.github.andreyasadchy.xtra.ui.following.overview.FollowingOverviewLoadingType
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DiscoverState(
    val sections: List<FollowingOverviewSection> = emptyList(),
    val trendingGame: Game? = null,
    val isLoading: Boolean = false,
    val hasError: Boolean = false,
)

class DiscoverViewModel(
    private val applicationContext: Context,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val recommendationsRepository: RecommendationsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        DiscoverState(
            sections = loadingSections(),
            isLoading = true,
        ),
    )
    val state: StateFlow<DiscoverState> = _state
    private var refreshJob: Job? = null

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.value = DiscoverState(sections = loadingSections(), isLoading = true)
            try {
                val topStreams = loadOrEmpty { loadTopStreams() }
                coroutineScope {
                    val recommendations = async {
                        loadOrEmpty {
                            recommendationsRepository.getLiveRecommendations(
                                limit = STREAM_LIMIT,
                                excludedChannelIds = topStreams.mapNotNull { it.channelId }.toSet(),
                            ).streams
                        }
                    }
                    val games = async { loadOrEmpty { loadTopGames() } }
                    val recommendedStreams = recommendations.await()
                    val topGames = games.await()
                    val trendingGame = topGames.firstOrNull()
                    val trendingStreams = trendingGame?.let { loadOrEmpty { loadGameStreams(it) } }.orEmpty()
                    _state.value = DiscoverState(
                        sections = contentSections(topStreams, recommendedStreams, trendingGame, trendingStreams, topGames),
                        trendingGame = trendingGame,
                        hasError = topStreams.isEmpty() && recommendedStreams.isEmpty() && topGames.isEmpty(),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.value = DiscoverState(sections = emptySections(), hasError = true)
            }
        }
    }

    private suspend fun loadTopStreams(): List<Stream> {
        val networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        return TopStreamsPageLoader(
            gqlQueryLanguages = null,
            gqlQuerySort = StreamSort.VIEWER_COUNT,
            gqlLanguages = null,
            gqlSort = "VIEWER_COUNT",
            tags = null,
            gqlHeaders = { TwitchApiHelper.getGQLHeaders(applicationContext) },
            graphQLRepository = graphQLRepository,
            helixHeaders = { TwitchApiHelper.getHelixHeaders(applicationContext) },
            helixRepository = helixRepository,
            enableIntegrity = applicationContext.prefs().getBoolean(C.ENABLE_INTEGRITY, false),
            networkLibrary = networkLibrary,
            pageSize = STREAM_LIMIT,
        ).load(null).items
    }

    private suspend fun loadTopGames(): List<Game> {
        val networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        return try {
            val response = graphQLRepository.loadQueryTopGames(
                networkLibrary = networkLibrary,
                headers = TwitchApiHelper.getGQLHeaders(applicationContext),
                tags = null,
                first = CATEGORY_LIMIT,
                after = null,
            )
            if (applicationContext.prefs().getBoolean(C.ENABLE_INTEGRITY, false) &&
                response.errors?.any { it.message == C.FAILED_INTEGRITY_CHECK } == true
            ) {
                throw IllegalStateException(C.FAILED_INTEGRITY_CHECK)
            }
            response.data?.games?.edges.orEmpty().mapNotNull { edge ->
                edge?.node?.let { game ->
                    Game(
                        id = game.id,
                        slug = game.slug,
                        name = game.displayName,
                        boxArtURL = game.boxArtURL,
                        viewerCount = game.viewersCount,
                        broadcasterCount = game.broadcastersCount,
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val headers = TwitchApiHelper.getHelixHeaders(applicationContext)
            if (headers[C.HEADER_TOKEN].isNullOrBlank()) throw error
            helixRepository.getTopGames(
                networkLibrary = networkLibrary,
                headers = headers,
                limit = CATEGORY_LIMIT,
                offset = null,
            ).data.map { game ->
                Game(id = game.id, name = game.name, boxArtURL = game.boxArtURL)
            }
        }
    }

    private suspend fun loadGameStreams(game: Game): List<Stream> {
        val networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        return GameStreamsPageLoader(
            gameId = game.id,
            gameSlug = game.slug,
            gameName = game.name,
            gqlQueryLanguages = null,
            gqlQuerySort = StreamSort.VIEWER_COUNT,
            gqlLanguages = null,
            gqlSort = "VIEWER_COUNT",
            tags = null,
            gqlHeaders = { TwitchApiHelper.getGQLHeaders(applicationContext) },
            graphQLRepository = graphQLRepository,
            helixHeaders = { TwitchApiHelper.getHelixHeaders(applicationContext) },
            helixRepository = helixRepository,
            enableIntegrity = applicationContext.prefs().getBoolean(C.ENABLE_INTEGRITY, false),
            networkLibrary = networkLibrary,
            pageSize = STREAM_LIMIT,
        ).load(null).items
    }

    private suspend fun <T> loadOrEmpty(request: suspend () -> List<T>): List<T> {
        return try {
            request()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun loadingSections(): List<FollowingOverviewSection> = listOf(
        section(KEY_BIG_EVENTS, R.string.trending, isLoading = true),
        section(KEY_RECOMMENDED, R.string.discover_live_channels, isLoading = true),
        section(KEY_TRENDING, R.string.discover_trending, isLoading = true),
        section(KEY_CATEGORIES, R.string.discover_categories, loadingType = FollowingOverviewLoadingType.GAME, isLoading = true),
    )

    private fun emptySections(): List<FollowingOverviewSection> = listOf(
        section(KEY_BIG_EVENTS, R.string.trending),
        section(KEY_RECOMMENDED, R.string.discover_live_channels),
        section(KEY_TRENDING, R.string.discover_trending),
        section(KEY_CATEGORIES, R.string.discover_categories),
    )

    private fun contentSections(
        topStreams: List<Stream>,
        recommendations: List<Stream>,
        trendingGame: Game?,
        trendingStreams: List<Stream>,
        topGames: List<Game>,
    ): List<FollowingOverviewSection> = listOf(
        section(KEY_BIG_EVENTS, R.string.trending, streams = topStreams),
        section(KEY_RECOMMENDED, R.string.discover_live_channels, streams = recommendations),
        section(
            key = KEY_TRENDING,
            titleRes = R.string.discover_trending,
            title = trendingGame?.name?.let { applicationContext.getString(R.string.discover_trending_category, it) },
            streams = trendingStreams,
        ),
        section(KEY_CATEGORIES, R.string.discover_categories, games = topGames),
    )

    private fun section(
        key: String,
        titleRes: Int,
        title: CharSequence? = null,
        streams: List<Stream> = emptyList(),
        games: List<Game> = emptyList(),
        isLoading: Boolean = false,
        loadingType: FollowingOverviewLoadingType = FollowingOverviewLoadingType.STREAM,
    ) = FollowingOverviewSection(
        key = key,
        titleRes = titleRes,
        title = title,
        emptyRes = if (key == KEY_CATEGORIES) R.string.discover_no_categories else R.string.discover_no_live_channels,
        streams = streams,
        games = games,
        isLoading = isLoading,
        loadingType = loadingType,
        showSeeAll = key == KEY_TRENDING || key == KEY_CATEGORIES,
        isFeatured = key == KEY_BIG_EVENTS,
    )

    companion object {
        const val KEY_BIG_EVENTS = "discover_big_events"
        const val KEY_RECOMMENDED = "discover_recommended"
        const val KEY_TRENDING = "discover_trending"
        const val KEY_CATEGORIES = "discover_categories"
        private const val STREAM_LIMIT = 12
        private const val CATEGORY_LIMIT = 12

        val Factory = viewModelFactory {
            initializer {
                val app = (this[APPLICATION_KEY] as XtraApp)
                val module = app.xtraModule
                DiscoverViewModel(
                    applicationContext = app.applicationContext,
                    graphQLRepository = module.graphQLRepository,
                    helixRepository = module.helixRepository,
                    recommendationsRepository = module.recommendationsRepository,
                )
            }
        }
    }
}
