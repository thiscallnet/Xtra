package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReply

internal fun ChatReply.legacyThreadParentId(): String =
    threadMessageId?.value ?: parentMessageId.value

internal fun ChatMessage.isHighlightedMessage(): Boolean =
    msgId.equals("highlighted-message", ignoreCase = true) ||
        msgId.equals("channel_points_highlighted", ignoreCase = true) ||
        reward?.title.equals("Highlight My Message", ignoreCase = true) ||
        reward?.title.equals("Send Highlighted Message", ignoreCase = true)

internal fun ChatMessage.effectiveNoticeId(): String? = sourceMsgId ?: msgId

internal fun ChatMessage.isWatchStreakNotice(): Boolean =
    watchStreakCount != null && (
        effectiveNoticeId().equals("viewermilestone", ignoreCase = true) ||
            effectiveNoticeId().equals("watch_streak", ignoreCase = true) ||
            effectiveNoticeId().equals("watch-streak", ignoreCase = true)
        )

internal fun ChatMessage.isSubscriptionNotice(): Boolean = effectiveNoticeId()?.lowercase() in setOf(
    "sub",
    "resub",
    "subgift",
    "submysterygift",
    "giftpaidupgrade",
    "anongiftpaidupgrade",
    "prime_paid_upgrade",
    "gift_paid_upgrade",
    "sub_gift",
    "community_sub_gift",
    "shared_chat_sub",
    "shared_chat_resub",
    "shared_chat_sub_gift",
    "shared_chat_community_sub_gift",
    "pay_it_forward",
    "shared_chat_gift_paid_upgrade",
    "shared_chat_prime_paid_upgrade",
    "shared_chat_pay_it_forward",
)
