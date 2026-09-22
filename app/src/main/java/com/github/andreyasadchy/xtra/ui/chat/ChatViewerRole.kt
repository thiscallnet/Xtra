package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.ui.chat.v2.recommendations.ChatUserSuggestion

/** Role of the signed-in chat identity in the currently connected live channel. */
enum class ChatViewerRole {
    UNKNOWN,
    VIEWER,
    MODERATOR,
    BROADCASTER,
}

data class ChatViewerRoleSnapshot(
    val channelId: String? = null,
    val viewerId: String? = null,
    val viewerLogin: String? = null,
    val role: ChatViewerRole = ChatViewerRole.UNKNOWN,
    val observedAtMs: Long = 0L,
    val sessionGeneration: Long = 0L,
)

enum class ChatModeratorAction {
    BAN,
    TIMEOUT,
    REMOVE,
}

data class ChatModeratorActionRequest(
    val action: ChatModeratorAction,
    val targetId: String? = null,
    val targetLogin: String,
    val duration: String? = null,
    val reason: String? = null,
)

sealed interface ChatModeratorActionResult {
    data object Success : ChatModeratorActionResult
    data class Failure(val message: String) : ChatModeratorActionResult
}

internal data class ChatUserPresenceSnapshot(
    val broadcasters: Set<String>,
    val moderators: Set<String>,
    val vips: Set<String>,
    val viewers: Set<String>,
    val suggestions: List<ChatUserSuggestion>,
)
