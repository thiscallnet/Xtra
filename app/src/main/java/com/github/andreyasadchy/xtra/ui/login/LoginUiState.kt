package com.github.andreyasadchy.xtra.ui.login

import android.net.Uri
import com.github.andreyasadchy.xtra.model.id.DeviceCodeResponse

enum class LoginError {
    SETUP_REQUIRED,
    NETWORK,
    SERVER,
    DENIED,
    EXPIRED,
    ACCOUNT_MISMATCH,
    MISSING_SCOPES,
    VALIDATION,
    PERSISTENCE,
    BROWSER_UNAVAILABLE,
    MALFORMED_RESPONSE,
    UNKNOWN,
}

sealed interface LoginUiState {
    data object Idle : LoginUiState
    data object Starting : LoginUiState

    data class WaitingForAuthorization(
        val verificationUri: Uri,
        val userCode: String,
        val expiresAtMillis: Long,
        val isPolling: Boolean,
    ) : LoginUiState

    data class CompatibilityAuthorization(
        val verificationUri: Uri,
        val userCode: String,
        val expiresAtMillis: Long,
        val isPolling: Boolean,
    ) : LoginUiState

    /** The first grant is staged; the second approval is still required before Xtra is signed in. */
    data object CompatibilityReady : LoginUiState

    data object Validating : LoginUiState

    /** The complete pair is being persisted; cancellation must wait for this to finish. */
    data object Committing : LoginUiState

    data class Error(
        val type: LoginError,
        val recoverable: Boolean,
    ) : LoginUiState

    data class CompatibilityError(
        val type: LoginError,
        val recoverable: Boolean,
    ) : LoginUiState

    data class Complete(
        val accountChanged: Boolean,
        val revocationFailures: Int,
    ) : LoginUiState

    data class LoggedOut(
        val revocationFailures: Int,
    ) : LoginUiState
}

internal val HELIX_LOGIN_SCOPES = listOf(
    "channel:edit:commercial", // ChannelPage / commercials.
    "channel:manage:broadcast", // Stream markers and broadcast settings.
    "channel:manage:moderators", // Channel moderation management.
    "channel:manage:raids", // Channel raids.
    "channel:manage:vips", // Channel VIP management.
    "channel:moderate", // Channel moderation actions.
    "channel:read:polls", // Own-channel poll/EventSub activity.
    "channel:read:predictions", // Own-channel prediction/EventSub activity.
    "chat:edit", // IRC chat commands.
    "chat:read", // IRC chat messages.
    "moderator:manage:announcements", // Chat announcements.
    "moderator:manage:banned_users", // Moderation bans.
    "moderator:manage:chat_messages", // Moderation chat actions.
    "moderator:manage:chat_settings", // Chat settings.
    "moderator:read:chatters", // Chatter lists.
    "moderator:read:followers", // Channel followers.
    "user:manage:chat_color", // Chat color.
    "user:manage:whispers", // Whispers.
    "user:edit", // Account/profile editing.
    "user:read:blocked_users", // Account blocked-user list.
    "user:manage:blocked_users", // Account blocked-user actions.
    "user:read:chat", // Chat identity and permissions.
    "user:read:emotes", // User chat emotes.
    "user:read:follows", // Followed streams/channels.
    "user:write:chat", // Helix chat messages.
)

internal val GQL_COMPATIBILITY_SCOPES = listOf(
    "channel_read",
    "chat:read",
    "user_blocks_edit",
    "user_blocks_read",
    "user_follows_edit",
    "user_read",
)

internal fun selectVerificationUri(response: DeviceCodeResponse): String? =
    response.verificationUriComplete ?: response.verificationUri?.let { verificationUri ->
        if (!verificationUri.contains("?device-code=") &&
            !verificationUri.contains("&device-code=") &&
            !response.userCode.isNullOrBlank()
        ) {
            val separator = when {
                verificationUri.endsWith('?') || verificationUri.endsWith('&') -> ""
                verificationUri.contains('?') -> "&"
                else -> "?"
            }
            "$verificationUri${separator}device-code=${response.userCode}"
        } else {
            verificationUri
        }
    }
