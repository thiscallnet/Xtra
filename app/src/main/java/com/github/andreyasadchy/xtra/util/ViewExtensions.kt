package com.github.andreyasadchy.xtra.util

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

fun ViewPager2.reduceDragSensitivity() {
    try {
        val recyclerViewField = ViewPager2::class.java.getDeclaredField("mRecyclerView")
        recyclerViewField.isAccessible = true
        val recyclerView = recyclerViewField.get(this) as RecyclerView

        val touchSlopField = RecyclerView::class.java.getDeclaredField("mTouchSlop")
        touchSlopField.isAccessible = true
        val touchSlop = touchSlopField.get(recyclerView) as Int
        touchSlopField.set(recyclerView, touchSlop * 2)
    } catch (e: Exception) {
    }
}

/** Keeps tab paging lightweight and leaves expensive child work to the visible page. */
fun ViewPager2.configureForSmoothPaging() {
    offscreenPageLimit = 1
    (getChildAt(0) as? RecyclerView)?.apply {
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        setHasFixedSize(true)
    }
    reduceDragSensitivity()
}

/**
 * Offsets a collapsing AppBar child below the status bar/cutout.
 *
 * Uses the stable status-bar geometry, which is reported even while the
 * bar is temporarily hidden (player fullscreen transitions, PiP), so a
 * transient zero inset can never strand the collapsed tab strip underneath
 * the status bar. Legitimate geometry changes such as portrait to
 * landscape rotation are still applied.
 */
fun View.applyStableTopSystemBarMargin(
    target: View,
    onStableTopChanged: ((Int) -> Unit)? = null,
) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsets ->
        val statusBarTop = windowInsets
            .getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.statusBars()
            )
            .top

        val cutoutTop = windowInsets
            .getInsets(
                WindowInsetsCompat.Type.displayCutout()
            )
            .top

        val top = maxOf(statusBarTop, cutoutTop)

        target.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            if (topMargin != top) {
                topMargin = top
            }
        }
        onStableTopChanged?.invoke(top)

        windowInsets
    }

    ViewCompat.requestApplyInsets(this)
}
