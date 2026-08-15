package com.github.andreyasadchy.xtra.ui.common

import androidx.paging.LoadState

internal enum class PagedListContentState {
    Loading,
    Content,
    Empty,
    Error,
}

internal enum class PagedListErrorState {
    Refresh,
    Page,
}

internal fun pagedListContentState(refresh: LoadState, itemCount: Int): PagedListContentState {
    return when {
        itemCount > 0 -> PagedListContentState.Content
        refresh is LoadState.Loading -> PagedListContentState.Loading
        refresh is LoadState.Error -> PagedListContentState.Error
        else -> PagedListContentState.Empty
    }
}

/**
 * RemoteMediator refreshes synchronize the cache; they must not hide rows
 * already supplied by the Room PagingSource.
 */
internal fun cacheAwarePagedListContentState(
    sourceRefresh: LoadState,
    mediatorRefresh: LoadState?,
    itemCount: Int,
): PagedListContentState {
    return when {
        itemCount > 0 -> PagedListContentState.Content
        sourceRefresh is LoadState.Loading || mediatorRefresh is LoadState.Loading -> PagedListContentState.Loading
        sourceRefresh is LoadState.Error || mediatorRefresh is LoadState.Error -> PagedListContentState.Error
        else -> PagedListContentState.Empty
    }
}

internal fun shouldShowPagedListSwipeRefresh(
    refreshLoading: Boolean,
    itemCount: Int,
    manualRefreshRequested: Boolean,
): Boolean {
    return refreshLoading && (itemCount == 0 || manualRefreshRequested)
}

internal fun shouldShowPagedListRefreshError(
    sourceRefreshError: Boolean,
    mediatorRefreshError: Boolean,
    manualRefreshRequested: Boolean,
): Boolean {
    return sourceRefreshError || (mediatorRefreshError && manualRefreshRequested)
}

internal fun pagedListErrorState(
    refresh: LoadState,
    append: LoadState,
    prepend: LoadState,
): PagedListErrorState? {
    return when {
        refresh is LoadState.Error -> PagedListErrorState.Refresh
        append is LoadState.Error || prepend is LoadState.Error -> PagedListErrorState.Page
        else -> null
    }
}
