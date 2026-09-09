package com.github.andreyasadchy.xtra.repository.datasource

internal data class SearchQueryPageState(
    val api: String? = null,
    val cursor: String? = null,
    val exhausted: Boolean = false,
    val pagesLoaded: Int = 0,
)

internal data class SearchPageKey(
    val api: String,
    val cursor: String,
    val queryIndex: Int = 0,
    val queryStates: List<SearchQueryPageState> = emptyList(),
)

internal fun nextSearchPageKey(
    api: String,
    currentCursor: String?,
    candidate: String?,
): SearchPageKey? {
    val next = candidate?.takeIf { it.isNotBlank() && it != currentCursor } ?: return null
    return SearchPageKey(api, next)
}

internal fun nextSearchQueryIndex(
    currentIndex: Int,
    states: List<SearchQueryPageState>,
): Int? {
    if (states.isEmpty() || states.all(SearchQueryPageState::exhausted)) return null
    return (1..states.size)
        .map { offset -> (currentIndex + offset) % states.size }
        .firstOrNull { index -> !states[index].exhausted }
}

internal fun isSearchQueryExhausted(
    hasNextPage: Boolean,
    pagesLoaded: Int,
    pageBudget: Int?,
): Boolean = !hasNextPage || pageBudget?.let { pagesLoaded >= it } == true
