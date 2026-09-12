package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

internal object ControlRowPlanner {

    /** Returns child indexes grouped into horizontal lines for a bounded row. */
    fun lineBreak(availableWidth: Int, itemWidths: List<Int>, spacing: Int = 0): List<List<Int>> {
        if (itemWidths.isEmpty()) return emptyList()
        val lines = mutableListOf<MutableList<Int>>()
        var line = mutableListOf<Int>()
        var lineWidth = 0
        itemWidths.forEachIndexed { index, itemWidth ->
            val requiredWidth = if (line.isEmpty()) itemWidth else lineWidth + spacing + itemWidth
            if (line.isNotEmpty() && requiredWidth > availableWidth) {
                lines += line
                line = mutableListOf()
                lineWidth = 0
            }
            line += index
            lineWidth += if (line.size == 1) itemWidth else spacing + itemWidth
        }
        lines += line
        return lines
    }
}

/**
 * A compact control row that wraps instead of letting buttons run underneath
 * another overlay group. It keeps the existing ViewGroup contract used by the
 * player control layout editor while making a crowded custom layout safe.
 */
class ControlRowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private data class Line(
        val children: MutableList<View> = mutableListOf(),
        var width: Int = 0,
        var height: Int = 0,
    )

    init {
        orientation = HORIZONTAL
        clipChildren = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> Int.MAX_VALUE
            else -> (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(1)
        }
        val lines = measureLines(widthMeasureSpec, heightMeasureSpec, availableWidth)
        val desiredWidth = (lines.maxOfOrNull { it.width } ?: 0) + paddingLeft + paddingRight
        val desiredHeight = lines.sumOf { it.height } + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val lines = layoutLines(availableWidth)
        var lineTop = paddingTop
        val horizontalGravity = gravity and Gravity.HORIZONTAL_GRAVITY_MASK
        lines.forEach { line ->
            val lineLeft = when (horizontalGravity) {
                Gravity.RIGHT, Gravity.END -> width - paddingRight - line.width
                Gravity.CENTER_HORIZONTAL -> paddingLeft + (availableWidth - line.width) / 2
                else -> paddingLeft
            }
            var childLeft = lineLeft
            line.children.forEach { child ->
                val params = child.layoutParams as MarginLayoutParams
                childLeft += params.leftMargin
                val childTop = lineTop + params.topMargin +
                    ((line.height - params.topMargin - params.bottomMargin - child.measuredHeight) / 2)
                child.layout(
                    childLeft,
                    childTop,
                    childLeft + child.measuredWidth,
                    childTop + child.measuredHeight,
                )
                childLeft += child.measuredWidth + params.rightMargin
            }
            lineTop += line.height
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams =
        LayoutParams(context, attrs)

    override fun generateLayoutParams(params: ViewGroup.LayoutParams): LayoutParams =
        LayoutParams(params)

    override fun checkLayoutParams(params: ViewGroup.LayoutParams): Boolean =
        params is LayoutParams

    private fun measureLines(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
        availableWidth: Int,
    ): List<Line> {
        val children = visibleChildren()
        children.forEach { child ->
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
        }
        return buildLines(availableWidth, children)
    }

    private fun layoutLines(availableWidth: Int): List<Line> = buildLines(availableWidth, visibleChildren())

    private fun buildLines(availableWidth: Int, children: List<View>): List<Line> {
        val widths = children.map { child ->
            val params = child.layoutParams as MarginLayoutParams
            params.leftMargin + child.measuredWidth + params.rightMargin
        }
        return ControlRowPlanner.lineBreak(availableWidth, widths).map { indexes ->
            Line(
                children = indexes.mapTo(mutableListOf(), children::get),
                width = indexes.sumOf { widths[it] },
                height = indexes.maxOf { index ->
                    val child = children[index]
                    val params = child.layoutParams as MarginLayoutParams
                    params.topMargin + child.measuredHeight + params.bottomMargin
                },
            )
        }
    }

    private fun visibleChildren(): List<View> = (0 until childCount)
        .map(::getChildAt)
        .filter { it.visibility == View.VISIBLE }
}
