package com.github.andreyasadchy.xtra.util

import android.graphics.Rect
import kotlin.math.roundToInt

/** Geometry shared by player overlays and other floating player UI. */
data class PlayerUiGeometry(
    val bounds: Rect,
    val safeBounds: Rect,
    val edgePaddingPx: Int,
) {
    companion object {
        fun from(
            bounds: Rect,
            insets: Rect,
            density: Float,
            edgePaddingDp: Int = 10,
        ): PlayerUiGeometry {
            val left = (bounds.left + insets.left).coerceIn(bounds.left, bounds.right)
            val top = (bounds.top + insets.top).coerceIn(bounds.top, bounds.bottom)
            val right = (bounds.right - insets.right).coerceIn(left, bounds.right)
            val bottom = (bounds.bottom - insets.bottom).coerceIn(top, bounds.bottom)
            val safe = Rect(left, top, right, bottom)
            return PlayerUiGeometry(
                bounds = Rect(bounds),
                safeBounds = safe,
                edgePaddingPx = (edgePaddingDp * density).roundToInt(),
            )
        }
    }

    fun paddedSafeBounds(): Rect {
        val horizontalPadding = edgePaddingPx.coerceAtMost(safeBounds.width().coerceAtLeast(0) / 2)
        val verticalPadding = edgePaddingPx.coerceAtMost(safeBounds.height().coerceAtLeast(0) / 2)
        return Rect(
            safeBounds.left + horizontalPadding,
            safeBounds.top + verticalPadding,
            safeBounds.right - horizontalPadding,
            safeBounds.bottom - verticalPadding,
        )
    }
}
