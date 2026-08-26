package com.github.andreyasadchy.xtra.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxError
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxException
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchNotification
import com.github.andreyasadchy.xtra.repository.TwitchNotificationsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NotificationsUiState(
    val items: List<TwitchNotification> = emptyList(),
    val initialLoading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingNextPage: Boolean = false,
    val canLoadMore: Boolean = false,
    val markingAllAsSeen: Boolean = false,
    val error: TwitchInboxError? = null,
)

class TwitchNotificationsViewModel(private val repository: TwitchNotificationsRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState: StateFlow<NotificationsUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var nextCursor: String? = null

    init { loadInitial() }

    fun loadInitial() {
        if (loadJob?.isActive == true || _uiState.value.markingAllAsSeen) return
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(initialLoading = true, error = null)
            runCatching { repository.getNotifications() }.onSuccess { page ->
                nextCursor = page.nextCursor
                _uiState.value = NotificationsUiState(page.notifications, canLoadMore = page.hasNextPage)
                runCatching { repository.markNotificationsViewed() }
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(initialLoading = false, error = error.toInboxError())
            }
        }
    }

    fun refresh() {
        if (loadJob?.isActive == true || _uiState.value.markingAllAsSeen) return
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(refreshing = true, error = null)
            runCatching { repository.getNotifications() }.onSuccess { page ->
                nextCursor = page.nextCursor
                _uiState.value = NotificationsUiState(page.notifications, canLoadMore = page.hasNextPage)
                runCatching { repository.markNotificationsViewed() }
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(refreshing = false, error = error.toInboxError())
            }
            _uiState.value = _uiState.value.copy(refreshing = false, initialLoading = false)
        }
    }

    fun loadMore() {
        if (loadJob?.isActive == true || _uiState.value.markingAllAsSeen || !_uiState.value.canLoadMore || nextCursor.isNullOrBlank()) return
        val requestedCursor = nextCursor ?: return
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loadingNextPage = true)
            runCatching { repository.getNotifications(requestedCursor) }.onSuccess { page ->
                if (page.nextCursor == requestedCursor) {
                    nextCursor = null
                    _uiState.value = _uiState.value.copy(canLoadMore = false)
                    return@onSuccess
                }
                nextCursor = page.nextCursor
                val merged = (_uiState.value.items + page.notifications).distinctBy { it.id }
                _uiState.value = _uiState.value.copy(items = merged, canLoadMore = page.hasNextPage, error = null)
            }.onFailure { error -> _uiState.value = _uiState.value.copy(error = error.toInboxError()) }
            _uiState.value = _uiState.value.copy(loadingNextPage = false)
        }
    }

    fun markRead(item: TwitchNotification, onSuccess: () -> Unit = {}) {
        if (!item.isUnread) return
        _uiState.value = _uiState.value.copy(items = _uiState.value.items.map { if (it.id == item.id) it.copy(isUnread = false) else it })
        viewModelScope.launch {
            runCatching { repository.markNotificationsRead(listOf(item.id)) }
                .onSuccess { onSuccess() }
        }
    }

    fun markAllAsSeen(onSuccess: () -> Unit = {}) {
        val previous = _uiState.value
        if (previous.markingAllAsSeen || previous.initialLoading || previous.refreshing || previous.loadingNextPage || previous.items.isEmpty()) return
        val previousUnreadById = previous.items.associate { it.id to it.isUnread }
        _uiState.value = previous.copy(
            items = previous.items.map { it.copy(isUnread = false) },
            markingAllAsSeen = true,
            error = null,
        )
        viewModelScope.launch {
            runCatching { repository.markAllNotificationsRead() }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        items = _uiState.value.items.map { it.copy(isUnread = false) },
                        markingAllAsSeen = false,
                    )
                    onSuccess()
                }
                .onFailure { error ->
                    val current = _uiState.value
                    _uiState.value = current.copy(
                        items = current.items.map { item ->
                            previousUnreadById[item.id]?.let { isUnread -> item.copy(isUnread = isUnread) } ?: item
                        },
                        markingAllAsSeen = false,
                        error = error.toInboxError(),
                    )
                }
        }
    }

    fun dismiss(item: TwitchNotification) {
        val previous = _uiState.value.items
        _uiState.value = _uiState.value.copy(items = previous.filterNot { it.id == item.id })
        viewModelScope.launch {
            runCatching { repository.dismissNotification(item.id) }.onFailure { error ->
                _uiState.value = _uiState.value.copy(items = previous, error = error.toInboxError())
            }
        }
    }

    companion object {
        fun factory(repository: TwitchNotificationsRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TwitchNotificationsViewModel(repository) as T
        }
    }
}

private fun Throwable.toInboxError(): TwitchInboxError = (this as? TwitchInboxException)?.error ?: TwitchInboxError.Network
