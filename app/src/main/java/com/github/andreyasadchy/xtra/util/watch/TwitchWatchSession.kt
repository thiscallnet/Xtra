package com.github.andreyasadchy.xtra.util.watch

import java.util.UUID

data class TwitchWatchSession(
    val channelId: String,
    val channelLogin: String,
    val streamId: String?,
    val userId: String,
    val sessionId: String = UUID.randomUUID().toString(),
)
