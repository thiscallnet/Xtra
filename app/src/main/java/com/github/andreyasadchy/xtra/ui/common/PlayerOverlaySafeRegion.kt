package com.github.andreyasadchy.xtra.ui.common

import android.graphics.Rect
import kotlin.math.abs

internal enum class PlayerOverlaySafeSide {
    TOP,
    BOTTOM,
    LEFT,
    RIGHT,
}

internal data class PlayerOverlaySafeRegion(
    val bounds: Rect,
    val side: PlayerOverlaySafeSide,
) {
    val width: Int get() = bounds.width()
    val height: Int get() = bounds.height()
}

/** Returns the clearest viable rectangle around a floating player. */
internal fun findPlayerOverlaySafeRegion(
    pageBounds: Rect,
    playerBounds: Rect,
    gapPx: Int,
    minimumWidthPx: Int,
    minimumHeightPx: Int,
    preferredSide: PlayerOverlaySafeSide? = null,
    allowedSides: Set<PlayerOverlaySafeSide>? = null,
    minimumHeightForWidth: ((Int) -> Int)? = null,
): PlayerOverlaySafeRegion? {
    if (pageBounds.isEmpty || playerBounds.isEmpty || !Rect.intersects(pageBounds, playerBounds)) return null

    val obstacle = Rect(playerBounds).apply { inset(-gapPx, -gapPx) }
    val candidates = listOf(
        PlayerOverlaySafeRegion(
            Rect(pageBounds.left, pageBounds.top, pageBounds.right, obstacle.top.coerceIn(pageBounds.top, pageBounds.bottom)),
            PlayerOverlaySafeSide.TOP,
        ),
        PlayerOverlaySafeRegion(
            Rect(pageBounds.left, obstacle.bottom.coerceIn(pageBounds.top, pageBounds.bottom), pageBounds.right, pageBounds.bottom),
            PlayerOverlaySafeSide.BOTTOM,
        ),
        PlayerOverlaySafeRegion(
            Rect(pageBounds.left, pageBounds.top, obstacle.left.coerceIn(pageBounds.left, pageBounds.right), pageBounds.bottom),
            PlayerOverlaySafeSide.LEFT,
        ),
        PlayerOverlaySafeRegion(
            Rect(obstacle.right.coerceIn(pageBounds.left, pageBounds.right), pageBounds.top, pageBounds.right, pageBounds.bottom),
            PlayerOverlaySafeSide.RIGHT,
        ),
    ).filter {
        it.width > 0 && it.height > 0 && (allowedSides == null || it.side in allowedSides)
    }

    if (candidates.isEmpty()) return null
    val viable = candidates.filter {
        it.width >= minimumWidthPx &&
            it.height >= maxOf(minimumHeightPx, minimumHeightForWidth?.invoke(it.width) ?: 0)
    }
    if (viable.isEmpty()) return null
    val preferred = viable.firstOrNull { it.side == preferredSide }
    if (preferred != null) return preferred

    val pageCenterX = pageBounds.exactCenterX()
    val pageCenterY = pageBounds.exactCenterY()
    return viable.minWithOrNull(
        compareBy<PlayerOverlaySafeRegion> {
            val safeCenterX = it.bounds.exactCenterX()
            val safeCenterY = it.bounds.exactCenterY()
            abs(safeCenterX - pageCenterX) + abs(safeCenterY - pageCenterY)
        }.thenByDescending { it.width.toLong() * it.height },
    )
}
