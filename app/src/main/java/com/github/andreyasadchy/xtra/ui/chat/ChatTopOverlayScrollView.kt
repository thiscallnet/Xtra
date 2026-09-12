package com.github.andreyasadchy.xtra.ui.chat

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.core.widget.NestedScrollView
import com.github.andreyasadchy.xtra.R
import kotlin.math.roundToInt

/**
 * Keeps transient chat cards from hiding the entire message feed.
 *
 * The overlay remains wrap-content for the usual one-card case. It only becomes
 * scrollable when the combined pinned/activity stack grows beyond part of the
 * chat viewport.
 */
internal class ChatTopOverlayScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : NestedScrollView(context, attrs) {

    companion object {
        private const val MAX_VIEWPORT_FRACTION = 0.42f
        private const val COMPACT_VIEWPORT_HEIGHT_DP = 640f
    }

    init {
        isFillViewport = false
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableHeight = when (View.MeasureSpec.getMode(heightMeasureSpec)) {
            View.MeasureSpec.EXACTLY,
            View.MeasureSpec.AT_MOST,
            -> View.MeasureSpec.getSize(heightMeasureSpec)

            else -> (parent as? View)?.measuredHeight ?: 0
        }
        findViewById<HappeningNowView>(R.id.happeningNow)?.setCompactMode(
            availableHeight > 0 && availableHeight <
                COMPACT_VIEWPORT_HEIGHT_DP * resources.displayMetrics.density,
        )

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        if (availableHeight <= 0) return

        val maxOverlayHeight = (availableHeight * MAX_VIEWPORT_FRACTION).roundToInt()
        if (maxOverlayHeight > 0 && measuredHeight > maxOverlayHeight) {
            super.onMeasure(
                widthMeasureSpec,
                View.MeasureSpec.makeMeasureSpec(maxOverlayHeight, View.MeasureSpec.EXACTLY),
            )
        }
    }
}
