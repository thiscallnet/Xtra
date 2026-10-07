package com.github.andreyasadchy.xtra.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.ui.inbox.runCatchingInboxRequest
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxError
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxException
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchNotification
import com.github.andreyasadchy.xtra.repository.TwitchNotificationsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
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
    val markingAllAsRead: Boolean = false,
    val error: TwitchInboxError? = null,
)

class TwitchNotificationsViewModel(private val repository: TwitchNotificationsRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState: StateFlow<NotificationsUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var nextCursor: String? = null
    private var accountId = repository.currentUserId()
    private val locallyDismissedIds = mutableSetOf<String>()

    init { loadInitial() }

    fun loadInitial() {
        ensureAccountCurrent()
        if (loadJob?.isActive == true || _uiState.value.markingAllAsRead) return
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(initialLoading = true, error = null)
            runCatchingInboxRequest { repository.getCachedNotifications() }.getOrNull()?.let { page ->
                if (!ensureAccountCurrent()) return@let
                nextCursor = page.nextCursor
                _uiState.value = _uiState.value.copy(items = applyLocalChanges(page.notifications), canLoadMore = page.hasNextPage)
            }
            runCatchingInboxRequest { repository.getNotifications() }.onSuccess { page ->
                if (!ensureAccountCurrent()) return@onSuccess
                nextCursor = page.nextCursor
                _uiState.value = NotificationsUiState(applyLocalChanges(page.notifications), canLoadMore = page.hasNextPage)
                runCatchingInboxRequest { repository.markNotificationsViewed() }
            }.onFailure { error ->
                if (!ensureAccountCurrent()) return@onFailure
                _uiState.value = _uiState.value.copy(initialLoading = false, error = error.toInboxError())
            }
        }
    }

    fun refresh() {
        ensureAccountCurrent()
        if (loadJob?.isActive == true || _uiState.value.markingAllAsRead) return
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(refreshing = true, error = null)
            runCatchingInboxRequest { repository.getNotifications() }.onSuccess { page ->
                if (!ensureAccountCurrent()) return@onSuccess
                nextCursor = page.nextCursor
                _uiState.value = NotificationsUiState(applyLocalChanges(page.notifications), canLoadMore = page.hasNextPage)
                runCatchingInboxRequest { repository.markNotificationsViewed() }
            }.onFailure { error ->
                if (!ensureAccountCurrent()) return@onFailure
                _uiState.value = _uiState.value.copy(refreshing = false, error = error.toInboxError())
            }
            _uiState.value = _uiState.value.copy(refreshing = false, initialLoading = false)
        }
    }

    fun loadMore() {
        if (!ensureAccountCurrent()) return
        if (loadJob?.isActive == true || _uiState.value.markingAllAsRead || !_uiState.value.canLoadMore || nextCursor.isNullOrBlank()) return
        val requestedCursor = nextCursor ?: return
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loadingNextPage = true)
            runCatchingInboxRequest { repository.getNotifications(requestedCursor) }.onSuccess { page ->
                if (!ensureAccountCurrent()) return@onSuccess
                val advanced = page.nextCursor != requestedCursor
                nextCursor = page.nextCursor.takeIf { advanced }
                val merged = applyLocalChanges((_uiState.value.items + page.notifications).distinctBy { it.id })
                _uiState.value = _uiState.value.copy(items = merged, canLoadMore = advanced && page.hasNextPage && nextCursor != null, error = null)
            }.onFailure { error -> if (ensureAccountCurrent()) _uiState.value = _uiState.value.copy(error = error.toInboxError()) }
            _uiState.value = _uiState.value.copy(loadingNextPage = false)
        }
    }

    fun markRead(item: TwitchNotification, onSuccess: () -> Unit = {}) {
        if (!ensureAccountCurrent()) return
        if (!item.isUnread) return
        viewModelScope.launch {
            runCatchingInboxRequest { repository.markNotificationsRead(listOf(item.id)) }
                .onSuccess {
                    if (!ensureAccountCurrent()) return@onSuccess
                    _uiState.value = _uiState.value.copy(
                        items = applyLocalChanges(_uiState.value.items).map { current ->
                            if (current.id == item.id) current.copy(isUnread = false) else current
                        },
                    )
                    onSuccess()
                }
                .onFailure { error ->
                    if (!ensureAccountCurrent()) return@onFailure
                    _uiState.value = _uiState.value.copy(error = error.toInboxError())
                }
        }
    }

    fun markAllAsRead(onSuccess: () -> Unit = {}) {
        if (!ensureAccountCurrent()) return
        val previous = _uiState.value
        if (previous.markingAllAsRead || previous.initialLoading || previous.refreshing || previous.loadingNextPage || previous.items.isEmpty()) return
        _uiState.value = previous.copy(
            markingAllAsRead = true,
            error = null,
        )
        viewModelScope.launch {
            runCatchingInboxRequest { repository.markAllNotificationsRead() }
                .onSuccess { readIds ->
                    if (!ensureAccountCurrent()) return@onSuccess
                    _uiState.value = _uiState.value.copy(
                        items = applyLocalChanges(_uiState.value.items).map { item ->
                            if (item.id in readIds) item.copy(isUnread = false) else item
                        },
                        markingAllAsRead = false,
                    )
                    onSuccess()
                }
                .onFailure { error ->
                    if (!ensureAccountCurrent()) return@onFailure
                    _uiState.value = _uiState.value.copy(markingAllAsRead = false, error = error.toInboxError())
                }
        }
    }

    fun dismiss(item: TwitchNotification) {
        if (!ensureAccountCurrent()) return
        val previous = _uiState.value.items
        locallyDismissedIds.add(item.id)
        _uiState.value = _uiState.value.copy(items = applyLocalChanges(previous))
        viewModelScope.launch {
            runCatchingInboxRequest { repository.dismissNotification(item.id) }
                .onSuccess {
                    if (!ensureAccountCurrent()) return@onSuccess
                    _uiState.value = _uiState.value.copy(items = applyLocalChanges(_uiState.value.items))
                }
                .onFailure { error ->
                    if (!ensureAccountCurrent()) return@onFailure
                    locallyDismissedIds.remove(item.id)
                    val current = _uiState.value.items
                    _uiState.value = _uiState.value.copy(
                        items = restoreDismissedNotification(current, previous, item),
                        error = error.toInboxError(),
                    )
                }
        }
    }

    private fun ensureAccountCurrent(): Boolean {
        val currentAccountId = repository.currentUserId()
        if (currentAccountId == accountId) return true
        viewModelScope.coroutineContext.cancelChildren()
        accountId = currentAccountId
        nextCursor = null
        locallyDismissedIds.clear()
        _uiState.value = NotificationsUiState(error = TwitchInboxError.SignedOut)
        return false
    }

    private fun applyLocalChanges(items: List<TwitchNotification>): List<TwitchNotification> =
        applyLocalNotificationChanges(items, locallyDismissedIds)

    companion object {
        fun factory(repository: TwitchNotificationsRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TwitchNotificationsViewModel(repository) as T
        }
    }
}

private fun Throwable.toInboxError(): TwitchInboxError = (this as? TwitchInboxException)?.error ?: TwitchInboxError.Network

internal fun applyLocalNotificationChanges(
    items: List<TwitchNotification>,
    locallyDismissedIds: Set<String>,
): List<TwitchNotification> = items.asSequence()
    .filterNot { it.id in locallyDismissedIds }
    .toList()

internal fun restoreDismissedNotification(
    current: List<TwitchNotification>,
    previous: List<TwitchNotification>,
    item: TwitchNotification,
): List<TwitchNotification> {
    if (current.any { it.id == item.id }) return current
    val previousIndex = previous.indexOfFirst { it.id == item.id }
    if (previousIndex < 0) return current + item
    val currentIndices = current.mapIndexed { index, notification -> notification.id to index }.toMap()
    // Keep concurrent refreshes and other dismissals; anchor the restored row to a surviving neighbor.
    val nextIndex = previous.asSequence().drop(previousIndex + 1)
        .firstNotNullOfOrNull { currentIndices[it.id] }
    val precedingIndex = previous.asSequence().take(previousIndex).toList().asReversed()
        .firstNotNullOfOrNull { currentIndices[it.id] }
    val insertionIndex = nextIndex ?: precedingIndex?.plus(1) ?: previousIndex.coerceAtMost(current.size)
    return current.toMutableList().apply { add(insertionIndex, item) }
}
