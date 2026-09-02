package com.github.andreyasadchy.xtra.ui.chat.v2.presentation

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReply
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.SharedChatSource
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.TwitchChatMessageType

data class ChatRowUiModel(
    val id: ChatMessageId,
    val channelId: String,
    val timestampText: String?,
    val pieces: List<ChatPiece>,
    val background: Int,
    val accessibilityText: String,
    val reply: ChatReply?,
    val source: SharedChatSource?,
    val isAction: Boolean,
    val twitchType: TwitchChatMessageType = TwitchChatMessageType.Text,
)

sealed interface ChatPiece {
    data class Text(val value: String, val color: Int? = null) : ChatPiece
    data class Username(val value: String, val color: Int) : ChatPiece
    data class Badge(val asset: ChatAssetSpec) : ChatPiece
    data class Mention(val value: String, val userId: String?, val login: String?) : ChatPiece
    data class Emote(val asset: ChatAssetSpec, val fallback: String, val animated: Boolean = true) : ChatPiece
    data class Gif(val asset: ChatAssetSpec, val url: String, val fallback: String) : ChatPiece
    data class Cheermote(val asset: ChatAssetSpec, val value: String, val bits: Int, val color: Int?) : ChatPiece
}
