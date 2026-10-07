package com.github.andreyasadchy.xtra.ui.whispers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.ui.inbox.runCatchingInboxRequest
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxError
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxException
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchUserSummary
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperThread
import com.github.andreyasadchy.xtra.repository.WhispersRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
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
    private var refreshAfterLoadJob: Job? = null
    private var refreshQueued = false
    private var searchJob: Job? = null
    private var nextCursor: String? = null
    private var accountId = repository.currentUserId()

    init { loadInitial() }

    fun loadInitial() {
        ensureAccountCurrent()
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            runCatchingInboxRequest { repository.getCachedThreads() }.getOrNull()?.let { page ->
                if (!ensureAccountCurrent()) return@let
                nextCursor = page.nextCursor
                updateConversations(page.threads, page.hasNextPage)
            }
            runCatchingInboxRequest { repository.getThreads() }.onSuccess { page ->
                if (!ensureAccountCurrent()) return@onSuccess
                nextCursor = page.nextCursor
                updateConversations(page.threads, page.hasNextPage)
            }.onFailure { error -> if (ensureAccountCurrent()) _uiState.value = _uiState.value.copy(error = error.toInboxError()) }
            _uiState.value = _uiState.value.copy(loading = false)
        }
    }

    fun refresh() {
        ensureAccountCurrent()
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(refreshing = true, error = null)
            runCatchingInboxRequest { repository.getCachedThreads() }.getOrNull()?.let { page ->
                if (!ensureAccountCurrent()) return@let
                nextCursor = page.nextCursor
                updateConversations(page.threads, page.hasNextPage)
            }
            runCatchingInboxRequest { repository.getThreads() }.onSuccess { page ->
                if (!ensureAccountCurrent()) return@onSuccess
                nextCursor = page.nextCursor
                updateConversations(page.threads, page.hasNextPage)
            }.onFailure { error -> if (ensureAccountCurrent()) _uiState.value = _uiState.value.copy(error = error.toInboxError()) }
            _uiState.value = _uiState.value.copy(refreshing = false)
        }
    }

    fun refreshWhenIdle() {
        if (loadJob?.isActive != true) {
            refresh()
            return
        }
        refreshQueued = true
        if (refreshAfterLoadJob?.isActive == true) return
        refreshAfterLoadJob = viewModelScope.launch {
            while (true) {
                val activeLoad = loadJob?.takeIf { it.isActive } ?: break
                activeLoad.join()
            }
            refreshAfterLoadJob = null
            if (refreshQueued) {
                refreshQueued = false
                refresh()
            }
        }
    }

    fun markThreadRead(threadId: String) {
        val current = _uiState.value
        val updated = current.conversations.map { thread ->
            if (thread.id == threadId) thread.copy(unreadCount = 0, isUnread = false) else thread
        }
        if (updated == current.conversations) return
        _uiState.value = current.copy(
            conversations = updated,
            filteredConversations = filter(updated, current.searchQuery),
        )
    }

    fun loadMore() {
        if (!ensureAccountCurrent()) return
        if (loadJob?.isActive == true || !_uiState.value.canLoadMore || nextCursor.isNullOrBlank()) return
        val requestedCursor = nextCursor ?: return
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loadingMore = true)
            runCatchingInboxRequest { repository.getThreads(requestedCursor) }.onSuccess { page ->
                if (!ensureAccountCurrent()) return@onSuccess
                val advanced = page.nextCursor != requestedCursor
                nextCursor = page.nextCursor.takeIf { advanced }
                updateConversations((_uiState.value.conversations + page.threads).distinctBy { it.id }, advanced && page.hasNextPage && nextCursor != null)
            }.onFailure { error -> if (ensureAccountCurrent()) _uiState.value = _uiState.value.copy(error = error.toInboxError()) }
            _uiState.value = _uiState.value.copy(loadingMore = false)
        }
    }

    fun setSearchQuery(value: String) {
        ensureAccountCurrent()
        val query = value.trimStart()
        val local = filter(_uiState.value.conversations, query)
        _uiState.value = _uiState.value.copy(searchQuery = query, filteredConversations = local, searchResults = emptyList(), searching = query.isNotBlank())
        searchJob?.cancel()
        if (query.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(300)
            try {
                val results = repository.searchUsers(query)
                if (ensureAccountCurrent() && _uiState.value.searchQuery == query) {
                    _uiState.value = _uiState.value.copy(searchResults = results.filterNot { user -> _uiState.value.conversations.any { it.peer.id == user.id } }, searching = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (ensureAccountCurrent() && _uiState.value.searchQuery == query) {
                    _uiState.value = _uiState.value.copy(searching = false, error = error.toInboxError())
                }
            }
        }
    }

    private fun ensureAccountCurrent(): Boolean {
        val currentAccountId = repository.currentUserId()
        if (currentAccountId == accountId) return true
        viewModelScope.coroutineContext.cancelChildren()
        accountId = currentAccountId
        nextCursor = null
        refreshQueued = false
        _uiState.value = WhispersUiState(error = TwitchInboxError.SignedOut)
        return false
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
