package com.github.andreyasadchy.xtra.model.twitchinbox

import java.time.Instant

enum class LocalSendState {
    SENDING,
    CONFIRMED,
    FAILED,
}

data class WhisperMessagePreview(
    val text: String?,
    val senderId: String?,
    val sentAt: Instant?,
)

data class WhisperMessage(
    val id: String,
    val nonce: String?,
    val senderId: String,
    val text: String,
    val sentAt: Instant?,
    val isMine: Boolean,
    val cursor: String? = null,
    val localState: LocalSendState = LocalSendState.CONFIRMED,
    val sendError: String? = null,
)
