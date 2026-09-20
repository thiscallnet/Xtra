package com.github.andreyasadchy.xtra.ui.chat

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEmoteInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatGifInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel
import com.github.andreyasadchy.xtra.ui.chat.v2.ui.ChatMessageTextView

internal fun isChatPopupMessageSelected(
    message: ChatMessage,
    selectedMessageId: String?,
    selectedMessage: ChatMessage?,
): Boolean = selectedMessageId?.takeIf { it.isNotBlank() }?.let { message.id == it } ?: (message === selectedMessage)

/** Message popout adapter backed exclusively by compiled Chat v2 rows. */
class MessageClickedChatAdapter(
    sourceMessages: List<ChatMessage>,
    private val messageTextSize: Float,
    private val animateGifs: Boolean,
    private val replyClick: (ChatMessage) -> Unit,
    private var v2Rows: List<ChatRowUiModel>,
    private val v2Assets: ChatAssetRepository,
    private val v2EmoteClick: ((ChatEmoteInteraction) -> Unit)?,
    private val v2GifClick: ((ChatGifInteraction) -> Unit)?,
    var selectedMessage: ChatMessage?,
) : RecyclerView.Adapter<MessageClickedChatAdapter.ViewHolder>() {

    private var selectedMessageId = selectedMessage?.id?.takeIf { it.isNotBlank() }

    val type = selectedMessage?.type
    val userId = selectedMessage?.userId
    val userLogin = selectedMessage?.userLogin
    val messages = synchronized(sourceMessages) {
        val filtered = if (type == ChatMessage.USER_MESSAGE) {
            sourceMessages.filter {
                (!userId.isNullOrBlank() && (it.userId == userId || it.replyParent?.userId == userId)) ||
                    (!userLogin.isNullOrBlank() && (it.userLogin == userLogin || it.replyParent?.userLogin == userLogin))
            }
        } else {
            sourceMessages.filter { it.type == type }
        }
        (filtered.ifEmpty { selectedMessage?.let(::listOf).orEmpty() }).toMutableList()
    }

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
            if (!isSelected(chatMessage)) {
                val previous = selectedMessage
                messageClickListener?.invoke(chatMessage, previous)
                selectedMessage = chatMessage
                selectedMessageId = chatMessage.id?.takeIf { it.isNotBlank() }
                applyChatInteractionSelectionBackground(holder.textView)
            } else if (chatMessage.type == ChatMessage.REPLY_MESSAGE || chatMessage.reply != null) {
                replyClick(chatMessage)
            }
        }
        holder.textView.bind(row)
        if (isSelected(chatMessage)) applyChatInteractionSelectionBackground(holder.textView)
    }

    override fun getItemCount(): Int = synchronized(messages) { messages.size }

    fun updateV2Messages(sourceMessages: List<ChatMessage>, rows: List<ChatRowUiModel>) {
        val filteredMessages = sourceMessages.filter(::belongsToSelectedUser)
        val ids = filteredMessages.mapTo(HashSet()) { it.id }
        synchronized(messages) {
            messages.clear()
            messages.addAll(filteredMessages)
        }
        selectedMessage = selectedMessageId?.let { id -> filteredMessages.firstOrNull { it.id == id } }
            ?: selectedMessage
        v2Rows = rows.filter { it.id.value in ids }
        notifyDataSetChanged()
    }

    private fun belongsToSelectedUser(message: ChatMessage): Boolean {
        if (type != ChatMessage.USER_MESSAGE) return message.type == type
        if (!userId.isNullOrBlank() && (message.userId == userId || message.replyParent?.userId == userId)) return true
        return !userLogin.isNullOrBlank() &&
            (message.userLogin.equals(userLogin, true) || message.replyParent?.userLogin.equals(userLogin, true))
    }

    private fun isSelected(message: ChatMessage): Boolean =
        isChatPopupMessageSelected(message, selectedMessageId, selectedMessage)

    inner class ViewHolder(val textView: ChatMessageTextView) : RecyclerView.ViewHolder(textView)
}
