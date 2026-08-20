package com.github.andreyasadchy.xtra.util.chat

internal data class ChatBadgeVisibility(
    val showTwitchBadges: Boolean,
    val showStvBadges: Boolean,
    val loadStvUser: Boolean,
)

internal fun chatBadgeVisibility(
    showBadges: Boolean,
    showStvBadges: Boolean,
    showNamePaints: Boolean,
    showPersonalEmotes: Boolean,
): ChatBadgeVisibility {
    return ChatBadgeVisibility(
        showTwitchBadges = showBadges,
        showStvBadges = showBadges && showStvBadges,
        loadStvUser = showNamePaints || showPersonalEmotes || (showBadges && showStvBadges),
    )
}
