package com.github.andreyasadchy.xtra.ui.chat.v2.ui

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel
import java.util.HashMap

/** Reusable indexes for the last accepted publication. The backing tables are kept between publications. */
internal class ChatPresentationReuseIndex {
    private val messagesById = HashMap<ChatMessageId, ChatMessage>()
    private val rowsById = HashMap<ChatMessageId, ChatRowUiModel>()

    fun rowFor(message: ChatMessage): ChatRowUiModel? =
        rowsById[message.id]?.takeIf { messagesById[message.id] == message }

    /** Updates stable tables without allocating a new associateBy map on every publication. */
    fun replace(messages: List<ChatMessage>, rows: List<ChatRowUiModel>) {
        messagesById.clear()
        rowsById.clear()
        messages.forEachIndexed { index, message ->
            messagesById[message.id] = message
            rows.getOrNull(index)?.let { row -> rowsById[row.id] = row }
        }
    }

    fun clear() {
        messagesById.clear()
        rowsById.clear()
    }
}

internal data class ChatRowCompileResult(
    val rows: List<ChatRowUiModel>,
    val messagesChanged: Int,
    val rowsCompiled: Int,
    val rowsReused: Int,
)

internal fun compileChatRows(
    messages: List<ChatMessage>,
    resolve: (ChatMessage, Int) -> ChatRowUiModel,
    reuseIndex: ChatPresentationReuseIndex? = null,
): ChatRowCompileResult {
    val rows = ArrayList<ChatRowUiModel>(messages.size)
    var changed = 0
    var compiled = 0
    var reused = 0
    messages.forEachIndexed { index, message ->
        val reusable = reuseIndex?.rowFor(message)
        if (reusable != null) {
            rows += reusable
            reused++
        } else {
            changed++
            compiled++
            rows += resolve(message, index)
        }
    }
    return ChatRowCompileResult(rows, changed, compiled, reused)
}
