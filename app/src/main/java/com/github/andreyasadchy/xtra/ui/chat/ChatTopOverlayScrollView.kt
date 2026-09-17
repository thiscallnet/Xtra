package com.github.andreyasadchy.xtra.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.NestedScrollView
import com.google.android.material.color.MaterialColors
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
        private const val SCROLLBAR_WIDTH_DP = 3
        private const val SCROLLBAR_MIN_THUMB_DP = 28
        private const val SCROLLBAR_VERTICAL_INSET_DP = 6
        private const val SCROLLBAR_EDGE_MARGIN_DP = 2
    }

    private val scrollbarTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrollbarThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var scrollInteractionGeneration = 0L

    init {
        isFillViewport = false
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)

        val content = getChildAt(0) ?: return
        val viewportHeight = height - paddingTop - paddingBottom
        val scrollRange = (content.height - viewportHeight).coerceAtLeast(0)
        if (viewportHeight <= 0 || scrollRange <= 0) return

        val onSurfaceVariant = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
        )
        val primary = MaterialColors.getColor(
            this,
            androidx.appcompat.R.attr.colorPrimary,
        )
        scrollbarTrackPaint.color = ColorUtils.withAlpha(onSurfaceVariant, 0.30f)
        scrollbarThumbPaint.color = ColorUtils.withAlpha(primary, 0.82f)

        val verticalInset = dp(SCROLLBAR_VERTICAL_INSET_DP).toFloat()
        val edgeMargin = dp(SCROLLBAR_EDGE_MARGIN_DP).toFloat()
        val trackWidth = dp(SCROLLBAR_WIDTH_DP).toFloat()
        val trackLeft = width - edgeMargin - trackWidth
        val trackTop = paddingTop + verticalInset
        val trackBottom = height - paddingBottom - verticalInset
        val trackHeight = (trackBottom - trackTop).coerceAtLeast(0f)
        if (trackHeight <= 0f) return

        val thumbHeight = (
            trackHeight * viewportHeight.toFloat() / content.height.toFloat()
        ).roundToInt()
            .coerceAtLeast(dp(SCROLLBAR_MIN_THUMB_DP))
            .coerceAtMost(trackHeight.roundToInt())
            .toFloat()
        val thumbTravel = trackHeight - thumbHeight
        val boundedScrollY = scrollY.coerceIn(0, scrollRange)
        val thumbTop = trackTop + if (scrollRange > 0) {
            thumbTravel * boundedScrollY.toFloat() / scrollRange.toFloat()
        } else {
            0f
        }

        // View.draw() translates the canvas by -scrollY before dispatching
        // children. The indicator belongs to the viewport, not the scrolling
        // content, so put the canvas back in viewport coordinates before
        // painting it.
        val saveCount = canvas.save()
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())
        canvas.drawRoundRect(
            trackLeft,
            trackTop,
            trackLeft + trackWidth,
            trackBottom,
            trackWidth,
            trackWidth,
            scrollbarTrackPaint,
        )
        canvas.drawRoundRect(
            trackLeft,
            thumbTop,
            trackLeft + trackWidth,
            thumbTop + thumbHeight,
            trackWidth,
            trackWidth,
            scrollbarThumbPaint,
        )
        canvas.restoreToCount(saveCount)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (t != oldt) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> scrollInteractionGeneration++
        }
        return super.onTouchEvent(event)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private object ColorUtils {
        fun withAlpha(color: Int, fraction: Float): Int =
            Color.argb(
                (Color.alpha(color) * fraction).roundToInt().coerceIn(0, 255),
                Color.red(color),
                Color.green(color),
                Color.blue(color),
            )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableHeight = when (View.MeasureSpec.getMode(heightMeasureSpec)) {
            View.MeasureSpec.EXACTLY,
            View.MeasureSpec.AT_MOST,
            -> View.MeasureSpec.getSize(heightMeasureSpec)

            else -> (parent as? View)?.measuredHeight ?: 0
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        if (availableHeight <= 0) return

        val softLimit = (availableHeight * MAX_VIEWPORT_FRACTION).roundToInt()
        if (softLimit <= 0 || measuredHeight <= softLimit) return

        // Keep the resting edge on a complete overlay unit. The cap is a soft
        // limit: a unit that barely crosses it is still better than a clipped
        // button or card header.
        val targetHeight = safeOverlayHeight(softLimit, availableHeight)
        if (targetHeight > 0 && measuredHeight > targetHeight) {
            super.onMeasure(
                widthMeasureSpec,
                View.MeasureSpec.makeMeasureSpec(targetHeight, View.MeasureSpec.EXACTLY),
            )
        }
    }

    private fun safeOverlayHeight(softLimit: Int, availableHeight: Int): Int {
        val content = getChildAt(0) as? ViewGroup ?: return 0
        val rawBreakpoints = mutableListOf<Int>()
        var contentTop = content.paddingTop

        for (index in 0 until content.childCount) {
            val child = content.getChildAt(index)
            if (child.visibility != View.VISIBLE) continue

            val margins = child.layoutParams as? ViewGroup.MarginLayoutParams
            contentTop += margins?.topMargin ?: 0
            val childBottom = contentTop + child.measuredHeight

            if (child is HappeningNowView) {
                child.appendOverlayBreakpoints(contentTop, rawBreakpoints)
            } else {
                rawBreakpoints += childBottom + (margins?.bottomMargin ?: 0)
            }

            contentTop = childBottom + (margins?.bottomMargin ?: 0)
        }

        val outerBottomPadding = content.paddingBottom + paddingBottom
        val breakpoints = rawBreakpoints
            .map { it + outerBottomPadding }
            .filter { it > 0 }
            .sorted()
        val restingHeight = breakpoints
            .filter { it <= softLimit }
            .maxOrNull()
            ?: breakpoints.firstOrNull()
            ?: softLimit
        return restingHeight.coerceAtMost(availableHeight)
    }

    /**
     * Reveal an activity card after the user expands Happening Now. The stack
     * is intentionally capped, so simply expanding the card can otherwise
     * leave its newly visible content below the fold.
     */
    internal fun revealDescendant(descendant: View) {
        post {
            if (height <= 0 || descendant.height <= 0) return@post

            var descendantTop = descendant.top
            var parent = descendant.parent
            while (parent is View && parent !== this) {
                descendantTop += parent.top
                parent = parent.parent
            }
            if (parent !== this) return@post

            val descendantBottom = descendantTop + descendant.height
            val visibleTop = scrollY + paddingTop
            val visibleBottom = scrollY + height - paddingBottom
            val targetScrollY = when {
                descendantBottom > visibleBottom ->
                    descendantBottom - height + paddingBottom

                descendantTop < visibleTop -> descendantTop - paddingTop
                else -> return@post
            }
            val content = getChildAt(0) ?: return@post
            val scrollRange = (content.height - height + paddingBottom).coerceAtLeast(0)
            scrollTo(scrollX, targetScrollY.coerceIn(0, scrollRange))
        }
    }

    /** Keep a user's place when a live activity updates and its card is rebuilt. */
    internal fun restoreScrollPosition(scrollY: Int) {
        if (scrollY <= 0) return
        val generation = scrollInteractionGeneration
        post {
            if (generation != scrollInteractionGeneration) return@post
            val content = getChildAt(0) ?: return@post
            val scrollRange = (content.height - height + paddingBottom).coerceAtLeast(0)
            scrollTo(scrollX, scrollY.coerceIn(0, scrollRange))
        }
    }
}
