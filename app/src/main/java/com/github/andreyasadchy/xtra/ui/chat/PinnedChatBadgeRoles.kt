package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.Badge

internal fun highestPinnedChatRoleBadge(badges: List<Badge>): Badge? =
    badges.firstOrNull { it.setId.equals("broadcaster", ignoreCase = true) }
        ?: badges.firstOrNull { it.setId.equals("moderator", ignoreCase = true) }
