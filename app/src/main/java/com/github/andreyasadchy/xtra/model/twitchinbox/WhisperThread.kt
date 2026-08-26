package com.github.andreyasadchy.xtra.model.twitchinbox

import java.time.Instant

data class WhisperThread(
    val id: String,
    val peer: TwitchUserSummary,
    val lastMessage: WhisperMessagePreview?,
    val unreadCount: Int?,
    val isUnread: Boolean,
    val updatedAt: Instant?,
)

data class WhisperThreadPage(
    val threads: List<WhisperThread>,
    val nextCursor: String?,
    val hasNextPage: Boolean,
    val unreadThreadCount: Int?,
)

data class WhisperThreadDetails(
    val peer: TwitchUserSummary,
    val messages: List<WhisperMessage>,
    val nextCursor: String?,
    val hasOlderMessages: Boolean,
    val unreadCount: Int?,
)
