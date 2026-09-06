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
        val appendSpace = token.end == value.length
        val suffix = if (appendSpace) " " else ""
        val next = value.substring(0, token.start) + replacement + suffix + value.substring(token.end)
        return ChatTokenReplacement(
            text = next,
            cursor = token.start + replacement.length + suffix.length,
        )
    }

}
