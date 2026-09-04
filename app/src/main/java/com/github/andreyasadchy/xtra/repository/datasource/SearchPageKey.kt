package com.github.andreyasadchy.xtra.repository.datasource

internal data class SearchPageKey(
    val api: String,
    val cursor: String,
)

internal fun nextSearchPageKey(
    api: String,
    currentCursor: String?,
    candidate: String?,
): SearchPageKey? {
    val next = candidate?.takeIf { it.isNotBlank() && it != currentCursor } ?: return null
    return SearchPageKey(api, next)
}
