package com.github.andreyasadchy.xtra.ui.whispers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxError
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxException
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchUserSummary
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperThread
import com.github.andreyasadchy.xtra.repository.WhispersRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WhispersUiState(
    val conversations: List<WhisperThread> = emptyList(),
    val filteredConversations: List<WhisperThread> = emptyList(),
    val searchResults: List<TwitchUserSummary> = emptyList(),
    val searchQuery: String = "",
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val searching: Boolean = false,
    val canLoadMore: Boolean = false,
    val error: TwitchInboxError? = null,
)

class WhispersViewModel(private val repository: WhispersRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(WhispersUiState())
    val uiState: StateFlow<WhispersUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var nextCursor: String? = null

    init { loadInitial() }

    fun loadInitial() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            runCatching { repository.getCachedThreads() }.getOrNull()?.let { page ->
                nextCursor = page.nextCursor
                updateConversations(page.threads, page.hasNextPage)
            }
            runCatching { repository.getThreads() }.onSuccess { page ->
                nextCursor = page.nextCursor
                updateConversations(page.threads, page.hasNextPage)
            }.onFailure { error -> _uiState.value = _uiState.value.copy(error = error.toInboxError()) }
            _uiState.value = _uiState.value.copy(loading = false)
        }
    }

    fun refresh() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(refreshing = true, error = null)
            runCatching { repository.getThreads() }.onSuccess { page ->
                nextCursor = page.nextCursor
                updateConversations(page.threads, page.hasNextPage)
            }.onFailure { error -> _uiState.value = _uiState.value.copy(error = error.toInboxError()) }
            _uiState.value = _uiState.value.copy(refreshing = false)
        }
    }

    fun loadMore() {
        if (loadJob?.isActive == true || !_uiState.value.canLoadMore || nextCursor.isNullOrBlank()) return
        val requestedCursor = nextCursor ?: return
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loadingMore = true)
            runCatching { repository.getThreads(requestedCursor) }.onSuccess { page ->
                if (page.nextCursor == requestedCursor) {
                    nextCursor = null
                    _uiState.value = _uiState.value.copy(canLoadMore = false)
                    return@onSuccess
                }
                nextCursor = page.nextCursor
                updateConversations((_uiState.value.conversations + page.threads).distinctBy { it.id }, page.hasNextPage)
            }.onFailure { error -> _uiState.value = _uiState.value.copy(error = error.toInboxError()) }
            _uiState.value = _uiState.value.copy(loadingMore = false)
        }
    }

    fun setSearchQuery(value: String) {
        val query = value.trimStart()
        val local = filter(_uiState.value.conversations, query)
        _uiState.value = _uiState.value.copy(searchQuery = query, filteredConversations = local, searchResults = emptyList(), searching = query.isNotBlank())
        searchJob?.cancel()
        if (query.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(300)
            try {
                val results = repository.searchUsers(query)
                if (_uiState.value.searchQuery == query) {
                    _uiState.value = _uiState.value.copy(searchResults = results.filterNot { user -> _uiState.value.conversations.any { it.peer.id == user.id } }, searching = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (_uiState.value.searchQuery == query) {
                    _uiState.value = _uiState.value.copy(searching = false, error = error.toInboxError())
                }
            }
        }
    }

    private fun updateConversations(items: List<WhisperThread>, canLoadMore: Boolean) {
        val query = _uiState.value.searchQuery
        _uiState.value = _uiState.value.copy(conversations = items, filteredConversations = filter(items, query), canLoadMore = canLoadMore, error = null)
    }

    private fun filter(items: List<WhisperThread>, query: String): List<WhisperThread> = filterWhisperThreads(items, query)

    companion object {
        fun factory(repository: WhispersRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WhispersViewModel(repository) as T
        }
    }
}

internal fun filterWhisperThreads(items: List<WhisperThread>, query: String): List<WhisperThread> = if (query.isBlank()) items else items.filter {
    it.peer.displayName.contains(query, true) || it.peer.login.contains(query, true) || it.lastMessage?.text.orEmpty().contains(query, true)
}

private fun Throwable.toInboxError(): TwitchInboxError = (this as? TwitchInboxException)?.error ?: TwitchInboxError.Network
