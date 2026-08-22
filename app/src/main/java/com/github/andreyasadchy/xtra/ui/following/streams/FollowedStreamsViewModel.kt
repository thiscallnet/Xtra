package com.github.andreyasadchy.xtra.ui.following.streams

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.LocalChannelFollowsRepository
import com.github.andreyasadchy.xtra.repository.streamfeed.RefreshReason
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedPager
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedRefreshCoordinator
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedSpec
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedSpecs
import com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class FollowedStreamsViewModel(
    applicationContext: Context,
    private val localChannelFollowsRepository: LocalChannelFollowsRepository,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val streamFeedPager: StreamFeedPager,
    val refreshCoordinator: StreamFeedRefreshCoordinator,
) : ViewModel() {

    private val applicationContext = applicationContext
    private val accountId = MutableStateFlow(readCurrentUserId())
    private val streamSort = MutableStateFlow(readStreamSort())
    private val pagingConfig = if (applicationContext.prefs().getString(C.COMPACT_STREAMS, "disabled") != "disabled") {
        PagingConfig(pageSize = 30, prefetchDistance = 10, initialLoadSize = 30)
    } else {
        PagingConfig(pageSize = 30, prefetchDistance = 3, initialLoadSize = 30)
    }
    private val accountPagerGeneration = FollowedAccountPagerGeneration()

    val sort: String
        get() = streamSort.value

    val flow = combine(accountId, streamSort) { userId, sort -> userId to sort }
        .flatMapLatest { (userId, sort) ->
            flow {
                // An account partition change is the one legitimate case
                // where the old rows must be removed before the new cache
                // generation is submitted. The initial generation remains
                // cache-first and does not emit this empty PagingData.
                if (accountPagerGeneration.switchTo(userId)) {
                    emit(PagingData.empty<Stream>())
                }
                emitAll(
                    streamFeedPager.flow(
                        spec = createSpec(userId, sort),
                        config = pagingConfig,
                    )
                )
            }
        }
        .cachedIn(viewModelScope)

    fun syncCurrentAccount() {
        accountId.value = readCurrentUserId()
        streamSort.value = readStreamSort()
    }

    fun currentFeedSpec(): StreamFeedSpec {
        syncCurrentAccount()
        return createSpec(accountId.value, streamSort.value)
    }

    fun refreshCurrent(reason: RefreshReason, force: Boolean = false) {
        syncCurrentAccount()
        val spec = createSpec(accountId.value, streamSort.value)
        viewModelScope.launch {
            runCatching {
                if (force) refreshCoordinator.forceRefresh(spec, reason)
                else refreshCoordinator.maybeRefresh(spec, reason)
            }
        }
    }

    private fun readCurrentUserId(): String? = applicationContext.tokenPrefs().getString(C.USER_ID, null)

    private fun readStreamSort(): String = StreamsSortDialog.defaultSort(applicationContext)

    private fun createSpec(userId: String?, sort: String): StreamFeedSpec {
        return StreamFeedSpecs.followed(
            context = applicationContext,
            userId = userId,
            sort = sort,
            localChannelFollowsRepository = localChannelFollowsRepository,
            graphQLRepository = graphQLRepository,
            helixRepository = helixRepository,
        )
    }

    companion object {
        val FollowedStreamsViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                FollowedStreamsViewModel(
                    application.applicationContext,
                    xtraModule.localChannelFollowsRepository,
                    xtraModule.graphQLRepository,
                    xtraModule.helixRepository,
                    xtraModule.streamFeedPager,
                    xtraModule.streamFeedRefreshCoordinator,
                )
            }
        }
    }
}
