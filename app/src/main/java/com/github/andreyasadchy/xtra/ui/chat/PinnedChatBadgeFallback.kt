package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.R

internal fun pinnedChatBadgeFallbackResource(setId: String): Int? = when (setId) {
    "moderator" -> R.drawable.ic_moderator_badge
    "broadcaster" -> R.drawable.ic_broadcaster_badge
    else -> null
}
