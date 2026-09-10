package com.github.andreyasadchy.xtra.ui.chat.v2.recommendations

data class CurrentChatToken(
    val text: String,
    val start: Int,
    val end: Int,
)

data class ChatTokenReplacement(
    val text: String,
    val cursor: Int,
)

object ChatInputToken {
    fun aroundCursor(text: CharSequence, cursor: Int): CurrentChatToken? {
        val value = text.toString()
        if (value.isEmpty()) return null
        val position = cursor.coerceIn(0, value.length)
        val tokenPosition = when {
            position < value.length && !value[position].isWhitespace() -> position
            position > 0 && !value[position - 1].isWhitespace() -> position - 1
            else -> return null
        }
        var start = tokenPosition
        var end = tokenPosition + 1
        while (start > 0 && !value[start - 1].isWhitespace()) start--
        while (end < value.length && !value[end].isWhitespace()) end++
        return CurrentChatToken(value.substring(start, end), start, end)
    }

    fun replace(
        text: CharSequence,
        cursor: Int,
        replacement: String,
    ): ChatTokenReplacement? {
        val value = text.toString()
        val token = aroundCursor(value, cursor) ?: return null
        return replaceRange(value, token.start, token.end, replacement, token.end)
    }

    fun replaceRange(
        text: CharSequence,
        start: Int,
        end: Int,
        replacement: String,
        cursor: Int = end,
    ): ChatTokenReplacement? {
        val value = text.toString()
        if (start < 0 || end < start || end > value.length) return null
        val suffix = if (end == value.length) " " else ""
        val next = value.substring(0, start) + replacement + suffix + value.substring(end)
        val originalCursor = cursor.coerceIn(0, value.length)
        val mappedCursor = when {
            originalCursor <= start -> originalCursor
            originalCursor <= end -> start + replacement.length
            else -> start + replacement.length + (originalCursor - end)
        } + if (end == value.length && originalCursor >= end) suffix.length else 0
        return ChatTokenReplacement(
            text = next,
            cursor = mappedCursor,
        )
    }

}
