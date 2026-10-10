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
import com.github.andreyasadchy.xtra.repository.SearchHistoryRepository
import com.github.andreyasadchy.xtra.repository.BookmarksRepository
import com.github.andreyasadchy.xtra.repository.LocalChannelFollowsRepository
import com.github.andreyasadchy.xtra.repository.OfflineVideosRepository
import com.github.andreyasadchy.xtra.repository.RecommendationsRepository
import com.github.andreyasadchy.xtra.repository.datasource.FollowedChannelsDataSource
import androidx.paging.PagingSource
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.github.andreyasadchy.xtra.repository.datasource.withHelixBroadcasterTypes
import com.github.andreyasadchy.xtra.model.ui.SearchHistoryItem
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.SharingStarted
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
    private val searchHistoryRepository: SearchHistoryRepository,
    private val recommendationsRepository: RecommendationsRepository,
    private val localChannelFollowsRepository: LocalChannelFollowsRepository,
    private val offlineVideosRepository: OfflineVideosRepository,
    private val bookmarksRepository: BookmarksRepository,
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
    /** Everything the user opened from search; callers rank it with [ranked]. */
    val history = searchHistoryRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())
    private var cachedSuggestionRequest = 0L

    /** Viewer counts of channels that are live right now; last known value is kept on failures. */
    private val _live = MutableStateFlow<LiveChannels>(emptyMap())
    val live = _live.asStateFlow()
    private var liveJob: Job? = null

    /** Followed channels (account and local) as searchable entries; loaded on first use. */
    private val _followed = MutableStateFlow<List<SearchHistoryItem>>(emptyList())
    val followed = _followed.asStateFlow()
    private var followedLoaded = false

    fun refreshLive(items: List<SearchHistoryItem>) {
        val channels = items.filter { it.isChannel }
        if (channels.isEmpty()) {
            liveJob?.cancel()
            _live.value = emptyMap()
            return
        }
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            val networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
            val headers = TwitchApiHelper.getGQLHeaders(applicationContext, true)
            val ids = channels.filter { it.refId != it.slug }.map { it.refId }.distinct()
            val logins = channels.filter { it.refId == it.slug }.map { it.refId }.distinct()
            val found = mutableMapOf<String, Int>()
            try {
                suspend fun collect(ids: List<String>?, logins: List<String>?) {
                    graphQLRepository.loadQueryUsersStream(networkLibrary, headers, ids, logins)
                        .data?.users?.forEach { user ->
                            val viewers = user?.stream?.viewersCount ?: return@forEach
                            user.id?.let { found[it] = viewers }
                            user.login?.let { found[it.lowercase()] = viewers }
                        }
                }
                ids.chunked(100).forEach { collect(it, null) }
                logins.chunked(100).forEach { collect(null, it) }
                _live.value = found
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline or throttled: keep what we showed before instead of dropping every badge.
            }
        }
    }

    fun ensureFollowedLoaded() {
        if (followedLoaded) return
        followedLoaded = true
        viewModelScope.launch {
            try {
                val source = FollowedChannelsDataSource(
                    sort = "login",
                    order = "asc",
                    userId = applicationContext.tokenPrefs().getString(C.USER_ID, null),
                    localChannelFollowsRepository = localChannelFollowsRepository,
                    offlineVideosRepository = offlineVideosRepository,
                    bookmarksRepository = bookmarksRepository,
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true),
                    graphQLRepository = graphQLRepository,
                    helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext),
                    helixRepository = helixRepository,
                    networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                )
                val page = source.load(PagingSource.LoadParams.Refresh(null, FOLLOWED_LIMIT, false))
                if (page is PagingSource.LoadResult.Page) {
                    _followed.value = page.data.mapNotNull {
                        SearchHistoryItem.channel(it.id, it.login, it.name, it.profileImage)
                            ?.let { item -> SearchHistoryItem(item.kind, item.refId, item.title, item.slug, item.imageUrl, 0, 0L) }
                    }
                } else {
                    followedLoaded = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                followedLoaded = false
            }
        }
    }

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

    fun removeHistory(item: SearchHistoryItem) {
        viewModelScope.launch { searchHistoryRepository.delete(item) }
    }

    fun clearHistory() {
        viewModelScope.launch { searchHistoryRepository.deleteAll() }
    }

    fun restoreHistory(items: List<SearchHistoryItem>) {
        if (items.isNotEmpty()) viewModelScope.launch { searchHistoryRepository.restore(items) }
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
        private const val FOLLOWED_LIMIT = 100

        val SearchPagerViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                SearchPagerViewModel(
                    application.applicationContext,
                    xtraModule.graphQLRepository,
                    xtraModule.helixRepository,
                    xtraModule.searchHistoryRepository,
                    xtraModule.recommendationsRepository,
                    xtraModule.localChannelFollowsRepository,
                    xtraModule.offlineVideosRepository,
                    xtraModule.bookmarksRepository,
                )
            }
        }
    }
}
