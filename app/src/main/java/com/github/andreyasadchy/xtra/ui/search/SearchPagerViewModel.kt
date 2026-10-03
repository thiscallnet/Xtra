package com.github.andreyasadchy.xtra.ui.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.RecentSearchesRepository
import com.github.andreyasadchy.xtra.repository.RecommendationsRepository
import com.github.andreyasadchy.xtra.repository.datasource.withHelixBroadcasterTypes
import com.github.andreyasadchy.xtra.model.ui.RecentSearch
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SearchPagerViewModel(
    private val applicationContext: Context,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val recentSearchesRepository: RecentSearchesRepository,
    private val recommendationsRepository: RecommendationsRepository,
) : ViewModel() {

    data class UserLookupRequest(val byId: Boolean, val input: String)

    sealed interface UserLookupState {
        data object Idle : UserLookupState
        data class Loading(val request: UserLookupRequest) : UserLookupState
        data class Success(val request: UserLookupRequest, val type: String?, val reason: String?) : UserLookupState
        data class Failed(val request: UserLookupRequest) : UserLookupState
    }

    private val _userLookup = MutableStateFlow<UserLookupState>(UserLookupState.Idle)
    val userLookup = _userLookup.asStateFlow()
    private var userLookupJob: Job? = null
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
    private var cachedSuggestionRequest = 0L

    fun refreshCachedSuggestions() {
        val request = ++cachedSuggestionRequest
        viewModelScope.launch {
            val suggestions = runCatching {
                recommendationsRepository.peekCachedRecommendations(limit = 8)
            }.getOrDefault(emptyList())
            if (request != cachedSuggestionRequest) return@launch
            // Draw cached suggestions immediately; Helix is optional decoration.
            cachedSuggestions.value = suggestions
            if (suggestions.isEmpty()) return@launch

            val enriched = suggestions.withHelixBroadcasterTypes(
                networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                headers = TwitchApiHelper.getHelixHeaders(applicationContext),
                helixRepository = helixRepository,
            )
            if (request == cachedSuggestionRequest) cachedSuggestions.value = enriched
        }
    }

    fun deleteRecentSearch(item: RecentSearch) {
        viewModelScope.launch { recentSearchesRepository.delete(item) }
    }

    fun loadUserResult(request: UserLookupRequest, networkLibrary: String?, gqlHeaders: Map<String, String>) {
        userLookupJob?.cancel()
        _userLookup.value = UserLookupState.Loading(request)
        userLookupJob = viewModelScope.launch {
            try {
                val result = if (request.byId) {
                    val data = graphQLRepository.loadQueryUserResultID(networkLibrary, gqlHeaders, request.input)
                        .data?.userResultByID ?: error("Missing user lookup result")
                    when {
                        data.onUser != null -> UserLookupState.Success(request, null, null)
                        data.onUserDoesNotExist != null -> UserLookupState.Success(request, data.__typename, data.onUserDoesNotExist.reason)
                        data.onUserError != null -> UserLookupState.Success(request, data.__typename, null)
                        else -> UserLookupState.Failed(request)
                    }
                } else {
                    val data = graphQLRepository.loadQueryUserResultLogin(networkLibrary, gqlHeaders, request.input)
                        .data?.userResultByLogin ?: error("Missing user lookup result")
                    when {
                        data.onUser != null -> UserLookupState.Success(request, null, null)
                        data.onUserDoesNotExist != null -> UserLookupState.Success(request, data.__typename, data.onUserDoesNotExist.reason)
                        data.onUserError != null -> UserLookupState.Success(request, data.__typename, null)
                        else -> UserLookupState.Failed(request)
                    }
                }
                // A cancelled/replaced request must never publish over a newer one.
                currentCoroutineContext().ensureActive()
                _userLookup.value = result
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                _userLookup.value = UserLookupState.Failed(request)
            }
        }
    }

    fun clearUserLookup() {
        userLookupJob?.cancel()
        userLookupJob = null
        _userLookup.value = UserLookupState.Idle
    }

    companion object {
        val SearchPagerViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                SearchPagerViewModel(
                    application.applicationContext,
                    xtraModule.graphQLRepository,
                    xtraModule.helixRepository,
                    xtraModule.recentSearchesRepository,
                    xtraModule.recommendationsRepository,
                )
            }
        }
    }
}
