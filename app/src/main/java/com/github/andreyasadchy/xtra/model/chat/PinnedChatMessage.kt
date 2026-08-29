package com.github.andreyasadchy.xtra.model.chat

data class PinnedChatMessage(
    val id: String,
    val pinnedBy: String,
    val pinnedByBadges: List<Badge> = emptyList(),
    val text: String,
    val sender: String? = null,
    val senderColor: String? = null,
    val senderBadges: List<Badge> = emptyList(),
    val sentAt: Long? = null,
)
