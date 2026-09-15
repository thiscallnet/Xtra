package com.github.andreyasadchy.xtra.ui.player.hud

import kotlin.math.max

data class HudRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom

    fun inset(horizontal: Float, vertical: Float = horizontal): HudRect = HudRect(
        left + horizontal,
        top + vertical,
        (right - horizontal).coerceAtLeast(left + horizontal),
        (bottom - vertical).coerceAtLeast(top + vertical),
    )

    fun clampInside(bounds: HudRect): HudRect {
        val width = width.coerceAtMost(bounds.width)
        val height = height.coerceAtMost(bounds.height)
        val left = left.coerceIn(bounds.left, bounds.right - width)
        val top = top.coerceIn(bounds.top, bounds.bottom - height)
        return HudRect(left, top, left + width, top + height)
    }

    fun offsetBy(x: Float, y: Float): HudRect = HudRect(
        left + x,
        top + y,
        right + x,
        bottom + y,
    )

    fun expandedTo(minWidth: Float, minHeight: Float): HudRect {
        val width = max(width, minWidth)
        val height = max(height, minHeight)
        return HudRect(
            centerX - width / 2f,
            centerY - height / 2f,
            centerX + width / 2f,
            centerY + height / 2f,
        )
    }

    fun overlaps(other: HudRect, gap: Float = 0f): Boolean =
        left < other.right - gap && right > other.left + gap &&
            top < other.bottom - gap && bottom > other.top + gap
}

data class HudSize(
    val width: Float,
    val height: Float,
)
