package com.github.andreyasadchy.xtra.ui.chat.v2.domain

@JvmInline
value class ChatMessageId(val value: String)

@JvmInline
value class ChatAssetKey(val value: String)

data class ChatSessionKey(val channelId: String, val generation: Long)
