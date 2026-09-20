package com.github.andreyasadchy.xtra.ui.chat

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEmoteInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatGifInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel
import com.github.andreyasadchy.xtra.ui.chat.v2.ui.ChatMessageTextView

/** Reply popout adapter backed exclusively by compiled Chat v2 rows. */
class ReplyClickedChatAdapter(
    sourceMessages: List<ChatMessage>,
    private val messageTextSize: Float,
    private val animateGifs: Boolean,
    var selectedMessage: ChatMessage?,
    private var v2Rows: List<ChatRowUiModel>,
    private val v2Assets: ChatAssetRepository,
    private val v2EmoteClick: ((ChatEmoteInteraction) -> Unit)?,
    private val v2GifClick: ((ChatGifInteraction) -> Unit)?,
) : RecyclerView.Adapter<ReplyClickedChatAdapter.ViewHolder>() {

    val threadParentId = selectedMessage?.reply?.threadParentId ?: selectedMessage?.replyParent?.id
    val messages = synchronized(sourceMessages) {
        sourceMessages.filter {
            (it.reply?.threadParentId == threadParentId || it.id == threadParentId) &&
                it.type != ChatMessage.REPLY_MESSAGE
        }.toMutableList()
    }.ifEmpty { selectedMessage?.let(::mutableListOf) ?: mutableListOf() }

    var messageClickListener: ((ChatMessage, ChatMessage?) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ChatMessageTextView(parent.context, v2Assets).apply {
            setMessageTextSizeSp(messageTextSize)
            setAnimateGifs(animateGifs)
            layoutParams = RecyclerView.LayoutParams(-1, -2)
        },
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val chatMessage = synchronized(messages) { messages.getOrNull(position) } ?: return
        val row = v2Rows.firstOrNull { it.id.value == chatMessage.id }
            ?: v2Rows.getOrNull(position)
            ?: error("Missing Chat v2 row for message ${chatMessage.id}")
        holder.textView.setInteractionCallbacks(
            onMessageLongClick = null,
            onEmoteClick = v2EmoteClick,
            onGifClick = v2GifClick,
        )
        holder.textView.setMessageClickCallback {
            if (chatMessage != selectedMessage) {
                val previous = selectedMessage
                messageClickListener?.invoke(chatMessage, previous)
                selectedMessage = chatMessage
                applyChatInteractionSelectionBackground(holder.textView)
            }
        }
        holder.textView.bind(row)
        if (chatMessage == selectedMessage) applyChatInteractionSelectionBackground(holder.textView)
    }

    override fun getItemCount(): Int = synchronized(messages) { messages.size }

    fun updateV2Messages(sourceMessages: List<ChatMessage>, rows: List<ChatRowUiModel>) {
        val threadMessages = sourceMessages.filter {
            (it.reply?.threadParentId == threadParentId || it.id == threadParentId) &&
                it.type != ChatMessage.REPLY_MESSAGE
        }
        val rowIds = rows.mapTo(HashSet()) { it.id.value }
        val filteredMessages = threadMessages.filter { it.id in rowIds }
        val selectedId = selectedMessage?.id
        selectedMessage = selectedId?.let { id -> filteredMessages.firstOrNull { it.id == id } } ?: selectedMessage
        synchronized(messages) {
            messages.clear()
            messages.addAll(filteredMessages)
        }
        val ids = filteredMessages.mapTo(HashSet()) { it.id }
        v2Rows = rows.filter { it.id.value in ids }
        notifyDataSetChanged()
    }

    inner class ViewHolder(val textView: ChatMessageTextView) : RecyclerView.ViewHolder(textView)
}
