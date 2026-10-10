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
import com.github.andreyasadchy.xtra.repository.ChannelStreamStartsRepository
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.LocalChannelFollowsRepository
import com.github.andreyasadchy.xtra.repository.FollowingOverviewCacheSnapshot
import com.github.andreyasadchy.xtra.repository.MetadataCache
import com.github.andreyasadchy.xtra.repository.RecommendationAuthMode
import com.github.andreyasadchy.xtra.repository.RecommendationsRepository
import com.github.andreyasadchy.xtra.repository.RecommendationSource
import com.github.andreyasadchy.xtra.repository.datasource.withHelixBroadcasterTypes
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.repository.streamfeed.RefreshReason
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedCache
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedRefreshCoordinator
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedSpec
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedSpecs
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedKey
import com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog
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
import java.time.ZoneId
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
    private val metadataCache: MetadataCache,
    private val recommendationsRepository: RecommendationsRepository,
    private val playerRepository: PlayerRepository,
    private val channelStreamStartsRepository: ChannelStreamStartsRepository,
) : ViewModel() {

    private val applicationContext = applicationContext
    private val accountId = MutableStateFlow(readCurrentUserId())
    private val streamSort = MutableStateFlow(readStreamSort())
    private var recommendationsJob: Job? = null
    private var recommendationsGeneration = 0L
    private var overviewContentJob: Job? = null
    private var overviewContentGeneration = 0L
    private var lastRecommendationsRefreshAt = 0L
    private var lastRecentVideosRefreshAt = 0L
    private var lastUpcomingStreamsRefreshAt = 0L
    private var lastChannelVodsRefreshAt = 0L
    private var lastOfflineWindowMs = 0L
    private var channelVodsJob: Job? = null
    private var channelVodsGeneration = 0L

    val liveStreams: Flow<List<Stream>> = combine(accountId, streamSort) { userId, sort -> userId to sort }.flatMapLatest { (userId, sort) ->
        streamFeedCache.activeItemsFlow(
            feedKey = StreamFeedKey.followed(userId, sort),
            limit = LIVE_SHELF_LIMIT,
        )
    }

    private val allLiveChannelIds: Flow<Set<String>> = combine(accountId, streamSort) { userId, sort -> userId to sort }.flatMapLatest { (userId, sort) ->
        streamFeedCache.allActiveItemsFlow(StreamFeedKey.followed(userId, sort))
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

    private val _recentVideosResolved = MutableStateFlow(false)
    val recentVideosResolved: StateFlow<Boolean> = _recentVideosResolved

    private val _upcomingStreams = MutableStateFlow<List<UpcomingStream>>(emptyList())
    val upcomingStreams: StateFlow<List<UpcomingStream>> = _upcomingStreams

    private val _upcomingStreamsLoading = MutableStateFlow(false)
    val upcomingStreamsLoading: StateFlow<Boolean> = _upcomingStreamsLoading

    private val _upcomingStreamsResolved = MutableStateFlow(false)
    val upcomingStreamsResolved: StateFlow<Boolean> = _upcomingStreamsResolved

    private val _recentlyOfflineVideos = MutableStateFlow<List<Video>>(emptyList())
    val recentlyOfflineVideos: Flow<List<Video>> = combine(_recentlyOfflineVideos, allLiveChannelIds) { videos, liveChannelIds ->
        videos.filterNot { it.channelId in liveChannelIds }
    }

    private val _expectedStreams = MutableStateFlow<List<UpcomingStream>>(emptyList())
    val expectedStreams: Flow<List<UpcomingStream>> = combine(_expectedStreams, allLiveChannelIds) { expected, liveChannelIds ->
        expected.filterNot { it.channelId in liveChannelIds }
    }

    private val _channelVodsLoading = MutableStateFlow(false)
    val channelVodsLoading: StateFlow<Boolean> = _channelVodsLoading

    private val _channelVodsResolved = MutableStateFlow(false)
    val channelVodsResolved: StateFlow<Boolean> = _channelVodsResolved

    private val _overviewSectionKeys = MutableStateFlow(readOverviewSectionKeys())
    val overviewSectionKeys: StateFlow<List<String>> = _overviewSectionKeys

    private val _recommendedStreams = MutableStateFlow<List<Stream>>(emptyList())
    val recommendedStreams: Flow<List<Stream>> = combine(_recommendedStreams, allLiveChannelIds) { recommended, liveChannelIds ->
        recommended.filterNot { it.channelId in liveChannelIds }
    }

    private val _recommendationsLoading = MutableStateFlow(false)
    val recommendationsLoading: StateFlow<Boolean> = _recommendationsLoading

    private val _recommendationsResolved = MutableStateFlow(false)
    val recommendationsResolved: StateFlow<Boolean> = _recommendationsResolved

    private val _recommendationsFailed = MutableStateFlow(false)
    val recommendationsFailed: StateFlow<Boolean> = _recommendationsFailed

    private val _recommendationSource = MutableStateFlow(RecommendationSource.UNAVAILABLE)
    val recommendationSource: StateFlow<RecommendationSource> = _recommendationSource

    private val _recommendationAuthMode = MutableStateFlow(RecommendationAuthMode.ANONYMOUS)
    val recommendationAuthMode: StateFlow<RecommendationAuthMode> = _recommendationAuthMode

    init {
        viewModelScope.launch {
            channelStreamStartsRepository.cleared.collect {
                lastChannelVodsRefreshAt = 0L
                _expectedStreams.value = emptyList()
            }
        }
    }

    fun syncCurrentAccount() {
        val newAccountId = readCurrentUserId()
        if (accountId.value != newAccountId) {
            accountId.value = newAccountId
            cancelRecommendations(clearData = true)
            cancelOverviewContent()
            lastRecommendationsRefreshAt = 0L
            lastRecentVideosRefreshAt = 0L
            lastUpcomingStreamsRefreshAt = 0L
            lastChannelVodsRefreshAt = 0L
            cancelChannelVods()
        }
        streamSort.value = readStreamSort()
    }

    fun refreshOverviewSections(force: Boolean = false) {
        val keys = readOverviewSectionKeys()
        val now = System.currentTimeMillis()
        val keysChanged = keys != _overviewSectionKeys.value
        val recommendationsInterval = if (_recommendationsFailed.value) {
            RECOMMENDATIONS_RETRY_MS
        } else RECOMMENDATIONS_TTL_MS
        val recommendationsDue = FollowingOverviewSections.RECOMMENDED in keys &&
            (force || now - lastRecommendationsRefreshAt >= recommendationsInterval)
        val recentVideosDue = FollowingOverviewSections.CONTINUE in keys &&
            (force || now - lastRecentVideosRefreshAt >= RECENT_VIDEOS_TTL_MS)
        val upcomingStreamsDue = FollowingOverviewSections.UPCOMING in keys &&
            (force || now - lastUpcomingStreamsRefreshAt >= UPCOMING_STREAMS_TTL_MS)
        val channelVodsVisible = FollowingOverviewSections.RECENTLY_OFFLINE in keys ||
                FollowingOverviewSections.EXPECTED_SOON in keys
        val channelVodsDue = channelVodsVisible &&
            (force || keysChanged || readRecentlyOfflineWindowMs() != lastOfflineWindowMs ||
                now - lastChannelVodsRefreshAt >= CHANNEL_VODS_TTL_MS)
        if (!force && !keysChanged && !recommendationsDue && !recentVideosDue && !upcomingStreamsDue && !channelVodsDue) {
            return
        }
        _overviewSectionKeys.value = keys
        if (FollowingOverviewSections.RECOMMENDED !in keys) {
            cancelRecommendations(clearData = false)
        } else if (force || recommendationsDue) {
            lastRecommendationsRefreshAt = now
            refreshRecommendations()
        }
        if (recentVideosDue) lastRecentVideosRefreshAt = now
        if (upcomingStreamsDue) lastUpcomingStreamsRefreshAt = now
        if (!channelVodsVisible) {
            cancelChannelVods()
        } else if (channelVodsDue) {
            lastChannelVodsRefreshAt = now
            refreshChannelVods()
        }
        refreshOverviewContent(
            reloadRecentVideos = recentVideosDue,
            reloadUpcomingStreams = upcomingStreamsDue,
        )
    }

    private fun refreshOverviewContent(
        reloadRecentVideos: Boolean,
        reloadUpcomingStreams: Boolean,
    ) {
        val keys = _overviewSectionKeys.value
        val shouldLoadRecentVideos = FollowingOverviewSections.CONTINUE in keys
        val shouldLoadUpcomingStreams = FollowingOverviewSections.UPCOMING in keys
        val loadRecentVideos = shouldLoadRecentVideos && reloadRecentVideos
        val loadUpcomingStreams = shouldLoadUpcomingStreams && reloadUpcomingStreams

        if (!shouldLoadRecentVideos) {
            _recentVideosLoading.value = false
        } else if (loadRecentVideos) {
            _recentVideosLoading.value = true
        }
        if (!shouldLoadUpcomingStreams) {
            _upcomingStreamsLoading.value = false
        } else if (loadUpcomingStreams) {
            _upcomingStreamsLoading.value = true
        }
        if (!loadRecentVideos && !loadUpcomingStreams) {
            if (!shouldLoadRecentVideos && !shouldLoadUpcomingStreams) {
                overviewContentGeneration++
                overviewContentJob?.cancel()
                overviewContentJob = null
            }
            return
        }

        val generation = ++overviewContentGeneration
        overviewContentJob?.cancel()
        val requestAccountId = accountId.value

        overviewContentJob = viewModelScope.launch {
            try {
                val cachedOverview = tryNetworkRequest {
                    metadataCache.readFollowingOverview(requestAccountId)
                }
                if (isCurrentOverviewRequest(generation, requestAccountId)) {
                    cachedOverview?.let { cache ->
                        if (loadRecentVideos) {
                            recentFollowedVideos.value = cache.recentVideos
                            _recentVideosResolved.value = true
                        }
                        if (loadUpcomingStreams) {
                            _upcomingStreams.value = cache.upcomingStreams
                            _upcomingStreamsResolved.value = true
                        }
                    }
                }
                coroutineScope {
                    val followedChannels = if (loadUpcomingStreams) {
                        async { loadFollowedChannels() }
                    } else null
                    val recentVideos = async {
                        if (loadRecentVideos) {
                            loadRecentFollowedVideos {
                                if (followedChannels != null) followedChannels.await()
                                else loadFollowedChannels()
                            }
                        } else null
                    }
                    val loadedRecentVideos = recentVideos.await()
                    val loadedUpcomingStreams = if (loadUpcomingStreams) {
                        followedChannels?.await()?.let { loadUpcomingStreams(it, _upcomingStreams.value) }
                    } else null
                    if (isCurrentOverviewRequest(generation, requestAccountId)) {
                        loadedRecentVideos?.let {
                            recentFollowedVideos.value = it
                            _recentVideosResolved.value = true
                        }
                        loadedUpcomingStreams?.let {
                            _upcomingStreams.value = it
                            _upcomingStreamsResolved.value = true
                        }
                        if (loadedRecentVideos != null || loadedUpcomingStreams != null) {
                            tryNetworkRequest {
                                metadataCache.writeFollowingOverview(
                                    userId = requestAccountId,
                                    snapshot = FollowingOverviewCacheSnapshot(
                                        recentVideos = recentFollowedVideos.value,
                                        upcomingStreams = _upcomingStreams.value,
                                    ),
                                    replaceRecentVideos = loadedRecentVideos != null,
                                    replaceUpcomingStreams = loadedUpcomingStreams != null,
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (isCurrentOverviewRequest(generation, requestAccountId)) {
                    _recentVideosLoading.value = false
                    _upcomingStreamsLoading.value = false
                    if (loadRecentVideos) _recentVideosResolved.value = true
                    if (loadUpcomingStreams) _upcomingStreamsResolved.value = true
                }
            }
        }
    }

    private fun refreshChannelVods() {
        val keys = _overviewSectionKeys.value
        val showRecentlyOffline = FollowingOverviewSections.RECENTLY_OFFLINE in keys
        val showExpectedSoon = FollowingOverviewSections.EXPECTED_SOON in keys
        val generation = ++channelVodsGeneration
        channelVodsJob?.cancel()
        val requestAccountId = accountId.value
        val offlineWindowMs = readRecentlyOfflineWindowMs()
        lastOfflineWindowMs = offlineWindowMs
        _channelVodsLoading.value = true
        channelVodsJob = viewModelScope.launch {
            try {
                val channels = if (showExpectedSoon) loadFollowedChannels() else null
                val vods = loadRecentFollowedVideos(CHANNEL_VODS_LIMIT) {
                    channels ?: loadFollowedChannels()
                } ?: return@launch
                val nowMs = System.currentTimeMillis()
                val liveChannelIds = allLiveChannelIds.first()
                val recentlyOffline = if (showRecentlyOffline) {
                    recentlyOfflineVideos(vods, liveChannelIds, nowMs, offlineWindowMs, RECENTLY_OFFLINE_LIMIT)
                } else null
                val expectedStreams = if (showExpectedSoon) {
                    channelStreamStartsRepository.recordStartTimes(vods.mapNotNull { video ->
                        val channelId = video.channelId ?: return@mapNotNull null
                        val startedAt = video.createdAt?.let(Instant::parseOrNull)?.toEpochMilliseconds()
                            ?: return@mapNotNull null
                        channelId to startedAt
                    })
                    // Without the followed list the previous predictions stay in place.
                    channels?.let {
                        val followedChannelIds = it.mapTo(hashSetOf()) { channel -> channel.id }
                        val starts = channelStreamStartsRepository
                            .startsSince(nowMs - EXPECTED_LOOKBACK_MS)
                            .filter { start -> start.channelId in followedChannelIds }
                        val channelsById = it.associateBy { channel -> channel.id }
                        predictExpectedStarts(starts, liveChannelIds, nowMs, EXPECTED_HORIZON_MS, ZoneId.systemDefault())
                            .mapNotNull { expected ->
                                val channel = channelsById[expected.channelId] ?: return@mapNotNull null
                                UpcomingStream(
                                    id = "expected:${channel.id}",
                                    channelId = channel.id,
                                    channelLogin = channel.login,
                                    channelName = channel.name,
                                    channelImageURL = channel.imageURL,
                                    title = null,
                                    gameName = null,
                                    startTimeMillis = expected.expectedAtMs,
                                    endTimeMillis = null,
                                    isRecurring = false,
                                    isPredicted = true,
                                )
                            }
                    }
                } else null
                if (isCurrentChannelVodsRequest(generation, requestAccountId)) {
                    recentlyOffline?.let { _recentlyOfflineVideos.value = it }
                    expectedStreams?.let { _expectedStreams.value = it }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                // A failed fetch keeps the last published shelves and still resolves the loading state.
                if (isCurrentChannelVodsRequest(generation, requestAccountId)) {
                    _channelVodsLoading.value = false
                    _channelVodsResolved.value = true
                }
            }
        }
    }

    private fun cancelChannelVods() {
        channelVodsGeneration++
        channelVodsJob?.cancel()
        channelVodsJob = null
        _recentlyOfflineVideos.value = emptyList()
        _expectedStreams.value = emptyList()
        _channelVodsLoading.value = false
        _channelVodsResolved.value = false
    }

    private fun isCurrentChannelVodsRequest(generation: Long, requestAccountId: String?): Boolean {
        return channelVodsGeneration == generation && accountId.value == requestAccountId
    }

    private fun readRecentlyOfflineWindowMs(): Long {
        val hours = applicationContext.prefs()
            .getString(C.UI_RECENTLY_OFFLINE_WINDOW_HOURS, null)
            ?.toLongOrNull()
            ?: DEFAULT_RECENTLY_OFFLINE_WINDOW_HOURS
        return hours * 3_600_000L
    }

    private fun cancelOverviewContent() {
        overviewContentGeneration++
        overviewContentJob?.cancel()
        overviewContentJob = null
        recentFollowedVideos.value = emptyList()
        _upcomingStreams.value = emptyList()
        _recentVideosLoading.value = false
        _upcomingStreamsLoading.value = false
        _recentVideosResolved.value = false
        _upcomingStreamsResolved.value = false
    }

    private fun isCurrentOverviewRequest(generation: Long, requestAccountId: String?): Boolean {
        return overviewContentGeneration == generation && accountId.value == requestAccountId
    }

    private suspend fun loadRecentFollowedVideos(
        limit: Int = RECENT_VOD_LIMIT,
        followedChannelsLoader: suspend () -> List<FollowedChannel>?,
    ): List<Video>? {
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
                    first = limit,
                    after = null,
                )
                response.data?.user?.followedVideos?.edges
                    ?.mapNotNull { edge -> edge?.node?.let(::toVideo) }
                    ?: throw IllegalStateException("Missing followed video data")
            }?.let { remoteVideos ->
                return mergeRecentVideosWithSupplement(
                    remoteVideos,
                    loadLocalFollowedVideos(remoteVideos, localChannels, networkLibrary),
                    limit,
                ) ?: return null
            }

            tryNetworkRequest {
                val response = graphQLRepository.loadFollowedVideos(
                    networkLibrary = networkLibrary,
                    headers = gqlHeaders,
                    limit = limit,
                    cursor = null,
                )
                response.data?.currentUser?.followedVideos?.edges
                    ?.map { item -> toVideo(item.node) }
                    ?: throw IllegalStateException("Missing followed video data")
            }?.let { remoteVideos ->
                return mergeRecentVideosWithSupplement(
                    remoteVideos,
                    loadLocalFollowedVideos(remoteVideos, localChannels, networkLibrary),
                    limit,
                ) ?: return null
            }
        }

        val channels = followedChannelsLoader() ?: return null
        val helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext)
        val videos = loadHelixVideos(
            channels = channels.take(RECENT_VOD_CHANNEL_LIMIT),
            networkLibrary = networkLibrary,
            headers = helixHeaders,
        ) ?: return null
        return mergeRecentVideos(emptyList(), videos, limit)
    }

    private suspend fun loadLocalFollowedVideos(
        remoteVideos: List<Video>,
        localChannels: List<FollowedChannel>,
        networkLibrary: String?,
    ): List<Video>? {
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
    ): List<Video>? = coroutineScope {
        if (channels.isEmpty()) return@coroutineScope emptyList()
        val requestSemaphore = Semaphore(RECENT_VOD_REQUEST_CONCURRENCY)
        val results = channels.map { channel ->
            async {
                requestSemaphore.withPermit {
                    loadChannelItems(
                        request = {
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
                        },
                    )
                }
            }
        }.awaitAll()
        combineChannelItems(results)
    }

    private suspend fun loadUpcomingStreams(
        channels: List<FollowedChannel>,
        cachedStreams: List<UpcomingStream>,
    ): List<UpcomingStream>? {
        val networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true)
        val helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext)
        val nowMs = Clock.System.now().toEpochMilliseconds()
        return coroutineScope {
            val requestSemaphore = Semaphore(UPCOMING_REQUEST_CONCURRENCY)
            if (channels.isEmpty()) return@coroutineScope emptyList()
            val results = channels.map { channel ->
                async {
                    requestSemaphore.withPermit {
                        loadChannelItems(
                            request = {
                                loadUpcomingChannel(
                                    channel = channel,
                                    networkLibrary = networkLibrary,
                                    gqlHeaders = gqlHeaders,
                                    helixHeaders = helixHeaders,
                                    nowMs = nowMs,
                                )
                            },
                            notFoundItems = emptyList(),
                        )
                    }
                }
            }.awaitAll()
            mergeUpcomingStreams(
                channelIds = channels.map(FollowedChannel::id),
                results = results,
                cachedStreams = cachedStreams,
                nowMs = nowMs,
            )
                ?.take(UPCOMING_STREAM_LIMIT)
        }
    }

    private suspend fun loadUpcomingChannel(
        channel: FollowedChannel,
        networkLibrary: String?,
        gqlHeaders: Map<String, String>,
        helixHeaders: Map<String, String>,
        nowMs: Long,
    ): List<UpcomingStream> {
        channel.login?.takeIf(String::isNotBlank)?.let { login ->
            try {
                val response = graphQLRepository.loadStreamSchedule(
                    networkLibrary = networkLibrary,
                    headers = gqlHeaders,
                    login = login,
                )
                val scheduleUser = response.data?.user
                    ?: throw IllegalStateException("Missing schedule user")
                val scheduleChannel = scheduleUser.channel
                    ?: throw IllegalStateException("Missing schedule channel")
                val segment = selectUpcomingScheduleSegment(scheduleChannel.schedule, nowMs)
                    ?: return emptyList()
                val startTime = segment.startAt?.let(Instant::parseOrNull)
                    ?.toEpochMilliseconds()
                    ?.takeIf { it > nowMs }
                    ?: return emptyList()
                return listOf(
                    UpcomingStream(
                        id = "${channel.id}:${segment.id ?: startTime}",
                        channelId = scheduleChannel.id ?: channel.id,
                        channelLogin = channel.login,
                        channelName = channel.name,
                        channelImageURL = channel.imageURL,
                        previewImageURL = scheduleUser.bannerImageURL,
                        title = segment.title,
                        gameName = segment.categories?.firstOrNull()?.name,
                        startTimeMillis = startTime,
                        endTimeMillis = segment.endAt?.let(Instant::parseOrNull)?.toEpochMilliseconds(),
                        isRecurring = (segment.repeatEndsAfterCount ?: 1) > 1,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Fall back to Helix when Twitch changes or rejects the website query.
            }
        }

        val schedule = helixRepository.getStreamSchedule(
            networkLibrary = networkLibrary,
            headers = helixHeaders,
            broadcasterId = channel.id,
            limit = UPCOMING_SEGMENTS_PER_CHANNEL,
        ).data ?: throw IllegalStateException("Missing schedule data")
        return schedule.segments.mapNotNull { segment ->
            if (!segment.canceledUntil.isNullOrBlank()) return@mapNotNull null
            val startTime = segment.startTime?.let(Instant::parseOrNull)
                ?.toEpochMilliseconds()
                ?.takeIf { it > nowMs }
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
    }

    private suspend fun loadFollowedChannels(): List<FollowedChannel>? {
        val networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val hasAuthenticatedRemoteIdentity = !accountId.value.isNullOrBlank() ||
                !applicationContext.tokenPrefs().getString(C.GQL_TOKEN_WEB, null).isNullOrBlank()
        val remoteFollowLookupAttempted = hasAuthenticatedRemoteIdentity
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true)
        if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            tryNetworkRequest {
                val response = graphQLRepository.loadQueryUserFollowedUsers(
                    networkLibrary = networkLibrary,
                    headers = gqlHeaders,
                    first = FOLLOWED_CHANNEL_LIMIT,
                    after = null,
                )
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

        return fallbackToLocalChannels(remoteFollowLookupAttempted, loadLocalChannels())
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
                if (result.isFailure) {
                    if (isCurrentRecommendationRequest(generation, requestAccountId)) {
                        _recommendationsFailed.value = true
                    }
                    return@launch
                }
                val streams = result.streams.withHelixBroadcasterTypes(
                    networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    headers = TwitchApiHelper.getHelixHeaders(applicationContext),
                    helixRepository = helixRepository,
                )
                if (isCurrentRecommendationRequest(generation, requestAccountId)) {
                    _recommendedStreams.value = streams
                    _recommendationSource.value = result.source
                    _recommendationAuthMode.value = result.authMode
                    _recommendationsFailed.value = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isCurrentRecommendationRequest(generation, requestAccountId)) {
                    _recommendationsFailed.value = true
                }
            } finally {
                if (isCurrentRecommendationRequest(generation, requestAccountId)) {
                    _recommendationsLoading.value = false
                    _recommendationsResolved.value = true
                }
            }
        }
    }

    private fun cancelRecommendations(clearData: Boolean) {
        recommendationsGeneration++
        recommendationsJob?.cancel()
        recommendationsJob = null
        _recommendationsLoading.value = false
        if (clearData) {
            _recommendedStreams.value = emptyList()
            _recommendationSource.value = RecommendationSource.UNAVAILABLE
            _recommendationAuthMode.value = RecommendationAuthMode.ANONYMOUS
            _recommendationsResolved.value = false
            _recommendationsFailed.value = false
        }
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

    private fun readStreamSort(): String = StreamsSortDialog.defaultSort(applicationContext)

    private fun readOverviewSectionKeys(): List<String> = FollowingOverviewSections.visibleKeys(
        applicationContext.prefs().getString(C.UI_FOLLOWING_OVERVIEW_SECTIONS, null),
    )

    private fun createSpec(userId: String?): StreamFeedSpec {
        return StreamFeedSpecs.followed(
            context = applicationContext,
            userId = userId,
            sort = streamSort.value,
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
        private const val RECOMMENDATIONS_TTL_MS = 5 * 60_000L
        private const val RECOMMENDATIONS_RETRY_MS = 45_000L
        private const val RECENT_VIDEOS_TTL_MS = 5 * 60_000L
        private const val FOLLOWED_CHANNEL_LIMIT = 100
        private const val UPCOMING_REQUEST_CONCURRENCY = 6
        private const val UPCOMING_SEGMENTS_PER_CHANNEL = 3
        private const val UPCOMING_STREAM_LIMIT = 20
        private const val UPCOMING_STREAMS_TTL_MS = 15 * 60_000L
        private const val RECENTLY_OFFLINE_LIMIT = 20
        private const val CHANNEL_VODS_LIMIT = 100
        private const val CHANNEL_VODS_TTL_MS = 10 * 60_000L
        private const val EXPECTED_LOOKBACK_MS = 28L * 24 * 60 * 60_000L
        private const val EXPECTED_HORIZON_MS = 12L * 60 * 60_000L
        private const val DEFAULT_RECENTLY_OFFLINE_WINDOW_HOURS = 24L

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
                    xtraModule.metadataCache,
                    xtraModule.recommendationsRepository,
                    xtraModule.playerRepository,
                    xtraModule.channelStreamStartsRepository,
                )
            }
        }
    }
}
