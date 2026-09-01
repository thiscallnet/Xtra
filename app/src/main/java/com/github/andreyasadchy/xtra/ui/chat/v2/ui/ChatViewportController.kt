package com.github.andreyasadchy.xtra.ui.chat.v2.ui

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel

enum class FollowMode { FOLLOWING_BOTTOM, USER_SCROLLED_UP }

data class ChatViewportAnchor(val messageId: ChatMessageId, val topOffsetPx: Int)

data class ChatViewportState(
    val followMode: FollowMode = FollowMode.FOLLOWING_BOTTOM,
    val newMessageCount: Int = 0,
    val anchor: ChatViewportAnchor? = null,
)

/** Dataset commits never stop. This controller only decides where the viewport stays. */
class ChatViewportController(
    private val recyclerView: RecyclerView,
    initialState: ChatViewportState = ChatViewportState(),
) {
    var state: ChatViewportState = initialState
        private set

    fun restore(savedState: ChatViewportState) { state = savedState }

    /** Channel/session changes start at the newest tail; an old anchor is not meaningful. */
    fun resetForNewSession() {
        state = ChatViewportState()
    }

    fun captureAnchor(adapter: RecyclerView.Adapter<*>): ChatViewportAnchor? {
        val layout = recyclerView.layoutManager as? LinearLayoutManager ?: return null
        val position = layout.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return null
        val view = layout.findViewByPosition(position) ?: return null
        return (adapter as? ChatTimelineAdapter)?.currentList?.getOrNull(position)?.id?.let {
            ChatViewportAnchor(it, view.top)
        }
    }

    fun onUserScroll() {
        if (!recyclerView.canScrollVertically(1)) {
            state = state.copy(followMode = FollowMode.FOLLOWING_BOTTOM, newMessageCount = 0)
        } else {
            state = state.copy(followMode = FollowMode.USER_SCROLLED_UP)
        }
    }

    fun onSnapshotCommitted(previousAnchor: ChatViewportAnchor?, rows: List<ChatRowUiModel>, appendedCount: Int) {
        if (state.followMode == FollowMode.FOLLOWING_BOTTOM) {
            if (rows.isNotEmpty()) recyclerView.scrollToPosition(rows.lastIndex)
            state = state.copy(anchor = rows.lastOrNull()?.let { ChatViewportAnchor(it.id, 0) })
            return
        }
        state = state.copy(newMessageCount = state.newMessageCount + appendedCount)
        val anchor = previousAnchor ?: state.anchor
        val position = anchor?.let { rows.indexOfFirst { row -> row.id == it.messageId } } ?: -1
        if (position >= 0) {
            (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, anchor!!.topOffsetPx)
            state = state.copy(anchor = anchor)
        } else if (rows.isNotEmpty()) {
            (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
            state = state.copy(anchor = ChatViewportAnchor(rows.first().id, 0))
        }
    }

    fun jumpToNewest(rows: List<ChatRowUiModel>) {
        state = ChatViewportState(FollowMode.FOLLOWING_BOTTOM, 0, rows.lastOrNull()?.let { ChatViewportAnchor(it.id, 0) })
        if (rows.isNotEmpty()) recyclerView.scrollToPosition(rows.lastIndex)
    }
}
