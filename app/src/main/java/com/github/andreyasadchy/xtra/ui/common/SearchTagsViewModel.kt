package com.github.andreyasadchy.xtra.ui.common

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.datasource.TagsDataSource
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn

class SearchTagsViewModel(
    applicationContext: Context,
    private val graphQLRepository: GraphQLRepository,
) : ViewModel() {

    data class SearchPage(val query: String, val data: PagingData<Tag>)

    var getGameTags = false
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    @OptIn(ExperimentalCoroutinesApi::class)
    val flow = _query.flatMapLatest { query ->
        // Keep the query with its cached generation, including across dialog recreation.
        // Switching queries also cancels that generation's cache.
        channelFlow {
            Pager(
                PagingConfig(pageSize = 30, prefetchDistance = 10, initialLoadSize = 30)
            ) {
                TagsDataSource(
                    getGameTags = getGameTags,
                    query = query,
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext),
                    graphQLRepository = graphQLRepository,
                    networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                )
            }.flow.cachedIn(this).collect { send(SearchPage(query, it)) }
        }
    }.shareIn(viewModelScope, SharingStarted.Lazily, replay = 1)

    fun setQuery(newQuery: String) {
        if (_query.value != newQuery) {
            _query.value = newQuery
        }
    }

    companion object {
        val SearchTagsViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                SearchTagsViewModel(application.applicationContext, xtraModule.graphQLRepository)
            }
        }
    }
}
