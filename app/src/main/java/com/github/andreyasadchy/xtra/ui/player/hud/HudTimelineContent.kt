package com.github.andreyasadchy.xtra.ui.player.hud

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

class HudTimelineContent @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {
    private var liveRewindEnabled = false

    init {
        clipChildren = false
        clipToPadding = false
    }

    fun setLiveRewindEnabled(enabled: Boolean) {
        if (liveRewindEnabled == enabled) return
        liveRewindEnabled = enabled
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            (48f * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        }
        val position = findViewById<View>(com.github.andreyasadchy.xtra.R.id.position)
        val duration = findViewById<View>(com.github.andreyasadchy.xtra.R.id.duration)
        val bottom = findViewById<View>(com.github.andreyasadchy.xtra.R.id.bottomLayout)
        val labelWidth = (72f * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        // Live rewind reserves label columns even before the first progress
        // update makes the labels visible. VOD visibility remains owned by
        // the playback fragment.
        val showLabels = liveRewindEnabled ||
            position?.visibility == View.VISIBLE ||
            duration?.visibility == View.VISIBLE
        val labelSlotWidth = if (showLabels) labelWidth else 0
        position?.measure(
            MeasureSpec.makeMeasureSpec(labelSlotWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        duration?.measure(
            MeasureSpec.makeMeasureSpec(labelSlotWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        bottom?.measure(
            MeasureSpec.makeMeasureSpec((width - 2 * labelSlotWidth).coerceAtLeast(1), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child !== position && child !== duration && child !== bottom) {
                measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            }
        }
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val position = findViewById<View>(com.github.andreyasadchy.xtra.R.id.position)
        val duration = findViewById<View>(com.github.andreyasadchy.xtra.R.id.duration)
        val timeline = findViewById<View>(com.github.andreyasadchy.xtra.R.id.bottomLayout)
        position?.layout(0, 0, position.measuredWidth, position.measuredHeight)
        duration?.layout(width - duration.measuredWidth, 0, width, duration.measuredHeight)
        timeline?.layout(
            position?.measuredWidth ?: 0,
            0,
            width - (duration?.measuredWidth ?: 0),
            timeline.measuredHeight,
        )
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child !== position && child !== duration && child !== timeline) {
                child.layout(0, 0, child.measuredWidth, child.measuredHeight)
            }
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun generateLayoutParams(params: ViewGroup.LayoutParams): LayoutParams =
        MarginLayoutParams(params)

    override fun checkLayoutParams(params: ViewGroup.LayoutParams): Boolean =
        params is MarginLayoutParams
}
