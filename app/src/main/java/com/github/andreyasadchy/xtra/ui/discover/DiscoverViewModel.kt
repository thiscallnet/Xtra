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
import com.github.andreyasadchy.xtra.repository.RecommendationsRepository
import com.github.andreyasadchy.xtra.repository.datasource.withHelixBroadcasterTypes
import com.github.andreyasadchy.xtra.repository.gamefeed.GameFeedCache
import com.github.andreyasadchy.xtra.repository.gamefeed.GameFeedSpec
import com.github.andreyasadchy.xtra.repository.gamefeed.GameFeedSpecs
import com.github.andreyasadchy.xtra.repository.gamefeed.GameRefreshDecision
import com.github.andreyasadchy.xtra.repository.gamefeed.toGame
import com.github.andreyasadchy.xtra.repository.streamfeed.RefreshReason
import com.github.andreyasadchy.xtra.repository.streamfeed.RefreshDecision
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedCache
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedRefreshCoordinator
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedSpec
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedSpecs
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedFreshnessPolicy
import com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog
import com.github.andreyasadchy.xtra.ui.common.SectionStatus
import com.github.andreyasadchy.xtra.ui.following.overview.FollowingOverviewLoadingType
import com.github.andreyasadchy.xtra.ui.following.overview.FollowingOverviewSection
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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
    val sourceGame: Game? = null,
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
    private var currentTrendingSpec: StreamFeedSpec? = null
    private var lastVisibleRefreshAt = 0L
    private var recommendationsRefreshGeneration = 0L
    private var topStreamsJob: Job? = null
    private var topGamesJob: Job? = null
    private var recommendationsJob: Job? = null
    private var trendingRefreshJob: Job? = null
    private var trendingRefreshKey: String? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<DiscoverState> = combine(
        topStreamsSection,
        recommendationsSection,
        topGamesSection,
        trendingStreamsSection,
    ) { topStreams, recommendations, topGames, trendingStreams ->
        val trendingGame = topGames.data.firstOrNull()
        val trending: DiscoverSectionState<List<Stream>> = when {
            trendingGame == null -> DiscoverSectionState(emptyList<Stream>(), topGames.refreshing, topGames.hasLoadedOnce, topGames.error)
            trendingStreams.sourceGame?.let { it.id == trendingGame.id && it.slug == trendingGame.slug && it.name == trendingGame.name } == true -> trendingStreams
            else -> DiscoverSectionState(emptyList(), refreshing = true)
        }
        val topChannelIds = topStreams.data.mapNotNull { it.channelId }.toSet()
        val recommendedStreams = recommendations.data.filterNot { stream ->
            stream.channelId?.let(topChannelIds::contains) == true
        }
        val sections = listOf(
            section(KEY_BIG_EVENTS, R.string.trending, streams = topStreams.data, isLoading = topStreams.isLoading(), status = topStreams.status()),
            section(KEY_RECOMMENDED, R.string.discover_live_channels, streams = recommendedStreams, isLoading = recommendations.isLoading(), status = recommendations.status()),
            section(
                key = KEY_TRENDING,
                titleRes = R.string.discover_trending,
                title = trendingGame?.name?.let { applicationContext.getString(R.string.discover_trending_category, it) },
                streams = trending.data,
                isLoading = trending.isLoading(),
                status = trending.status(),
            ),
            section(KEY_CATEGORIES, R.string.discover_categories, games = topGames.data, loadingType = FollowingOverviewLoadingType.GAME, isLoading = topGames.isLoading(), status = topGames.status()),
        )
        DiscoverState(
            sections = sections,
            trendingGame = trendingGame,
            isLoading = topStreams.refreshing || recommendations.refreshing || topGames.refreshing || trending.refreshing,
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
                if (reason == RefreshReason.SCREEN_VISIBLE) refreshRecommendations()
                return
            }
            lastVisibleRefreshAt = now
        }
        refreshTopStreams(reason, force)
        refreshTopGames(reason, force)
        refreshRecommendations()
        currentTrendingSpec?.let { refreshTrending(it, reason, force) }
    }

    private fun refreshTopStreams(reason: RefreshReason, force: Boolean) {
        if (!force && streamFeedRefreshCoordinator.isFreshInMemory(topStreamsSpec)) return
        if (topStreamsJob?.isActive == true) return
        topStreamsJob = viewModelScope.launch {
            topStreamsSection.update { it.copy(refreshing = true) }
            try {
                val result = if (force) streamFeedRefreshCoordinator.forceRefresh(topStreamsSpec, reason)
                else streamFeedRefreshCoordinator.maybeRefresh(topStreamsSpec, reason)
                completeStreamRefresh(topStreamsSection, result.decision)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                topStreamsSection.update { current -> current.copy(refreshing = false, error = error) }
            }
        }
    }

    private fun refreshTopGames(reason: RefreshReason, force: Boolean) {
        if (!force && gameFeedRefreshCoordinator.isFreshInMemory(topGamesSpec)) return
        if (topGamesJob?.isActive == true) return
        topGamesJob = viewModelScope.launch {
            topGamesSection.update { it.copy(refreshing = true) }
            try {
                val result = if (force) gameFeedRefreshCoordinator.forceRefresh(topGamesSpec, reason)
                else gameFeedRefreshCoordinator.maybeRefresh(topGamesSpec, reason)
                topGamesSection.update { current -> current.copy(
                    refreshing = false,
                    hasLoadedOnce = current.hasLoadedOnce || result.decision == GameRefreshDecision.REFRESH || result.decision == GameRefreshDecision.SKIP_FRESH,
                    error = when (result.decision) {
                        GameRefreshDecision.SKIP_BACKOFF -> current.error ?: IllegalStateException("Category refresh deferred")
                        GameRefreshDecision.SKIP_DEBOUNCED, GameRefreshDecision.JOIN -> current.error ?: if (!current.hasLoadedOnce && current.data.isEmpty()) IllegalStateException("Categories not loaded") else null
                        else -> null
                    },
                ) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                topGamesSection.update { current -> current.copy(refreshing = false, error = error) }
            }
        }
    }

    private fun refreshRecommendations() {
        if (recommendationsJob?.isActive == true) return
        recommendationsJob = viewModelScope.launch {
            recommendationsMutex.withLock {
                recommendationsSection.update { it.copy(refreshing = true) }
                try {
                    val result = recommendationsRepository.getLiveRecommendations(
                        limit = STREAM_LIMIT,
                        excludedChannelIds = topStreamsSection.value.data.mapNotNull { it.channelId }.toSet(),
                    )
                    val streams = result.streams.withHelixBroadcasterTypes(
                        networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                        headers = TwitchApiHelper.getHelixHeaders(applicationContext),
                        helixRepository = helixRepository,
                    )
                    recommendationsSection.update { current ->
                        if (result.isFailure) {
                            current.copy(refreshing = false, error = IllegalStateException("Recommendations unavailable"))
                        } else {
                            val recommendationsChanged = !result.isCacheHit ||
                                !recommendationStreamsSame(current.data, streams)
                            val generation = if (recommendationsChanged) {
                                ++recommendationsRefreshGeneration
                            } else {
                                current.data.firstOrNull()?.thumbnailGeneration
                                    ?: recommendationsRefreshGeneration
                            }
                            current.copy(
                                data = if (recommendationsChanged) {
                                    streams.map { stream ->
                                        stream.withThumbnailGeneration(generation)
                                    }
                                } else {
                                    current.data
                                },
                                refreshing = false,
                                hasLoadedOnce = true,
                                error = null,
                            )
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    recommendationsSection.update { current -> current.copy(refreshing = false, error = error) }
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
        if (trendingRefreshKey == spec.key.value && trendingRefreshJob?.isActive == true) return
        val job = currentCoroutineContext()[Job]
        trendingRefreshKey = spec.key.value
        trendingRefreshJob = job
        trendingStreamsSection.update { it.copy(refreshing = true) }
        try {
            val result = if (force) streamFeedRefreshCoordinator.forceRefresh(spec, reason)
            else streamFeedRefreshCoordinator.maybeRefresh(spec, reason)
            if (currentTrendingSpec?.key == spec.key) completeStreamRefresh(trendingStreamsSection, result.decision)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (currentTrendingSpec?.key == spec.key) trendingStreamsSection.update { it.copy(refreshing = false, error = error) }
        } finally {
            if (trendingRefreshJob === job) trendingRefreshJob = null
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
            trendingStreamsSection.value = DiscoverSectionState(emptyList(), refreshing = true, sourceGame = game)
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
        status: SectionStatus? = null,
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
        status = status,
    )

    private fun <T> DiscoverSectionState<List<T>>.isLoading(): Boolean = refreshing && !hasLoadedOnce && data.isEmpty()

    private fun <T> DiscoverSectionState<List<T>>.status(): SectionStatus? = when {
        refreshing && (hasLoadedOnce || data.isNotEmpty() || error != null) -> SectionStatus(true, data.isNotEmpty())
        error != null -> SectionStatus(false, data.isNotEmpty())
        else -> null
    }

    private fun completeStreamRefresh(section: MutableStateFlow<DiscoverSectionState<List<Stream>>>, decision: RefreshDecision) {
        section.update { current -> current.copy(
            refreshing = false,
            hasLoadedOnce = current.hasLoadedOnce || decision == RefreshDecision.REFRESH || decision == RefreshDecision.SKIP_FRESH,
            error = when (decision) {
                RefreshDecision.SKIP_BACKOFF -> current.error ?: IllegalStateException("Stream refresh deferred")
                RefreshDecision.SKIP_DEBOUNCED, RefreshDecision.JOIN -> current.error ?: if (!current.hasLoadedOnce && current.data.isEmpty()) IllegalStateException("Streams not loaded") else null
                else -> null
            },
        ) }
    }

    fun retrySection(key: String) {
        when (key) {
            KEY_BIG_EVENTS -> if (topStreamsSection.value.error != null) refreshTopStreams(RefreshReason.USER_PULL, force = true)
            KEY_RECOMMENDED -> if (recommendationsSection.value.error != null) refreshRecommendations()
            KEY_CATEGORIES -> if (topGamesSection.value.error != null) refreshTopGames(RefreshReason.USER_PULL, force = true)
            KEY_TRENDING -> currentTrendingSpec?.let { spec ->
                if (trendingStreamsSection.value.error != null) refreshTrending(spec, RefreshReason.USER_PULL, force = true)
            } ?: if (topGamesSection.value.error != null) refreshTopGames(RefreshReason.USER_PULL, force = true) else Unit
        }
    }

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
                    streamFeedCache = module.streamFeedCache,
                    streamFeedRefreshCoordinator = module.streamFeedRefreshCoordinator,
                    gameFeedCache = module.gameFeedCache,
                    gameFeedRefreshCoordinator = module.gameFeedRefreshCoordinator,
                )
            }
        }
    }
}

private fun recommendationStreamsSame(
    first: List<Stream>,
    second: List<Stream>,
): Boolean = first.size == second.size && first.zip(second).all { (old, new) ->
    old.id == new.id &&
        old.channelId == new.channelId &&
        old.channelLogin == new.channelLogin &&
        old.channelName == new.channelName &&
        old.channelImageURL == new.channelImageURL &&
        old.gameId == new.gameId &&
        old.gameSlug == new.gameSlug &&
        old.gameName == new.gameName &&
        old.title == new.title &&
        old.thumbnailURL == new.thumbnailURL &&
        old.createdAt == new.createdAt &&
        old.viewerCount == new.viewerCount &&
        old.tags == new.tags &&
        old.broadcasterType == new.broadcasterType &&
        old.dropsAvailable == new.dropsAvailable
}
