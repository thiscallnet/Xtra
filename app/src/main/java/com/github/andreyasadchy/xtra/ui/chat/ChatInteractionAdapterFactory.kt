package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEmoteInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatGifInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel

/** Creates the v2-backed adapters used inside message and reply dialogs. */
internal class ChatInteractionAdapterFactory(
    private val configuration: ChatInteractionAdapterConfiguration,
    private val defaultMessages: List<ChatMessage> = emptyList(),
    private val onOpenReplyThread: (ChatMessage) -> Unit = {},
) {
    private var selectedMessage: ChatMessage? = null

    fun createMessageClickedChatAdapter(
        sourceMessages: List<ChatMessage>,
        selectedMessageOverride: ChatMessage? = selectedMessage,
        v2Rows: List<ChatRowUiModel>,
        v2Assets: ChatAssetRepository,
        v2EmoteClick: ((ChatEmoteInteraction) -> Unit)? = null,
        v2GifClick: ((ChatGifInteraction) -> Unit)? = null,
    ): MessageClickedChatAdapter = MessageClickedChatAdapter(
        sourceMessages = sourceMessages,
        messageTextSize = configuration.messageTextSize,
        animateGifs = configuration.animateGifs,
        replyClick = { chatMessage ->
            selectedMessage = chatMessage
            onOpenReplyThread(chatMessage)
        },
        selectedMessage = selectedMessageOverride,
        v2Rows = v2Rows,
        v2Assets = v2Assets,
        v2EmoteClick = v2EmoteClick,
        v2GifClick = v2GifClick,
    )

    fun createReplyClickedChatAdapter(
        sourceMessages: List<ChatMessage> = defaultMessages,
        selectedMessageOverride: ChatMessage? = selectedMessage,
        v2Rows: List<ChatRowUiModel>,
        v2Assets: ChatAssetRepository,
        v2EmoteClick: ((ChatEmoteInteraction) -> Unit)? = null,
        v2GifClick: ((ChatGifInteraction) -> Unit)? = null,
    ): ReplyClickedChatAdapter = ReplyClickedChatAdapter(
        sourceMessages = sourceMessages,
        messageTextSize = configuration.messageTextSize,
        animateGifs = configuration.animateGifs,
        selectedMessage = selectedMessageOverride,
        v2Rows = v2Rows,
        v2Assets = v2Assets,
        v2EmoteClick = v2EmoteClick,
        v2GifClick = v2GifClick,
    )
}
