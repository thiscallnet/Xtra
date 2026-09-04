package com.github.andreyasadchy.xtra.util

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

val View.isKeyboardShown: Boolean
    get() {
        val rect = Rect()
        getWindowVisibleDisplayFrame(rect)
        val screenHeight = rootView.height

        // rect.bottom is the position above soft keypad or device button.
        // if keypad is shown, the r.bottom is smaller than that before.
        val keypadHeight = screenHeight - rect.bottom
        return keypadHeight > screenHeight * 0.15
    }

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
 * These pager screens are never immersive themselves, yet transient window
 * states (player fullscreen transitions, PiP) can dispatch a zero top inset
 * that would otherwise strand the collapsed tab strip underneath the status
 * bar with clock/icons overlapping the tabs. Keep the largest inset seen for
 * this view lifetime so a stale zero can never shrink the offset; the value
 * resets with the view. Errs toward a slightly larger gap, never overlap.
 */
fun View.applyStickyTopSystemBarMargin(target: View) {
    var maxTop = 0
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsets ->
        val top = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        ).top
        if (top > maxTop) {
            maxTop = top
            target.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = top
            }
        }
        windowInsets
    }
}
