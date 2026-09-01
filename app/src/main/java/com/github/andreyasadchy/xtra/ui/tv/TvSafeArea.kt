package com.github.andreyasadchy.xtra.ui.tv

import android.view.View
import android.view.ViewGroup
import androidx.core.view.updatePadding
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.isTelevision

fun View.applyTvSafePadding(
    left: Boolean = true,
    top: Boolean = true,
    right: Boolean = true,
    bottom: Boolean = true,
) {
    if (!context.isTelevision()) return
    val horizontal = resources.getDimensionPixelSize(R.dimen.tv_safe_horizontal)
    val vertical = resources.getDimensionPixelSize(R.dimen.tv_safe_vertical)
    updatePadding(
        left = if (left) horizontal else paddingLeft,
        top = if (top) vertical else paddingTop,
        right = if (right) horizontal else paddingRight,
        bottom = if (bottom) vertical else paddingBottom,
    )
}

fun View.disableTvClippingUpTree(levels: Int = 3) {
    if (!context.isTelevision()) return
    var current = parent as? ViewGroup
    repeat(levels) {
        current?.clipChildren = false
        current?.clipToPadding = false
        current = current?.parent as? ViewGroup
    }
}
