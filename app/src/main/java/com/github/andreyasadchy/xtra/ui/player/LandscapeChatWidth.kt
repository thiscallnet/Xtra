package com.github.andreyasadchy.xtra.ui.player

import kotlin.math.roundToInt

internal fun clampLandscapeChatWidth(
    configuredWidth: Int,
    availableWidth: Int,
): Int {
    if (configuredWidth <= 0) return 0
    return if (availableWidth > 0) configuredWidth.coerceAtMost(availableWidth) else configuredWidth
}

internal fun landscapeChatWidthForAvailableWidth(
    availableWidth: Int,
    percentage: Int,
): Int {
    if (availableWidth <= 0 || percentage <= 0) return 0
    return (availableWidth * percentage / 100f)
        .roundToInt()
        .coerceIn(1, availableWidth)
}
