package com.github.andreyasadchy.xtra.ui.search.games

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.datasource.SearchGamesDataSource
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

class GameSearchViewModel(
    applicationContext: Context,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    @OptIn(ExperimentalCoroutinesApi::class)
    val flow = _query.flatMapLatest { query ->
        Pager(
            PagingConfig(pageSize = 30, prefetchDistance = 10, initialLoadSize = 30)
        ) {
            SearchGamesDataSource(
                query = query,
                helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext),
                helixRepository = helixRepository,
                gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true),
                graphQLRepository = graphQLRepository,
                networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            )
        }.flow
    }.cachedIn(viewModelScope)

    fun setQuery(newQuery: String): Boolean {
        val query = newQuery.trim()
        if (_query.value == query) return false
        _query.value = query
        return true
    }


    companion object {
        val GameSearchViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                GameSearchViewModel(application.applicationContext, xtraModule.graphQLRepository, xtraModule.helixRepository)
            }
        }
    }
}
