package com.github.andreyasadchy.xtra.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.RecentSearchesRepository
import com.github.andreyasadchy.xtra.repository.RecommendationsRepository
import com.github.andreyasadchy.xtra.model.ui.RecentSearch
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.util.C
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SearchPagerViewModel(
    private val graphQLRepository: GraphQLRepository,
    private val recentSearchesRepository: RecentSearchesRepository,
    private val recommendationsRepository: RecommendationsRepository,
) : ViewModel() {

    val userResult = MutableStateFlow<Pair<String?, String?>?>(null)
    val cachedSuggestions = MutableStateFlow<List<Stream>>(emptyList())
    val recentSearches = combine(
        recentSearchesRepository.getAll(RecentSearch.TYPE_STREAM),
        recentSearchesRepository.getAll(RecentSearch.TYPE_CHANNEL),
        recentSearchesRepository.getAll(RecentSearch.TYPE_GAME),
        recentSearchesRepository.getAll(RecentSearch.TYPE_VIDEO),
    ) { streams, channels, games, videos ->
        (streams + channels + games + videos)
            .sortedByDescending(RecentSearch::lastSearched)
            .take(8)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())
    private var isLoading = false

    fun refreshCachedSuggestions() {
        viewModelScope.launch {
            cachedSuggestions.value = runCatching {
                recommendationsRepository.peekCachedRecommendations(limit = 8)
            }.getOrDefault(emptyList())
        }
    }

    fun deleteRecentSearch(item: RecentSearch) {
        viewModelScope.launch { recentSearchesRepository.delete(item) }
    }

    fun loadUserResult(checkedId: Int, result: String, networkLibrary: String?, gqlHeaders: Map<String, String>) {
        if (userResult.value == null && !isLoading) {
            isLoading = true
            viewModelScope.launch {
                try {
                    userResult.value = if (checkedId == 0) {
                        val response = graphQLRepository.loadQueryUserResultID(networkLibrary, gqlHeaders, result)
                        response.data!!.userResultByID?.let {
                            when {
                                it.onUser != null -> Pair(null, null)
                                it.onUserDoesNotExist != null -> Pair(it.__typename, it.onUserDoesNotExist.reason)
                                it.onUserError != null -> Pair(it.__typename, null)
                                else -> null
                            }
                        }
                    } else {
                        val response = graphQLRepository.loadQueryUserResultLogin(networkLibrary, gqlHeaders, result)
                        response.data!!.userResultByLogin?.let {
                            when {
                                it.onUser != null -> Pair(null, null)
                                it.onUserDoesNotExist != null -> Pair(it.__typename, it.onUserDoesNotExist.reason)
                                it.onUserError != null -> Pair(it.__typename, null)
                                else -> null
                            }
                        }
                    }
                } catch (e: Exception) {

                } finally {
                    isLoading = false
                }
            }
        }
    }

    companion object {
        val SearchPagerViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                SearchPagerViewModel(
                    xtraModule.graphQLRepository,
                    xtraModule.recentSearchesRepository,
                    xtraModule.recommendationsRepository,
                )
            }
        }
    }
}
