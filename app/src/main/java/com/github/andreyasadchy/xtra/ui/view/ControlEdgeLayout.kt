package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/**
 * Lays out the start and end control rows against one shared width budget.
 *
 * The top edge also contains the metadata view as its first child. The rows
 * receive half of the available width each, while metadata uses the space
 * that remains between them. This keeps custom layouts predictable when a
 * user enables many actions or uses a narrow player.
 */
class ControlEdgeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    private val hasMetadata: Boolean
        get() = childCount >= 3

    init {
        clipChildren = true
        clipToPadding = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val availableWidth = when (widthMode) {
            MeasureSpec.UNSPECIFIED -> Int.MAX_VALUE
            else -> (widthSize - paddingLeft - paddingRight).coerceAtLeast(1)
        }
        val availableHeight = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> Int.MAX_VALUE
            else -> (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom)
                .coerceAtLeast(1)
        }
        // Wrapped rows need the full available height. A smaller artificial
        // cap would measure only part of a custom layout, after which the
        // edge's clipping boundary would cut off its remaining controls.
        val edgeHeight = availableHeight
        val start = startRow()
        val end = endRow()
        val metadata = metadataView()
        val rowBudget = (availableWidth / 2).coerceAtLeast(1)

        measureChildAtMost(start, availableWidth, edgeHeight)
        measureChildAtMost(end, availableWidth, edgeHeight)

        // Re-measuring both rows with the same budget is what makes the
        // non-overlap guarantee hold. ControlRowLayout will wrap inside it.
        measureChildAtMost(start, rowBudget, edgeHeight)
        measureChildAtMost(end, rowBudget, edgeHeight)

        val metadataWidth = (availableWidth - start.measuredWidth - end.measuredWidth)
            .coerceAtLeast(0)
        metadata?.let { measureChildExactly(it, metadataWidth, edgeHeight) }

        val contentHeight = max(
            max(start.measuredHeight, end.measuredHeight),
            metadata?.measuredHeight ?: 0,
        )
        val desiredWidth = paddingLeft + paddingRight + start.measuredWidth +
            end.measuredWidth + (metadata?.measuredWidth ?: 0)
        val desiredHeight = paddingTop + paddingBottom + contentHeight
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val contentLeft = paddingLeft
        val contentRight = width - paddingRight
        val contentTop = paddingTop
        val start = startRow()
        val end = endRow()
        val metadata = metadataView()
        val startRight = contentLeft + start.measuredWidth
        val endLeft = contentRight - end.measuredWidth

        layoutChild(start, contentLeft, contentTop)
        layoutChild(end, endLeft, contentTop)
        metadata?.let {
            layoutChild(it, startRight, contentTop, (endLeft - startRight).coerceAtLeast(0))
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

    private fun startRow(): View = getChildAt(if (hasMetadata) 1 else 0)

    private fun endRow(): View = getChildAt(if (hasMetadata) 2 else 1)

    private fun metadataView(): View? = if (hasMetadata) getChildAt(0) else null

    private fun measureChildAtMost(child: View, width: Int, height: Int) {
        val parentWidth = (width + paddingLeft + paddingRight).coerceAtLeast(1)
        val parentHeight = (height + paddingTop + paddingBottom).coerceAtLeast(1)
        measureChildWithMargins(
            child,
            MeasureSpec.makeMeasureSpec(parentWidth, MeasureSpec.AT_MOST),
            0,
            MeasureSpec.makeMeasureSpec(parentHeight, MeasureSpec.AT_MOST),
            0,
        )
    }

    private fun measureChildExactly(child: View, width: Int, height: Int) {
        val parentWidth = (width + paddingLeft + paddingRight).coerceAtLeast(0)
        val parentHeight = (height + paddingTop + paddingBottom).coerceAtLeast(1)
        measureChildWithMargins(
            child,
            MeasureSpec.makeMeasureSpec(parentWidth, MeasureSpec.EXACTLY),
            0,
            MeasureSpec.makeMeasureSpec(parentHeight, MeasureSpec.AT_MOST),
            0,
        )
    }

    private fun layoutChild(child: View, left: Int, top: Int, forcedWidth: Int? = null) {
        val width = forcedWidth ?: child.measuredWidth
        child.layout(left, top, left + width, top + child.measuredHeight)
    }
}
