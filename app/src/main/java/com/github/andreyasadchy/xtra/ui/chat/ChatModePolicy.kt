package com.github.andreyasadchy.xtra.ui.chat

/** Chat v2 is mandatory for every live chat that has the identifiers required by its session. */
internal fun shouldUseChatV2ForLive(
    isLive: Boolean,
    channelId: String?,
    channelLogin: String?,
): Boolean = isLive && !channelId.isNullOrBlank() && !channelLogin.isNullOrBlank()
