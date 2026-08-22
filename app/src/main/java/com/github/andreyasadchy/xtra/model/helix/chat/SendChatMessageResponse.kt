package com.github.andreyasadchy.xtra.model.helix.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class SendChatMessageResponse(
    val data: List<Message> = emptyList(),
) {
    @Serializable
    class Message(
        @SerialName("message_id")
        val messageId: String? = null,
        @SerialName("is_sent")
        val isSent: Boolean = false,
        @SerialName("drop_reason")
        val dropReason: DropReason? = null,
    )

    @Serializable
    class DropReason(
        val code: String? = null,
        val message: String? = null,
    )
}

data class SendChatMessageResult(
    val isSent: Boolean,
    val messageId: String? = null,
    val dropReasonCode: String? = null,
    val dropReasonMessage: String? = null,
    val errorMessage: String? = null,
)
