package com.github.andreyasadchy.xtra.ui.games

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.gamefeed.GameFeedPager
import com.github.andreyasadchy.xtra.repository.gamefeed.GameFeedRefreshCoordinator
import com.github.andreyasadchy.xtra.repository.gamefeed.GameFeedKey
import com.github.andreyasadchy.xtra.repository.gamefeed.GameFeedSpec
import com.github.andreyasadchy.xtra.repository.gamefeed.GameFeedSpecs
import com.github.andreyasadchy.xtra.repository.streamfeed.RefreshReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

class GamesViewModel(
    private val applicationContext: Context,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val gameFeedPager: GameFeedPager,
    val refreshCoordinator: GameFeedRefreshCoordinator,
) : ViewModel() {

    val filter = MutableStateFlow<Filter?>(null)
    val filtersText = MutableStateFlow<CharSequence?>(null)
    private var visibleFeedKey: GameFeedKey? = null

    val tags: Array<Tag>
        get() = filter.value?.tags ?: emptyArray()

    @OptIn(ExperimentalCoroutinesApi::class)
    val flow = filter.flatMapLatest { selectedFilter ->
        gameFeedPager.flow(
            spec = createSpec(selectedFilter),
            config = PagingConfig(pageSize = 30, prefetchDistance = 10, initialLoadSize = 30),
        )
    }.cachedIn(viewModelScope)

    fun currentFeedSpec(): GameFeedSpec? = filter.value?.let(::createSpec)

    fun setVisibleFeed() {
        val spec = currentFeedSpec()
        if (visibleFeedKey != spec?.key) {
            visibleFeedKey?.let(refreshCoordinator::clearVisibleFeed)
            visibleFeedKey = spec?.key
        }
        spec?.let(refreshCoordinator::setVisibleFeed)
    }

    fun clearVisibleFeed() {
        visibleFeedKey?.let(refreshCoordinator::clearVisibleFeed)
        visibleFeedKey = null
    }

    fun refreshCurrent(reason: RefreshReason, force: Boolean = false) {
        currentFeedSpec()?.let { spec ->
            viewModelScope.launch {
                runCatching {
                    if (force) refreshCoordinator.forceRefresh(spec, reason)
                    else refreshCoordinator.maybeRefresh(spec, reason)
                }
            }
        }
    }

    fun setFilter(tags: Array<Tag>?) {
        val wasVisible = visibleFeedKey != null
        if (wasVisible) clearVisibleFeed()
        filter.value = Filter(tags)
        if (wasVisible) setVisibleFeed()
    }

    private fun createSpec(filter: Filter?): GameFeedSpec {
        val tagIds = filter?.tags?.mapNotNull { it.id }
        return GameFeedSpecs.top(
            context = applicationContext,
            graphQLRepository = graphQLRepository,
            helixRepository = helixRepository,
            tags = tagIds,
        )
    }

    class Filter(
        val tags: Array<Tag>?,
    )

    companion object {
        val GamesViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                GamesViewModel(
                    application.applicationContext,
                    xtraModule.graphQLRepository,
                    xtraModule.helixRepository,
                    xtraModule.gameFeedPager,
                    xtraModule.gameFeedRefreshCoordinator,
                )
            }
        }
    }
}
