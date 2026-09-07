package com.github.andreyasadchy.xtra.ui.chat.v2.ui

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel
import java.util.HashMap

/** Reusable indexes for the last accepted publication. The backing tables are kept between publications. */
internal class ChatPresentationReuseIndex {
    private val messagesById = HashMap<ChatMessageId, ChatMessage>()
    private val rowsById = HashMap<ChatMessageId, ChatRowUiModel>()
    private val orderedIds = ArrayDeque<ChatMessageId>()

    fun rowFor(message: ChatMessage): ChatRowUiModel? =
        rowsById[message.id]?.takeIf { messagesById[message.id] == message }

    /** Updates stable tables without allocating a new associateBy map on every publication. */
    fun replace(messages: List<ChatMessage>, rows: List<ChatRowUiModel>) {
        messagesById.clear()
        rowsById.clear()
        orderedIds.clear()
        messages.forEachIndexed { index, message ->
            messagesById[message.id] = message
            rows.getOrNull(index)?.let { row ->
                rowsById[row.id] = row
                orderedIds += row.id
            }
        }
    }

    /** Updates only the head evictions and newly appended rows for the common live-chat path. */
    fun append(
        messages: List<ChatMessage>,
        rows: List<ChatRowUiModel>,
        appendedCount: Int,
        evictedCount: Int,
    ): Boolean {
        if (orderedIds.size != messages.size - appendedCount + evictedCount) return false
        repeat(evictedCount.coerceAtMost(orderedIds.size)) {
            val evicted = orderedIds.removeFirst()
            messagesById.remove(evicted)
            rowsById.remove(evicted)
        }
        val start = (messages.size - appendedCount).coerceAtLeast(0)
        for (index in 0 until start) {
            val message = messages[index]
            if (messagesById[message.id] != message) {
                messagesById[message.id] = message
                rows.getOrNull(index)?.let { row -> rowsById[row.id] = row }
            }
        }
        for (index in start until messages.size) {
            val message = messages[index]
            val row = rows.getOrNull(index) ?: continue
            messagesById[message.id] = message
            rowsById[row.id] = row
            orderedIds += row.id
        }
        return true
    }

    fun clear() {
        messagesById.clear()
        rowsById.clear()
        orderedIds.clear()
    }
}

internal data class ChatAppendInfo(
    val appendedCount: Int,
    val evictedCount: Int,
)

/**
 * Recognizes the stable tail append shape without allocating a second ID map. All other
 * publications use the existing full reconciliation path.
 */
internal fun findChatAppendInfo(
    previous: List<ChatMessage>,
    current: List<ChatMessage>,
): ChatAppendInfo? {
    if (previous.isEmpty() || current.isEmpty()) return null
    val previousTail = previous.last().id
    val retainedTailIndex = current.indexOfFirst { it.id == previousTail }
    if (retainedTailIndex < 0 || retainedTailIndex >= current.lastIndex) return null
    val evictedCount = previous.size - retainedTailIndex - 1
    if (evictedCount < 0 || evictedCount > previous.size) return null
    val retainedCount = previous.size - evictedCount
    if (retainedCount == 0) return null
    for (index in 0 until retainedCount) {
        if (previous[index + evictedCount].id != current[index].id) return null
    }
    val appendedCount = current.size - retainedCount
    return ChatAppendInfo(appendedCount, evictedCount)
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
