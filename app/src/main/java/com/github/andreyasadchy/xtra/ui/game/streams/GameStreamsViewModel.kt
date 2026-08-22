package com.github.andreyasadchy.xtra.ui.game.streams

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.GameSort
import com.github.andreyasadchy.xtra.model.ui.SavedFilter
import com.github.andreyasadchy.xtra.repository.GameSortRepository
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.SavedFiltersRepository
import com.github.andreyasadchy.xtra.repository.streamfeed.RefreshReason
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedPager
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedRefreshCoordinator
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedSpec
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedSpecs
import com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentArgs
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest

class GameStreamsViewModel(
    private val applicationContext: Context,
    private val gameSortRepository: GameSortRepository,
    private val savedFiltersRepository: SavedFiltersRepository,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    savedStateHandle: SavedStateHandle,
    private val streamFeedPager: StreamFeedPager,
    val refreshCoordinator: StreamFeedRefreshCoordinator,
) : ViewModel() {

    private val args = GamePagerFragmentArgs.fromSavedStateHandle(savedStateHandle)
    val filter = MutableStateFlow<Filter?>(null)
    val sortText = MutableStateFlow<CharSequence?>(null)
    val filtersText = MutableStateFlow<CharSequence?>(null)

    val sort: String
        get() = filter.value?.sort ?: StreamsSortDialog.defaultSort(applicationContext)
    val tags: Array<String>
        get() = filter.value?.tags ?: emptyArray()
    val languages: Array<String>
        get() = filter.value?.languages ?: emptyArray()

    @OptIn(ExperimentalCoroutinesApi::class)
    val flow = filter.flatMapLatest {
        streamFeedPager.flow(
            spec = createSpec(it ?: Filter(null, null, null)),
            config = if (applicationContext.prefs().getString(C.COMPACT_STREAMS, "disabled") == "all") {
                PagingConfig(pageSize = 30, prefetchDistance = 10, initialLoadSize = 30)
            } else {
                PagingConfig(pageSize = 30, prefetchDistance = 3, initialLoadSize = 30)
            },
        )
    }.cachedIn(viewModelScope)

    fun currentFeedSpec(): StreamFeedSpec? = filter.value?.let(::createSpec)

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

    suspend fun getGameSort(id: String): GameSort? {
        return gameSortRepository.getById(id)
    }

    suspend fun saveGameSort(item: GameSort) {
        gameSortRepository.save(item)
    }

    suspend fun deleteGameSort(item: GameSort) {
        gameSortRepository.delete(item)
    }

    suspend fun saveFilters(item: SavedFilter) {
        savedFiltersRepository.save(item)
    }

    fun setFilter(sort: String?, tags: Array<String>?, languages: Array<String>?) {
        filter.value = Filter(sort, tags, languages)
    }

    private fun createSpec(filter: Filter): StreamFeedSpec {
        return StreamFeedSpecs.game(
            context = applicationContext,
            graphQLRepository = graphQLRepository,
            helixRepository = helixRepository,
            gameId = args.gameId,
            gameSlug = args.gameSlug,
            gameName = args.gameName,
            sort = filter.sort ?: StreamsSortDialog.defaultSort(applicationContext),
            tags = filter.tags?.asIterable(),
            languages = filter.languages?.asIterable(),
        )
    }

    class Filter(
        val sort: String?,
        val tags: Array<String>?,
        val languages: Array<String>?,
    )

    companion object {
        val GameStreamsViewModelFactory = viewModelFactory {
            initializer {
                val savedStateHandle = createSavedStateHandle()
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                GameStreamsViewModel(
                    application.applicationContext,
                    xtraModule.gameSortRepository,
                    xtraModule.savedFiltersRepository,
                    xtraModule.graphQLRepository,
                    xtraModule.helixRepository,
                    savedStateHandle,
                    xtraModule.streamFeedPager,
                    xtraModule.streamFeedRefreshCoordinator,
                )
            }
        }
    }
}
