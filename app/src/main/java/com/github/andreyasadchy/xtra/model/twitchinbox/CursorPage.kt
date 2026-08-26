package com.github.andreyasadchy.xtra.model.twitchinbox

data class CursorPage<T>(
    val items: List<T>,
    val nextCursor: String?,
    val hasNextPage: Boolean,
)
