package com.github.andreyasadchy.xtra.ui.following.overview

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.graphql.type.BroadcastType
import com.github.andreyasadchy.xtra.graphql.type.VideoSort
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.UpcomingStream
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.model.VideoHistory
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.LocalChannelFollowsRepository
import com.github.andreyasadchy.xtra.repository.RecommendationsRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.repository.streamfeed.RefreshReason
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedCache
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedRefreshCoordinator
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedSpec
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedSpecs
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedKey
import com.github.andreyasadchy.xtra.repository.streamfeed.toStream
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class FollowingOverviewViewModel(
    applicationContext: Context,
    private val localChannelFollowsRepository: LocalChannelFollowsRepository,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val streamFeedCache: StreamFeedCache,
    val refreshCoordinator: StreamFeedRefreshCoordinator,
    private val recommendationsRepository: RecommendationsRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    private val applicationContext = applicationContext
    private val accountId = MutableStateFlow(readCurrentUserId())
    private var recommendationsJob: Job? = null
    private var recommendationsGeneration = 0L
    private var overviewContentJob: Job? = null
    private var overviewContentGeneration = 0L

    val liveStreams: Flow<List<Stream>> = accountId.flatMapLatest { userId ->
        streamFeedCache.activeItemsFlow(
            feedKey = StreamFeedKey.followed(userId),
            limit = LIVE_SHELF_LIMIT,
        ).map { items -> items.map { it.toStream() } }
    }

    private val allLiveChannelIds: Flow<Set<String>> = accountId.flatMapLatest { userId ->
        streamFeedCache.allActiveItemsFlow(StreamFeedKey.followed(userId))
            .map { items -> items.mapNotNull { it.channelId }.toSet() }
    }

    private val recentFollowedVideos = MutableStateFlow<List<Video>>(emptyList())
    val continueWatching: Flow<List<VideoHistory>> = combine(
        playerRepository.loadContinueWatching(CONTINUE_WATCHING_LIMIT),
        recentFollowedVideos,
    ) { localHistory, recentVideos ->
        mergeContinueWatching(localHistory, recentVideos, CONTINUE_WATCHING_LIMIT)
    }

    private val _recentVideosLoading = MutableStateFlow(false)
    val recentVideosLoading: StateFlow<Boolean> = _recentVideosLoading

    private val _upcomingStreams = MutableStateFlow<List<UpcomingStream>>(emptyList())
    val upcomingStreams: StateFlow<List<UpcomingStream>> = _upcomingStreams

    private val _upcomingStreamsLoading = MutableStateFlow(false)
    val upcomingStreamsLoading: StateFlow<Boolean> = _upcomingStreamsLoading

    private val _overviewSectionKeys = MutableStateFlow(readOverviewSectionKeys())
    val overviewSectionKeys: StateFlow<List<String>> = _overviewSectionKeys

    private val _recommendedStreams = MutableStateFlow<List<Stream>>(emptyList())
    val recommendedStreams: Flow<List<Stream>> = combine(_recommendedStreams, allLiveChannelIds) { recommended, liveChannelIds ->
        recommended.filterNot { it.channelId in liveChannelIds }
    }

    private val _recommendationsLoading = MutableStateFlow(false)
    val recommendationsLoading: StateFlow<Boolean> = _recommendationsLoading

    fun syncCurrentAccount() {
        val newAccountId = readCurrentUserId()
        if (accountId.value != newAccountId) {
            accountId.value = newAccountId
            cancelRecommendations()
            cancelOverviewContent()
        }
    }

    fun refreshOverviewSections() {
        val keys = readOverviewSectionKeys()
        _overviewSectionKeys.value = keys
        if (FollowingOverviewSections.RECOMMENDED !in keys) {
            cancelRecommendations()
        } else {
            refreshRecommendations()
        }
        refreshOverviewContent()
    }

    private fun refreshOverviewContent() {
        val keys = _overviewSectionKeys.value
        val shouldLoadRecentVideos = FollowingOverviewSections.CONTINUE in keys
        val shouldLoadUpcomingStreams = FollowingOverviewSections.UPCOMING in keys
        val generation = ++overviewContentGeneration
        overviewContentJob?.cancel()
        val requestAccountId = accountId.value

        if (!shouldLoadRecentVideos) {
            recentFollowedVideos.value = emptyList()
            _recentVideosLoading.value = false
        } else {
            _recentVideosLoading.value = true
        }
        if (!shouldLoadUpcomingStreams) {
            _upcomingStreams.value = emptyList()
            _upcomingStreamsLoading.value = false
        } else {
            _upcomingStreamsLoading.value = true
        }
        if (!shouldLoadRecentVideos && !shouldLoadUpcomingStreams) return

        overviewContentJob = viewModelScope.launch {
            try {
                coroutineScope {
                    val recentVideos = async {
                        if (shouldLoadRecentVideos) {
                            tryNetworkRequest { loadRecentFollowedVideos() }.orEmpty()
                        } else emptyList()
                    }
                    val followedChannels = async {
                        if (shouldLoadUpcomingStreams) {
                            tryNetworkRequest { loadFollowedChannels() }.orEmpty()
                        } else emptyList()
                    }
                    val loadedRecentVideos = recentVideos.await()
                    val loadedUpcomingStreams = if (shouldLoadUpcomingStreams) {
                        loadUpcomingStreams(followedChannels.await())
                    } else emptyList()
                    if (isCurrentOverviewRequest(generation, requestAccountId)) {
                        recentFollowedVideos.value = loadedRecentVideos
                        _upcomingStreams.value = loadedUpcomingStreams
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (isCurrentOverviewRequest(generation, requestAccountId)) {
                    _recentVideosLoading.value = false
                    _upcomingStreamsLoading.value = false
                }
            }
        }
    }

    private fun cancelOverviewContent() {
        overviewContentGeneration++
        overviewContentJob?.cancel()
        overviewContentJob = null
        recentFollowedVideos.value = emptyList()
        _upcomingStreams.value = emptyList()
        _recentVideosLoading.value = false
        _upcomingStreamsLoading.value = false
    }

    private fun isCurrentOverviewRequest(generation: Long, requestAccountId: String?): Boolean {
        return overviewContentGeneration == generation && accountId.value == requestAccountId
    }

    private suspend fun loadRecentFollowedVideos(): List<Video> {
        val networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true)
        val localChannels = loadLocalChannels()
        if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            tryNetworkRequest {
                val response = graphQLRepository.loadQueryUserFollowedVideos(
                    networkLibrary = networkLibrary,
                    headers = gqlHeaders,
                    sort = VideoSort.TIME,
                    type = listOf(BroadcastType.ARCHIVE),
                    first = RECENT_VOD_LIMIT,
                    after = null,
                )
                if (applicationContext.prefs().getBoolean(C.ENABLE_INTEGRITY, false)) {
                    response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                        throw IllegalStateException(it.message)
                    }
                }
                response.data?.user?.followedVideos?.edges
                    ?.mapNotNull { edge -> edge?.node?.let(::toVideo) }
                    ?: throw IllegalStateException("Missing followed video data")
            }?.let { remoteVideos ->
                return mergeRecentVideos(
                    remoteVideos,
                    loadLocalFollowedVideos(remoteVideos, localChannels, networkLibrary),
                    RECENT_VOD_LIMIT,
                )
            }

            tryNetworkRequest {
                val response = graphQLRepository.loadFollowedVideos(
                    networkLibrary = networkLibrary,
                    headers = gqlHeaders,
                    limit = RECENT_VOD_LIMIT,
                    cursor = null,
                )
                if (applicationContext.prefs().getBoolean(C.ENABLE_INTEGRITY, false)) {
                    response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                        throw IllegalStateException(it.message)
                    }
                }
                response.data?.currentUser?.followedVideos?.edges
                    ?.map { item -> toVideo(item.node) }
                    ?: throw IllegalStateException("Missing followed video data")
            }?.let { remoteVideos ->
                return mergeRecentVideos(
                    remoteVideos,
                    loadLocalFollowedVideos(remoteVideos, localChannels, networkLibrary),
                    RECENT_VOD_LIMIT,
                )
            }
        }

        val channels = loadFollowedChannels()
        val helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext)
        return loadHelixVideos(
            channels = channels.take(RECENT_VOD_CHANNEL_LIMIT),
            networkLibrary = networkLibrary,
            headers = helixHeaders,
        ).let { mergeRecentVideos(emptyList(), it, RECENT_VOD_LIMIT) }
    }

    private suspend fun loadLocalFollowedVideos(
        remoteVideos: List<Video>,
        localChannels: List<FollowedChannel>,
        networkLibrary: String?,
    ): List<Video> {
        val remoteChannelIds = remoteVideos.mapNotNull { it.channelId }.toSet()
        return loadHelixVideos(
            channels = localChannels.filterNot { it.id in remoteChannelIds },
            networkLibrary = networkLibrary,
            headers = TwitchApiHelper.getHelixHeaders(applicationContext),
        )
    }

    private suspend fun loadHelixVideos(
        channels: List<FollowedChannel>,
        networkLibrary: String?,
        headers: Map<String, String>,
    ): List<Video> = coroutineScope {
        val requestSemaphore = Semaphore(RECENT_VOD_REQUEST_CONCURRENCY)
        channels.map { channel ->
            async {
                requestSemaphore.withPermit {
                    tryNetworkRequest {
                        helixRepository.getVideos(
                            networkLibrary = networkLibrary,
                            headers = headers,
                            channelId = channel.id,
                            broadcastType = "archive",
                            sort = "time",
                            limit = RECENT_VODS_PER_CHANNEL,
                        ).data.mapNotNull { item ->
                            item.id?.let { id ->
                                Video(
                                    id = id,
                                    channelId = item.channelId ?: channel.id,
                                    channelLogin = item.channelLogin ?: channel.login,
                                    channelName = item.channelName ?: channel.name,
                                    channelImageURL = channel.imageURL,
                                    title = item.title,
                                    thumbnailURL = item.thumbnailURL,
                                    createdAt = item.createdAt,
                                    viewCount = item.viewCount,
                                    durationSeconds = item.duration?.let(TwitchApiHelper::getDuration),
                                )
                            }
                        }
                    }.orEmpty()
                }
            }
        }.awaitAll().flatten()
    }

    private suspend fun loadUpcomingStreams(channels: List<FollowedChannel>): List<UpcomingStream> {
        val networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext)
        val now = Clock.System.now().toEpochMilliseconds()
        return coroutineScope {
            val requestSemaphore = Semaphore(UPCOMING_REQUEST_CONCURRENCY)
            channels.map { channel ->
                async {
                    requestSemaphore.withPermit {
                        tryNetworkRequest {
                            helixRepository.getStreamSchedule(
                                networkLibrary = networkLibrary,
                                headers = helixHeaders,
                                broadcasterId = channel.id,
                                limit = UPCOMING_SEGMENTS_PER_CHANNEL,
                            ).data?.let { schedule ->
                                schedule.segments.mapNotNull { segment ->
                                    if (!segment.canceledUntil.isNullOrBlank()) return@mapNotNull null
                                    val startTime = segment.startTime?.let(Instant::parseOrNull)
                                        ?.toEpochMilliseconds()
                                        ?.takeIf { it > now }
                                        ?: return@mapNotNull null
                                    UpcomingStream(
                                        id = "${channel.id}:${segment.id ?: startTime}",
                                        channelId = schedule.broadcasterId ?: channel.id,
                                        channelLogin = schedule.broadcasterLogin ?: channel.login,
                                        channelName = schedule.broadcasterName ?: channel.name,
                                        channelImageURL = channel.imageURL,
                                        title = segment.title,
                                        gameName = segment.category?.name,
                                        startTimeMillis = startTime,
                                        endTimeMillis = segment.endTime?.let(Instant::parseOrNull)?.toEpochMilliseconds(),
                                        isRecurring = segment.isRecurring,
                                    )
                                }
                            }.orEmpty()
                        }.orEmpty()
                    }
                }
            }.awaitAll().flatten()
                .distinctBy { it.id }
                .sortedBy { it.startTimeMillis }
                .take(UPCOMING_STREAM_LIMIT)
        }
    }

    private suspend fun loadFollowedChannels(): List<FollowedChannel> {
        val networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true)
        if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            tryNetworkRequest {
                val response = graphQLRepository.loadQueryUserFollowedUsers(
                    networkLibrary = networkLibrary,
                    headers = gqlHeaders,
                    first = FOLLOWED_CHANNEL_LIMIT,
                    after = null,
                )
                if (applicationContext.prefs().getBoolean(C.ENABLE_INTEGRITY, false)) {
                    response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                        throw IllegalStateException(it.message)
                    }
                }
                response.data?.user?.follows?.edges
                    ?.mapNotNull { edge -> edge?.node?.let { node ->
                        node.id?.let { id -> FollowedChannel(id, node.login, node.displayName, node.profileImageURL) }
                    } }
                    ?: throw IllegalStateException("Missing followed channel data")
            }?.let { return mergeLocalChannels(it) }

            tryNetworkRequest {
                val response = graphQLRepository.loadFollowedChannels(
                    networkLibrary = networkLibrary,
                    headers = gqlHeaders,
                    limit = FOLLOWED_CHANNEL_LIMIT,
                    cursor = null,
                )
                if (applicationContext.prefs().getBoolean(C.ENABLE_INTEGRITY, false)) {
                    response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                        throw IllegalStateException(it.message)
                    }
                }
                response.data?.user?.follows?.edges
                    ?.mapNotNull { edge -> edge.node.let { node ->
                        node.id?.let { id -> FollowedChannel(id, node.login, node.displayName, node.profileImageURL) }
                    } }
                    ?: throw IllegalStateException("Missing followed channel data")
            }?.let { return mergeLocalChannels(it) }
        }

        val helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext)
        if (!accountId.value.isNullOrBlank() && !helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            tryNetworkRequest {
                val follows = helixRepository.getUserFollows(
                    networkLibrary = networkLibrary,
                    headers = helixHeaders,
                    userId = accountId.value,
                    limit = FOLLOWED_CHANNEL_LIMIT,
                ).data
                val profiles = follows.mapNotNull { it.id }
                    .chunked(100)
                    .flatMap { ids -> helixRepository.getUsers(networkLibrary, helixHeaders, ids = ids).data }
                    .associateBy { it.id }
                follows.mapNotNull { follow ->
                    follow.id?.let { id ->
                        FollowedChannel(
                            id = id,
                            login = follow.login,
                            name = follow.displayName,
                            imageURL = profiles[id]?.profileImageURL,
                        )
                    }
                }
            }?.let { return mergeLocalChannels(it) }
        }

        return mergeLocalChannels(emptyList())
    }

    private suspend fun mergeLocalChannels(remote: List<FollowedChannel>): List<FollowedChannel> {
        return (remote + loadLocalChannels()).distinctBy { it.id }
    }

    private suspend fun loadLocalChannels(): List<FollowedChannel> {
        return localChannelFollowsRepository.getAll().mapNotNull { follow ->
            follow.userId?.let { id ->
                FollowedChannel(id, follow.userLogin, follow.userName, follow.channelLogo)
            }
        }
    }

    private fun toVideo(node: com.github.andreyasadchy.xtra.graphql.UserFollowedVideosQuery.Node): Video = Video(
        id = node.id,
        channelId = node.owner?.id,
        channelLogin = node.owner?.login,
        channelName = node.owner?.displayName,
        channelImageURL = node.owner?.profileImageURL,
        gameId = node.game?.id,
        gameSlug = node.game?.slug,
        gameName = node.game?.displayName,
        title = node.title,
        thumbnailURL = node.previewThumbnailURL,
        createdAt = node.createdAt?.toString(),
        viewCount = node.viewCount,
        durationSeconds = node.lengthSeconds,
        type = node.broadcastType?.toString(),
        animatedPreviewURL = node.animatedPreviewURL,
    )

    private fun toVideo(node: com.github.andreyasadchy.xtra.model.gql.followed.FollowedVideosResponse.Video): Video = Video(
        id = node.id,
        channelId = node.owner?.id,
        channelLogin = node.owner?.login,
        channelName = node.owner?.displayName,
        channelImageURL = node.owner?.profileImageURL,
        gameId = node.game?.id,
        gameSlug = node.game?.slug,
        gameName = node.game?.displayName,
        title = node.title,
        thumbnailURL = node.previewThumbnailURL,
        createdAt = node.publishedAt,
        viewCount = node.viewCount,
        durationSeconds = node.lengthSeconds,
        animatedPreviewURL = node.animatedPreviewURL,
    )

    private data class FollowedChannel(
        val id: String,
        val login: String?,
        val name: String?,
        val imageURL: String?,
    )

    fun refreshRecommendations() {
        syncCurrentAccount()
        val generation = ++recommendationsGeneration
        recommendationsJob?.cancel()
        val requestAccountId = accountId.value
        recommendationsJob = viewModelScope.launch {
            _recommendationsLoading.value = true
            try {
                val liveChannelIds = allLiveChannelIds.first()
                val result = recommendationsRepository.getLiveRecommendations(RECOMMENDED_LIMIT, liveChannelIds)
                if (isCurrentRecommendationRequest(generation, requestAccountId)) {
                    _recommendedStreams.value = result
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isCurrentRecommendationRequest(generation, requestAccountId)) {
                    _recommendedStreams.value = emptyList()
                }
            } finally {
                if (isCurrentRecommendationRequest(generation, requestAccountId)) {
                    _recommendationsLoading.value = false
                }
            }
        }
    }

    private fun cancelRecommendations() {
        recommendationsGeneration++
        recommendationsJob?.cancel()
        recommendationsJob = null
        _recommendedStreams.value = emptyList()
        _recommendationsLoading.value = false
    }

    private fun isCurrentRecommendationRequest(generation: Long, requestAccountId: String?): Boolean {
        return recommendationsGeneration == generation && accountId.value == requestAccountId
    }

    private suspend fun <T> tryNetworkRequest(block: suspend () -> T): T? {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    fun currentFeedSpec(): StreamFeedSpec {
        syncCurrentAccount()
        return createSpec(accountId.value)
    }

    fun refreshCurrent(reason: RefreshReason, force: Boolean = false) {
        val spec = currentFeedSpec()
        viewModelScope.launch {
            tryNetworkRequest {
                if (force) refreshCoordinator.forceRefresh(spec, reason)
                else refreshCoordinator.maybeRefresh(spec, reason)
            }
        }
    }

    private fun readCurrentUserId(): String? = applicationContext.tokenPrefs().getString(C.USER_ID, null)

    private fun readOverviewSectionKeys(): List<String> = FollowingOverviewSections.visibleKeys(
        applicationContext.prefs().getString(C.UI_FOLLOWING_OVERVIEW_SECTIONS, null),
    )

    private fun createSpec(userId: String?): StreamFeedSpec {
        return StreamFeedSpecs.followed(
            context = applicationContext,
            userId = userId,
            localChannelFollowsRepository = localChannelFollowsRepository,
            graphQLRepository = graphQLRepository,
            helixRepository = helixRepository,
        )
    }

    companion object {
        private const val LIVE_SHELF_LIMIT = 12
        private const val RECOMMENDED_LIMIT = 12
        private const val CONTINUE_WATCHING_LIMIT = 20
        private const val RECENT_VOD_LIMIT = 20
        private const val RECENT_VOD_CHANNEL_LIMIT = 30
        private const val RECENT_VODS_PER_CHANNEL = 3
        private const val RECENT_VOD_REQUEST_CONCURRENCY = 6
        private const val FOLLOWED_CHANNEL_LIMIT = 100
        private const val UPCOMING_REQUEST_CONCURRENCY = 6
        private const val UPCOMING_SEGMENTS_PER_CHANNEL = 3
        private const val UPCOMING_STREAM_LIMIT = 20

        val FollowingOverviewViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                FollowingOverviewViewModel(
                    application.applicationContext,
                    xtraModule.localChannelFollowsRepository,
                    xtraModule.graphQLRepository,
                    xtraModule.helixRepository,
                    xtraModule.streamFeedCache,
                    xtraModule.streamFeedRefreshCoordinator,
                    xtraModule.recommendationsRepository,
                    xtraModule.playerRepository,
                )
            }
        }
    }
}
