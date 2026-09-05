package com.github.andreyasadchy.xtra.ui.following.overview

import android.view.View
import androidx.recyclerview.widget.RecyclerView

internal object ShelfCardSizing {

    fun apply(itemView: View, shelf: RecyclerView) {
        val params = itemView.layoutParams ?: RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.WRAP_CONTENT,
            RecyclerView.LayoutParams.WRAP_CONTENT,
        )
        val targetWidth = cardWidth(shelf)
        if (params.width != targetWidth) {
            params.width = targetWidth
            itemView.layoutParams = params
        }
    }

    private fun cardWidth(shelf: RecyclerView): Int {
        val measuredWidth = shelf.width.takeIf { it > 0 }
            ?: shelf.rootView.width.takeIf { it > 0 }
            ?: shelf.resources.displayMetrics.widthPixels
        val availableWidth = (measuredWidth - shelf.paddingLeft - shelf.paddingRight).coerceAtLeast(1)
        val density = shelf.resources.displayMetrics.density
        val widthDp = availableWidth / density
        val cardWidthDp = when {
            widthDp < 600f -> (availableWidth / 1.45f / density).coerceIn(220f, 280f)
            widthDp < 840f -> (availableWidth / 2.6f / density).coerceIn(200f, 300f)
            else -> (availableWidth / 4.0f / density).coerceIn(220f, 320f)
        }
        return (cardWidthDp * density).toInt()
    }
}
