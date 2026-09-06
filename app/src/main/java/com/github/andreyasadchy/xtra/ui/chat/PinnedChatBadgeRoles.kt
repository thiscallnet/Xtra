package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.Badge

private val pinnedChatRoleBadgePriority = listOf(
    "broadcaster",
    "lead_moderator",
    "moderator",
)

internal fun highestPinnedChatRoleBadge(badges: List<Badge>): Badge? =
    pinnedChatRoleBadgePriority.asSequence()
        .mapNotNull { roleSetId ->
            badges.firstOrNull { it.setId.equals(roleSetId, ignoreCase = true) }
        }
        .firstOrNull()
