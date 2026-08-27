package com.github.andreyasadchy.xtra.ui.discover

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.RecommendationSource
import com.github.andreyasadchy.xtra.repository.RecommendationsRepository
import com.github.andreyasadchy.xtra.repository.gamefeed.GameFeedCache
import com.github.andreyasadchy.xtra.repository.gamefeed.GameFeedSpec
import com.github.andreyasadchy.xtra.repository.gamefeed.GameFeedSpecs
import com.github.andreyasadchy.xtra.repository.gamefeed.toGame
import com.github.andreyasadchy.xtra.repository.streamfeed.RefreshReason
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedCache
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedRefreshCoordinator
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedSpec
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedSpecs
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedFreshnessPolicy
import com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog
import com.github.andreyasadchy.xtra.ui.following.overview.FollowingOverviewLoadingType
import com.github.andreyasadchy.xtra.ui.following.overview.FollowingOverviewSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DiscoverSectionState<T>(
    val data: T,
    val refreshing: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val error: Throwable? = null,
)

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
    private val streamFeedCache: StreamFeedCache,
    private val streamFeedRefreshCoordinator: StreamFeedRefreshCoordinator,
    private val gameFeedCache: GameFeedCache,
    private val gameFeedRefreshCoordinator: com.github.andreyasadchy.xtra.repository.gamefeed.GameFeedRefreshCoordinator,
) : ViewModel() {

    private val topStreamsSpec = StreamFeedSpecs.top(
        context = applicationContext,
        graphQLRepository = graphQLRepository,
        helixRepository = helixRepository,
        sort = StreamsSortDialog.SORT_VIEWERS,
        tags = null,
        languages = null,
    )
    private val topGamesSpec = GameFeedSpecs.top(
        context = applicationContext,
        graphQLRepository = graphQLRepository,
        helixRepository = helixRepository,
        tags = null,
    )

    private val topStreamsSection = MutableStateFlow(
        DiscoverSectionState<List<Stream>>(emptyList(), refreshing = true),
    )
    private val recommendationsSection = MutableStateFlow(
        DiscoverSectionState<List<Stream>>(emptyList(), refreshing = true),
    )
    private val topGamesSection = MutableStateFlow(
        DiscoverSectionState<List<Game>>(emptyList(), refreshing = true),
    )
    private val trendingStreamsSection = MutableStateFlow(
        DiscoverSectionState<List<Stream>>(emptyList(), refreshing = true),
    )
    private val recommendationsMutex = Mutex()
    private var recommendationsLastAttemptAt = 0L
    private var currentTrendingSpec: StreamFeedSpec? = null
    private var lastVisibleRefreshAt = 0L

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<DiscoverState> = combine(
        topStreamsSection,
        recommendationsSection,
        topGamesSection,
        trendingStreamsSection,
    ) { topStreams, recommendations, topGames, trendingStreams ->
        val trendingGame = topGames.data.firstOrNull()
        val topChannelIds = topStreams.data.mapNotNull { it.channelId }.toSet()
        val recommendedStreams = recommendations.data.filterNot { stream ->
            stream.channelId?.let(topChannelIds::contains) == true
        }
        val sections = listOf(
            section(KEY_BIG_EVENTS, R.string.trending, streams = topStreams.data, isLoading = topStreams.isLoading()),
            section(KEY_RECOMMENDED, R.string.discover_live_channels, streams = recommendedStreams, isLoading = recommendations.isLoading()),
            section(
                key = KEY_TRENDING,
                titleRes = R.string.discover_trending,
                title = trendingGame?.name?.let { applicationContext.getString(R.string.discover_trending_category, it) },
                streams = trendingStreams.data,
                isLoading = trendingStreams.isLoading(),
            ),
            section(KEY_CATEGORIES, R.string.discover_categories, games = topGames.data, loadingType = FollowingOverviewLoadingType.GAME, isLoading = topGames.isLoading()),
        )
        DiscoverState(
            sections = sections,
            trendingGame = trendingGame,
            isLoading = sections.any { it.isLoading },
            hasError = topStreams.data.isEmpty() &&
                recommendations.data.isEmpty() &&
                topGames.data.isEmpty() &&
                trendingStreams.data.isEmpty() &&
                sections.none { it.isLoading } &&
                listOf(topStreams, recommendations, topGames, trendingStreams).any { it.error != null },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DiscoverState())

    init {
        viewModelScope.launch {
            streamFeedCache.activeItemsFlow(topStreamsSpec.key, STREAM_LIMIT).collect { items ->
                topStreamsSection.update { current ->
                    current.copy(
                        data = items,
                        hasLoadedOnce = current.hasLoadedOnce || items.isNotEmpty(),
                        error = if (items.isNotEmpty()) null else current.error,
                    )
                }
            }
        }
        viewModelScope.launch {
            gameFeedCache.activeItemsFlow(topGamesSpec.key, CATEGORY_LIMIT).collect { items ->
                topGamesSection.update { current ->
                    current.copy(
                        data = items.map { it.toGame() },
                        hasLoadedOnce = current.hasLoadedOnce || items.isNotEmpty(),
                        error = if (items.isNotEmpty()) null else current.error,
                    )
                }
            }
        }
        viewModelScope.launch {
            topGamesSection
                .map { it.data.firstOrNull() }
                .distinctUntilChanged { old, new -> old?.id == new?.id && old?.slug == new?.slug && old?.name == new?.name }
                .collectLatestTrending()
        }
    }

    fun refresh(reason: RefreshReason = RefreshReason.INITIAL, force: Boolean = false) {
        if (!force &&
            reason != RefreshReason.USER_PULL &&
            reason != RefreshReason.FILTER_CHANGED
        ) {
            val now = System.currentTimeMillis()
            if (now - lastVisibleRefreshAt < StreamFeedFreshnessPolicy.VISIBLE_REVALIDATION_INTERVAL_MS) {
                return
            }
            lastVisibleRefreshAt = now
        }
        refreshTopStreams(reason, force)
        refreshTopGames(reason, force)
        refreshRecommendations(force)
        currentTrendingSpec?.let { refreshTrending(it, reason, force) }
    }

    private fun refreshTopStreams(reason: RefreshReason, force: Boolean) {
        if (!force && streamFeedRefreshCoordinator.isFreshInMemory(topStreamsSpec)) return
        viewModelScope.launch {
            topStreamsSection.update { current ->
                if (current.hasLoadedOnce) current else current.copy(refreshing = true)
            }
            try {
                if (force) streamFeedRefreshCoordinator.forceRefresh(topStreamsSpec, reason)
                else streamFeedRefreshCoordinator.maybeRefresh(topStreamsSpec, reason)
                topStreamsSection.update { current -> current.copy(refreshing = false, hasLoadedOnce = current.hasLoadedOnce || current.data.isNotEmpty(), error = if (current.data.isNotEmpty()) null else current.error) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                topStreamsSection.update { current -> current.copy(refreshing = false, error = if (current.data.isEmpty()) error else null) }
            }
        }
    }

    private fun refreshTopGames(reason: RefreshReason, force: Boolean) {
        if (!force && gameFeedRefreshCoordinator.isFreshInMemory(topGamesSpec)) return
        viewModelScope.launch {
            topGamesSection.update { current ->
                if (current.hasLoadedOnce) current else current.copy(refreshing = true)
            }
            try {
                if (force) gameFeedRefreshCoordinator.forceRefresh(topGamesSpec, reason)
                else gameFeedRefreshCoordinator.maybeRefresh(topGamesSpec, reason)
                topGamesSection.update { current -> current.copy(refreshing = false, hasLoadedOnce = current.hasLoadedOnce || current.data.isNotEmpty(), error = if (current.data.isNotEmpty()) null else current.error) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                topGamesSection.update { current -> current.copy(refreshing = false, error = if (current.data.isEmpty()) error else null) }
            }
        }
    }

    private fun refreshRecommendations(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - recommendationsLastAttemptAt < RECOMMENDATIONS_TTL_MS) return
        recommendationsLastAttemptAt = now
        viewModelScope.launch {
            recommendationsMutex.withLock {
                recommendationsSection.update { it.copy(refreshing = true) }
                try {
                    val result = recommendationsRepository.getLiveRecommendations(
                        limit = STREAM_LIMIT,
                        excludedChannelIds = topStreamsSection.value.data.mapNotNull { it.channelId }.toSet(),
                    )
                    recommendationsSection.update { current ->
                        if (result.source == RecommendationSource.UNAVAILABLE && current.data.isNotEmpty()) {
                            current.copy(refreshing = false, hasLoadedOnce = true, error = null)
                        } else {
                            current.copy(data = result.streams, refreshing = false, hasLoadedOnce = true, error = null)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    recommendationsSection.update { current -> current.copy(refreshing = false, error = if (current.data.isEmpty()) error else null) }
                }
            }
        }
    }

    private fun refreshTrending(spec: StreamFeedSpec, reason: RefreshReason, force: Boolean) {
        if (!force && streamFeedRefreshCoordinator.isFreshInMemory(spec)) return
        viewModelScope.launch {
            refreshTrendingNow(spec, reason, force)
        }
    }

    private suspend fun refreshTrendingNow(spec: StreamFeedSpec, reason: RefreshReason, force: Boolean) {
        trendingStreamsSection.update { current ->
            if (current.hasLoadedOnce) current else current.copy(refreshing = true)
        }
        try {
            if (force) streamFeedRefreshCoordinator.forceRefresh(spec, reason)
            else streamFeedRefreshCoordinator.maybeRefresh(spec, reason)
            trendingStreamsSection.update { current ->
                current.copy(
                    refreshing = false,
                    hasLoadedOnce = current.hasLoadedOnce || current.data.isNotEmpty(),
                    error = if (current.data.isNotEmpty()) null else current.error,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            trendingStreamsSection.update { current ->
                current.copy(refreshing = false, error = if (current.data.isEmpty()) error else null)
            }
        }
    }

    private suspend fun kotlinx.coroutines.flow.Flow<Game?>.collectLatestTrending() {
        collectLatest { game ->
            val spec = game?.let(::trendingSpec)
            currentTrendingSpec = spec
            if (spec == null) {
                trendingStreamsSection.update { current ->
                    current.copy(
                        data = emptyList(),
                        refreshing = topGamesSection.value.refreshing,
                        hasLoadedOnce = false,
                        error = null,
                    )
                }
                return@collectLatest
            }
            trendingStreamsSection.value = DiscoverSectionState(emptyList(), refreshing = true)
            coroutineScope {
                // Both the refresh and the Room bootstrap collector are children
                // of collectLatest. Switching the trending game cancels both;
                // no orphaned refresh for the previous game survives.
                launch { refreshTrendingNow(spec, RefreshReason.INITIAL, force = false) }
                streamFeedCache.activeItemsFlow(spec.key, STREAM_LIMIT).collect { items ->
                    trendingStreamsSection.update { current ->
                        current.copy(
                            data = items,
                            hasLoadedOnce = current.hasLoadedOnce || items.isNotEmpty(),
                            error = if (items.isNotEmpty()) null else current.error,
                        )
                    }
                }
            }
        }
    }

    private fun trendingSpec(game: Game): StreamFeedSpec = StreamFeedSpecs.game(
        context = applicationContext,
        graphQLRepository = graphQLRepository,
        helixRepository = helixRepository,
        gameId = game.id,
        gameSlug = game.slug,
        gameName = game.name,
        sort = StreamsSortDialog.SORT_VIEWERS,
        tags = null,
        languages = null,
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

    private fun <T> DiscoverSectionState<List<T>>.isLoading(): Boolean = refreshing && !hasLoadedOnce && data.isEmpty()

    companion object {
        const val KEY_BIG_EVENTS = "discover_big_events"
        const val KEY_RECOMMENDED = "discover_recommended"
        const val KEY_TRENDING = "discover_trending"
        const val KEY_CATEGORIES = "discover_categories"
        private const val STREAM_LIMIT = 12
        private const val CATEGORY_LIMIT = 12
        private const val RECOMMENDATIONS_TTL_MS = 5 * 60_000L

        val Factory = viewModelFactory {
            initializer {
                val app = (this[APPLICATION_KEY] as XtraApp)
                val module = app.xtraModule
                DiscoverViewModel(
                    applicationContext = app.applicationContext,
                    graphQLRepository = module.graphQLRepository,
                    helixRepository = module.helixRepository,
                    recommendationsRepository = module.recommendationsRepository,
                    streamFeedCache = module.streamFeedCache,
                    streamFeedRefreshCoordinator = module.streamFeedRefreshCoordinator,
                    gameFeedCache = module.gameFeedCache,
                    gameFeedRefreshCoordinator = module.gameFeedRefreshCoordinator,
                )
            }
        }
    }
}
