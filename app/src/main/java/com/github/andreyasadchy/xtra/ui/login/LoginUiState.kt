package com.github.andreyasadchy.xtra.ui.login

import android.net.Uri
import com.github.andreyasadchy.xtra.model.id.DeviceCodeResponse
import com.github.andreyasadchy.xtra.repository.auth.REQUIRED_OFFICIAL_SCOPES

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

    /** The official grant is active; the second approval enables enhanced compatibility features. */
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

internal val HELIX_LOGIN_SCOPES = REQUIRED_OFFICIAL_SCOPES.toList()

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
