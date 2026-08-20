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
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
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

    val liveStreams: Flow<List<Stream>> = accountId.flatMapLatest { userId ->
        streamFeedCache.activeItemsFlow(
            feedKey = StreamFeedKey.followed(userId),
            limit = LIVE_SHELF_LIMIT,
        ).map { items -> items.map { it.toStream() } }
    }

    val continueWatching: Flow<List<VideoHistory>> = playerRepository.loadContinueWatching(CONTINUE_WATCHING_LIMIT)

    private val _overviewSectionKeys = MutableStateFlow(readOverviewSectionKeys())
    val overviewSectionKeys: StateFlow<List<String>> = _overviewSectionKeys

    private val _recommendedStreams = MutableStateFlow<List<Stream>>(emptyList())
    val recommendedStreams: Flow<List<Stream>> = _recommendedStreams

    fun syncCurrentAccount() {
        accountId.value = readCurrentUserId()
    }

    fun refreshOverviewSections() {
        val keys = readOverviewSectionKeys()
        _overviewSectionKeys.value = keys
        if (FollowingOverviewSections.RECOMMENDED !in keys) {
            _recommendedStreams.value = emptyList()
        } else {
            refreshRecommendations()
        }
    }

    fun refreshRecommendations() {
        viewModelScope.launch {
            _recommendedStreams.value = runCatching {
                recommendationsRepository.getLiveRecommendations(RECOMMENDED_LIMIT)
            }.getOrDefault(emptyList())
        }
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
