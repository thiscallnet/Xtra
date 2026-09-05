package com.github.andreyasadchy.xtra.ui.following.streams

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.LocalChannelFollowsRepository
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedCache
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class FollowedStreamsViewModel(
    applicationContext: Context,
    private val localChannelFollowsRepository: LocalChannelFollowsRepository,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val streamFeedCache: StreamFeedCache,
    private val streamFeedPager: StreamFeedPager,
    val refreshCoordinator: StreamFeedRefreshCoordinator,
) : ViewModel() {

    private val applicationContext = applicationContext
    private val accountId = MutableStateFlow(readCurrentUserId())
    private val streamSort = MutableStateFlow(readStreamSort())
    private var cachedFeedSpec = createSpec(accountId.value, streamSort.value)
    private val accountPagerGeneration = FollowedAccountPagerGeneration()
    private var appendJob: Job? = null
    private val pagingConfig = if (applicationContext.prefs().getString(C.COMPACT_STREAMS, "disabled") != "disabled") {
        PagingConfig(pageSize = 30, prefetchDistance = 10, initialLoadSize = 30)
    } else {
        PagingConfig(pageSize = 30, prefetchDistance = 3, initialLoadSize = 30)
    }

    val sort: String
        get() = streamSort.value

    fun setSort(sort: String) {
        if (streamSort.value == sort) return
        streamSort.value = sort
        cachedFeedSpec = createSpec(accountId.value, sort)
    }

    val flow = combine(accountId, streamSort) { userId, sort -> userId to sort }
        .flatMapLatest { (userId, sort) ->
            flow {
                // An account partition change is the one legitimate case
                // where the old rows must be removed before the new cache
                // generation is submitted. The initial generation remains
                // cache-first and does not emit this empty snapshot.
                if (accountPagerGeneration.switchTo(userId)) {
                    emit(emptyList())
                }
                emitAll(
                    streamFeedCache.activeItemsFlow(
                        feedKey = createSpec(userId, sort).key,
                        limit = MAX_VISIBLE_STREAMS,
                    ),
                )
            }
        }

    /** Used only by the multiview picker, not by the Following Live list. */
    val pagingFlow = combine(accountId, streamSort) { userId, sort -> userId to sort }
        .flatMapLatest { (userId, sort) ->
            streamFeedPager.flow(
                spec = createSpec(userId, sort),
                config = pagingConfig,
            )
        }
        .cachedIn(viewModelScope)

    fun syncCurrentAccount() {
        val nextAccountId = readCurrentUserId()
        val nextSort = readStreamSort()
        accountId.value = nextAccountId
        streamSort.value = nextSort
        cachedFeedSpec = createSpec(nextAccountId, nextSort)
    }

    /** Cached because RecyclerView may call this from its end-of-scroll callback. */
    fun currentFeedSpec(): StreamFeedSpec = cachedFeedSpec

    fun refreshCurrent(reason: RefreshReason, force: Boolean = false): Job {
        syncCurrentAccount()
        val spec = cachedFeedSpec
        return viewModelScope.launch {
            runCatching {
                if (force) refreshCoordinator.forceRefresh(spec, reason)
                else refreshCoordinator.maybeRefresh(spec, reason)
            }
        }
    }

    fun appendNextPage() {
        if (appendJob?.isActive == true) return
        val spec = currentFeedSpec()
        appendJob = viewModelScope.launch {
            try {
                refreshCoordinator.append(spec)
            } catch (_: Exception) {
                // Keep the current snapshot visible; reaching the end again
                // can retry a transient pagination failure.
            } finally {
                appendJob = null
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
        private const val MAX_VISIBLE_STREAMS = 600

        val FollowedStreamsViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                FollowedStreamsViewModel(
                    application.applicationContext,
                    xtraModule.localChannelFollowsRepository,
                    xtraModule.graphQLRepository,
                    xtraModule.helixRepository,
                    xtraModule.streamFeedCache,
                    xtraModule.streamFeedPager,
                    xtraModule.streamFeedRefreshCoordinator,
                )
            }
        }
    }
}
