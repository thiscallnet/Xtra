package com.github.andreyasadchy.xtra.ui.chat

/** A live chat can use the process-owned v2 session when its session identifiers are available. */
internal fun shouldUseChatV2ForLive(
    isLive: Boolean,
    channelId: String?,
    channelLogin: String?,
): Boolean = isLive && !channelId.isNullOrBlank() && !channelLogin.isNullOrBlank()
