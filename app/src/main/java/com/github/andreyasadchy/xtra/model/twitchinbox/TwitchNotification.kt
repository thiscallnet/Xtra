package com.github.andreyasadchy.xtra.model.twitchinbox

import java.time.Instant

data class TwitchNotification(
    val id: String,
    val type: String?,
    val title: String?,
    val body: String,
    val createdAt: Instant?,
    val imageUrl: String?,
    val isUnread: Boolean,
    val canDismiss: Boolean,
    val action: TwitchNotificationAction?,
)

data class TwitchNotificationPage(
    val notifications: List<TwitchNotification>,
    val nextCursor: String?,
    val hasNextPage: Boolean,
    val unreadCount: Int?,
)

data class NotificationUnreadSummary(
    val count: Int?,
    val hasUnread: Boolean,
)
