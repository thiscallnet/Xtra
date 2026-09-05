package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.Badge

internal fun pinnedChatBadgeFallbackResource(setId: String): Int? = when (setId) {
    "moderator" -> R.drawable.ic_moderator_badge
    "broadcaster" -> R.drawable.ic_broadcaster_badge
    else -> null
}

internal fun highestPinnedChatRoleBadge(badges: List<Badge>) =
    badges.firstOrNull { it.setId.equals("broadcaster", ignoreCase = true) }
        ?: badges.firstOrNull { it.setId.equals("moderator", ignoreCase = true) }
