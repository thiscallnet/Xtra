package com.github.andreyasadchy.xtra.ui.following.overview

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Stream
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
import com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
    private val streamSort = MutableStateFlow(readStreamSort())
    private var recommendationsJob: Job? = null
    private var recommendationsGeneration = 0L

    val liveStreams: Flow<List<Stream>> = combine(accountId, streamSort) { userId, sort -> userId to sort }.flatMapLatest { (userId, sort) ->
        streamFeedCache.activeItemsFlow(
            feedKey = StreamFeedKey.followed(userId, sort),
            limit = LIVE_SHELF_LIMIT,
        ).map { items -> items.map { it.toStream() } }
    }

    private val allLiveChannelIds: Flow<Set<String>> = combine(accountId, streamSort) { userId, sort -> userId to sort }.flatMapLatest { (userId, sort) ->
        streamFeedCache.allActiveItemsFlow(StreamFeedKey.followed(userId, sort))
            .map { items -> items.mapNotNull { it.channelId }.toSet() }
    }

    val continueWatching: Flow<List<VideoHistory>> = playerRepository.loadContinueWatching(CONTINUE_WATCHING_LIMIT)

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
        }
        streamSort.value = readStreamSort()
    }

    fun refreshOverviewSections() {
        val keys = readOverviewSectionKeys()
        _overviewSectionKeys.value = keys
        if (FollowingOverviewSections.RECOMMENDED !in keys) {
            cancelRecommendations()
        } else {
            refreshRecommendations()
        }
    }

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

    fun currentFeedSpec(): StreamFeedSpec {
        syncCurrentAccount()
        return createSpec(accountId.value)
    }

    fun refreshCurrent(reason: RefreshReason, force: Boolean = false) {
        val spec = currentFeedSpec()
        viewModelScope.launch {
            runCatching {
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
