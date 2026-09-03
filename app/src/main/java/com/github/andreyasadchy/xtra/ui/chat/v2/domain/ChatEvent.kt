package com.github.andreyasadchy.xtra.ui.chat.v2.domain

import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatDecorationUpdate

sealed interface ChatEvent {
    val eventId: String?
    val receivedAtMs: Long

    data class Message(
        val message: ChatMessage,
        override val eventId: String? = message.id.value,
        override val receivedAtMs: Long = System.currentTimeMillis(),
    ) : ChatEvent
    data class Delete(val messageId: ChatMessageId, override val eventId: String?, override val receivedAtMs: Long) : ChatEvent
    data class ClearUser(val userId: String, override val eventId: String?, override val receivedAtMs: Long) : ChatEvent
    data class Clear(override val eventId: String?, override val receivedAtMs: Long) : ChatEvent
    data class SettingsUpdated(
        val channelId: String,
        val slowModeSeconds: Int?,
        val followerOnlyDurationMinutes: Int?,
        val subscriberOnly: Boolean,
        val emoteOnly: Boolean,
        val uniqueChatMode: Boolean,
        override val eventId: String?,
        override val receivedAtMs: Long,
    ) : ChatEvent
    data class Notice(val message: ChatMessage, override val eventId: String?, override val receivedAtMs: Long) : ChatEvent
    data class DecorationUpdated(
        val update: ChatDecorationUpdate,
        override val eventId: String? = null,
        override val receivedAtMs: Long = System.currentTimeMillis(),
    ) : ChatEvent
    /** Transport lifecycle is not a timeline item. It lets the session reconcile after a gap. */
    data class TransportDisconnected(
        val reason: String? = null,
        override val eventId: String? = null,
        override val receivedAtMs: Long = System.currentTimeMillis(),
    ) : ChatEvent
}
