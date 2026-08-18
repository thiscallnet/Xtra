package com.github.andreyasadchy.xtra.ui.player

internal fun clampLandscapeChatWidth(
    configuredWidth: Int,
    availableWidth: Int,
): Int {
    if (configuredWidth <= 0) return 0
    return if (availableWidth > 0) configuredWidth.coerceAtMost(availableWidth) else configuredWidth
}
