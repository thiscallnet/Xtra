package com.github.andreyasadchy.xtra.ui.chat

internal sealed interface ChatReplayPageRequest {
    data object Initial : ChatReplayPageRequest

    data class Offset(val seconds: Int) : ChatReplayPageRequest

    data class Cursor(val value: String) : ChatReplayPageRequest
}

/** Keeps replay pagination safe when a cursor becomes unusable mid-session. */
internal class ChatReplayPagination {
    private var previousPageMessageIds = emptySet<String>()
    private var forcedBoundaryEscapeFromSeconds: Int? = null

    var offsetPagination = false
        private set

    var cursor: String? = null
        private set

    var nextOffsetSeconds: Int? = null
        private set

    val hasMorePages: Boolean
        get() = !cursor.isNullOrBlank() || nextOffsetSeconds != null

    fun reset() {
        offsetPagination = false
        resetWindow()
    }

    /** Clears the current page window while retaining a known-safe pagination mode. */
    fun resetWindow() {
        cursor = null
        nextOffsetSeconds = null
        previousPageMessageIds = emptySet()
        forcedBoundaryEscapeFromSeconds = null
    }

    fun request(positionOffsetSeconds: Int?): ChatReplayPageRequest {
        return when {
            positionOffsetSeconds != null -> ChatReplayPageRequest.Offset(positionOffsetSeconds)
            offsetPagination && nextOffsetSeconds != null -> ChatReplayPageRequest.Offset(nextOffsetSeconds!!)
            !cursor.isNullOrBlank() -> ChatReplayPageRequest.Cursor(cursor!!)
            nextOffsetSeconds != null -> ChatReplayPageRequest.Offset(nextOffsetSeconds!!)
            else -> ChatReplayPageRequest.Initial
        }
    }

    fun onCursorFailure() {
        offsetPagination = true
        cursor = null
    }

    fun <T> acceptPage(
        request: ChatReplayPageRequest,
        messages: List<T>,
        hasNextPage: Boolean,
        nextCursor: String?,
        messageId: (T) -> String?,
        contentOffsetSeconds: (T) -> Int?,
        pageBoundaryOffsetSeconds: Int? = null,
        onForcedBoundaryEscape: (fromSeconds: Int, toSeconds: Int) -> Unit = { _, _ -> },
    ): List<T> {
        val pageIds = messages.mapNotNullTo(mutableSetOf(), messageId)
        val newMessages = messages.filter { message ->
            messageId(message) == null || messageId(message) !in previousPageMessageIds
        }
        val boundarySeconds = pageBoundaryOffsetSeconds
            ?: messages.asReversed().firstNotNullOfOrNull(contentOffsetSeconds)
        previousPageMessageIds = pageIds

        if (!hasNextPage) {
            cursor = null
            nextOffsetSeconds = null
            forcedBoundaryEscapeFromSeconds = null
            return newMessages
        }

        val normalizedNextCursor = nextCursor?.takeIf(String::isNotBlank)
        when (request) {
            is ChatReplayPageRequest.Cursor -> {
                if (normalizedNextCursor != null && normalizedNextCursor != request.value) {
                    cursor = normalizedNextCursor
                    nextOffsetSeconds = boundarySeconds
                    forcedBoundaryEscapeFromSeconds = null
                } else {
                    // The cursor stopped making token progress. Keep the known
                    // boundary and permanently use inclusive offset pagination.
                    offsetPagination = true
                    cursor = null
                    nextOffsetSeconds = boundarySeconds
                    forcedBoundaryEscapeFromSeconds = null
                }
            }

            is ChatReplayPageRequest.Offset -> {
                if (!offsetPagination && normalizedNextCursor != null) {
                    // The initial position is represented as an offset, but the
                    // first page can still establish cursor pagination.
                    cursor = normalizedNextCursor
                    nextOffsetSeconds = boundarySeconds
                    forcedBoundaryEscapeFromSeconds = null
                } else {
                    cursor = null
                    if (newMessages.isNotEmpty() && boundarySeconds != null) {
                    // Keep the boundary second. The offset API is inclusive, and
                    // overlap is removed using the previous page IDs.
                        nextOffsetSeconds = boundarySeconds
                        forcedBoundaryEscapeFromSeconds = null
                    } else if (boundarySeconds != null && boundarySeconds > request.seconds) {
                        nextOffsetSeconds = boundarySeconds
                        forcedBoundaryEscapeFromSeconds = null
                    } else if (boundarySeconds != null && forcedBoundaryEscapeFromSeconds != request.seconds - 1) {
                        // A repeated inclusive boundary cannot make offset progress.
                        // Escape it exactly once; a second repeated boundary terminates
                        // instead of spinning forever.
                        val escapedSeconds = if (request.seconds < Int.MAX_VALUE) {
                            request.seconds + 1
                        } else {
                            null
                        }
                        if (escapedSeconds != null) {
                            forcedBoundaryEscapeFromSeconds = request.seconds
                            nextOffsetSeconds = escapedSeconds
                            onForcedBoundaryEscape(request.seconds, escapedSeconds)
                        } else {
                            nextOffsetSeconds = null
                            forcedBoundaryEscapeFromSeconds = null
                        }
                    } else {
                        nextOffsetSeconds = null
                        forcedBoundaryEscapeFromSeconds = null
                    }
                }
            }

            ChatReplayPageRequest.Initial -> {
                cursor = normalizedNextCursor
                nextOffsetSeconds = boundarySeconds
                forcedBoundaryEscapeFromSeconds = null
            }
        }
        return newMessages
    }
}
