package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.isTelevision
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Places the movable player-control rows around the fixed player chrome.
 *
 * The rows remain separate so PlayerControlLayout can preserve their order,
 * while this parent gives every perimeter position the same bounded measure
 * and collision rules.
 */
class PlayerControlOverlayLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    private val inset = if (context.isTelevision()) {
        resources.getDimensionPixelSize(R.dimen.tv_safe_horizontal)
    } else {
        dp(10)
    }
    private val gap = dp(8)
    private var prepared = false
    private var transportRoot: View? = null
    private var timelineViews: List<View> = emptyList()
    private var relayoutPosted = false

    private val rowIds = listOf(
        R.id.topStartLayout,
        R.id.topCenterLayout,
        R.id.topRightLayout,
        R.id.middleLeftLayout,
        R.id.middleRightLayout,
        R.id.bottomLeftLayout,
        R.id.bottomCenterLayout,
        R.id.bottomRightLayout,
    )

    /** Moves the existing XML rows under this parent without changing binding IDs. */
    fun prepare(root: ViewGroup) {
        if (prepared) return
        val rows = rowIds.mapNotNull { root.findViewById<View>(it) }
        val metadata = root.findViewById<View>(R.id.topLeftLayout)
        rows.forEach { row ->
            (row.parent as? ViewGroup)?.removeView(row)
            addView(row)
        }
        metadata?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            addView(it, 0)
        }
        root.findViewById<View>(R.id.topControlLayout)?.visibility = View.GONE
        root.findViewById<View>(R.id.bottomControlLayout)?.visibility = View.GONE
        prepared = true
        requestLayout()
    }

    fun setObstacles(transport: View, timeline: List<View>) {
        transportRoot = transport
        timelineViews = timeline
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val measuredHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (!prepared) {
            setMeasuredDimension(
                resolveSize(measuredWidth, widthMeasureSpec),
                resolveSize(measuredHeight, heightMeasureSpec),
            )
            return
        }

        val availableWidth = (measuredWidth - inset * 2).coerceAtLeast(1)
        val transport = transportBounds() ?: estimatedTransportBounds(measuredWidth, measuredHeight)
        val metadata = findChild(R.id.topLeftLayout)
        val metadataVisible = metadata?.let(::hasVisibleDescendant) == true
        val topStart = findChild(R.id.topStartLayout)
        val topEnd = findChild(R.id.topRightLayout)
        val topCenter = findChild(R.id.topCenterLayout)
        val centerMinimum = dp(48)
        val sideWidth = ((availableWidth - centerMinimum - gap * 3) / 2)
            .coerceAtLeast(dp(48))
        val topHeight = (transport.top - inset - gap).coerceAtLeast(dp(48))
        measureControlRow(topStart, sideWidth)
        measureControlRow(topEnd, sideWidth)
        val centerAvailableWidth = (availableWidth -
            max(topStart?.measuredWidth ?: 0, 0) - max(topEnd?.measuredWidth ?: 0, 0) - gap * 2)
            .coerceAtLeast(centerMinimum)
        measureControlRow(
            topCenter,
            centerAvailableWidth,
        )
        val metadataWidth = if (metadataVisible) {
            (availableWidth - max(topStart?.measuredWidth ?: 0, 0) -
                max(topEnd?.measuredWidth ?: 0, 0) - max(topCenter?.measuredWidth ?: 0, 0) - gap * 3)
                .coerceAtLeast(0)
        } else {
            0
        }
        metadata?.let { measureExactly(it, metadataWidth, topHeight) }

        val bottomStart = findChild(R.id.bottomLeftLayout)
        val bottomEnd = findChild(R.id.bottomRightLayout)
        val bottomCenter = findChild(R.id.bottomCenterLayout)
        measureControlRow(bottomStart, sideWidth)
        measureControlRow(bottomEnd, sideWidth)
        val bottomCenterWidth = (availableWidth - max(bottomStart?.measuredWidth ?: 0, 0) -
            max(bottomEnd?.measuredWidth ?: 0, 0) - gap * 2).coerceAtLeast(centerMinimum)
        measureControlRow(
            bottomCenter,
            bottomCenterWidth,
        )

        val middleStart = findChild(R.id.middleLeftLayout)
        val middleEnd = findChild(R.id.middleRightLayout)
        val middleStartWidth = (transport.left - inset - gap).coerceAtLeast(dp(48))
        val middleEndWidth = (measuredWidth - transport.right - inset - gap).coerceAtLeast(dp(48))
        measureControlRow(middleStart, middleStartWidth)
        measureControlRow(middleEnd, middleEndWidth)

        setMeasuredDimension(
            resolveSize(measuredWidth, widthMeasureSpec),
            resolveSize(measuredHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!prepared) return
        val metadata = findChild(R.id.topLeftLayout)
        val topStart = findChild(R.id.topStartLayout)
        val topCenter = findChild(R.id.topCenterLayout)
        val topEnd = findChild(R.id.topRightLayout)
        val middleStart = findChild(R.id.middleLeftLayout)
        val middleEnd = findChild(R.id.middleRightLayout)
        val bottomStart = findChild(R.id.bottomLeftLayout)
        val bottomCenter = findChild(R.id.bottomCenterLayout)
        val bottomEnd = findChild(R.id.bottomRightLayout)

        val startRight = inset + (topStart?.measuredWidth ?: 0)
        layoutChild(topStart, inset, inset)
        layoutChild(topEnd, width - inset - (topEnd?.measuredWidth ?: 0), inset)
        val metadataLeft = startRight + if (topStart?.measuredWidth ?: 0 > 0) gap else 0
        layoutChild(
            metadata,
            metadataLeft
                .coerceAtMost((width - inset - (metadata?.measuredWidth ?: 0)).coerceAtLeast(inset)),
            inset,
        )
        val metadataRight = metadata?.let { metadataLeft + it.measuredWidth } ?: startRight
        val topCenterLeft = max(startRight, metadataRight) + gap
        val topCenterRight = width - inset - (topEnd?.measuredWidth ?: 0) - gap
        val topCenterRoom = topCenterRight - topCenterLeft
        val topCenterTop = inset
        val topCenterLeftForLayout = if (topCenterRoom >= (topCenter?.measuredWidth ?: 0)) {
            topCenterLeft + (topCenterRoom - (topCenter?.measuredWidth ?: 0)) / 2
        } else {
            (width - (topCenter?.measuredWidth ?: 0)) / 2
        }
        layoutChild(topCenter, topCenterLeftForLayout, topCenterTop)

        val transport = transportBounds() ?: estimatedTransportBounds(width, height)
        val timeline = timelineBounds(height)
        val topRowsBottom = max(
            max(topStart?.bottom ?: 0, topEnd?.bottom ?: 0),
            max(topCenter?.bottom ?: 0, metadata?.bottom ?: 0),
        )
        val bottomRowsHeight = max(
            max(bottomStart?.measuredHeight ?: 0, bottomEnd?.measuredHeight ?: 0),
            bottomCenter?.measuredHeight ?: 0,
        )
        val bottomRowsTop = (timeline?.top ?: (height - inset)) - gap - bottomRowsHeight
        val middleTop = max(inset, topRowsBottom + gap)
        val middleBottom = minOf(
            bottomRowsTop - gap,
            height - inset,
        )
        layoutChild(
            middleStart,
            inset,
            ((height - (middleStart?.measuredHeight ?: 0)) / 2)
                .coerceIn(
                    middleTop,
                    (middleBottom - (middleStart?.measuredHeight ?: 0)).coerceAtLeast(middleTop),
                ),
        )
        layoutChild(
            middleEnd,
            width - inset - (middleEnd?.measuredWidth ?: 0),
            ((height - (middleEnd?.measuredHeight ?: 0)) / 2)
                .coerceIn(
                    middleTop,
                    (middleBottom - (middleEnd?.measuredHeight ?: 0)).coerceAtLeast(middleTop),
                ),
        )

        val bottomY = timeline?.top ?: (height - inset)
        layoutChild(bottomStart, inset, bottomY - gap - (bottomStart?.measuredHeight ?: 0))
        layoutChild(bottomEnd, width - inset - (bottomEnd?.measuredWidth ?: 0), bottomY - gap - (bottomEnd?.measuredHeight ?: 0))
        val bottomCenterLeft = inset + (bottomStart?.measuredWidth ?: 0) + gap
        val bottomCenterRight = width - inset - (bottomEnd?.measuredWidth ?: 0) - gap
        val bottomCenterRoom = bottomCenterRight - bottomCenterLeft
        val bottomCenterX = if (bottomCenterRoom >= (bottomCenter?.measuredWidth ?: 0)) {
            bottomCenterLeft + (bottomCenterRoom - (bottomCenter?.measuredWidth ?: 0)) / 2
        } else {
            (width - (bottomCenter?.measuredWidth ?: 0)) / 2
        }
        val bottomCenterTop = bottomY - gap - (bottomCenter?.measuredHeight ?: 0)
        layoutChild(bottomCenter, bottomCenterX, bottomCenterTop)

        // Fixed obstacles are laid out by the surrounding RelativeLayout. A
        // second pass picks up their final bounds after rotations and resizing.
        if (changed && !relayoutPosted) {
            relayoutPosted = true
            post {
                relayoutPosted = false
                if (isAttachedToWindow) requestLayout()
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

    private fun findChild(id: Int): View? = (0 until childCount)
        .asSequence()
        .map(::getChildAt)
        .firstOrNull { it.id == id }

    private fun measureControlRow(child: View?, maxWidth: Int) {
        child ?: return
        child.measure(
            MeasureSpec.makeMeasureSpec(maxWidth.coerceAtLeast(1), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
    }

    private fun measureExactly(child: View, width: Int, maxHeight: Int) {
        child.measure(
            MeasureSpec.makeMeasureSpec(width.coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(maxHeight.coerceAtLeast(1), MeasureSpec.AT_MOST),
        )
    }

    private fun layoutChild(child: View?, left: Int, top: Int) {
        child ?: return
        val boundedLeft = left.coerceIn(0, (width - child.measuredWidth).coerceAtLeast(0))
        val boundedTop = top.coerceIn(0, (height - child.measuredHeight).coerceAtLeast(0))
        child.layout(
            boundedLeft,
            boundedTop,
            boundedLeft + child.measuredWidth,
            boundedTop + child.measuredHeight,
        )
    }

    private fun transportBounds(): Rect? {
        val root = transportRoot ?: return null
        val views = listOf(R.id.rewind, R.id.playPause, R.id.fastForward).mapNotNull(root::findViewById)
            .filter { it.isShown && it.width > 0 && it.height > 0 }
        if (views.isEmpty()) return null
        val overlayLocation = IntArray(2)
        getLocationOnScreen(overlayLocation)
        return views.map { view ->
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            Rect(
                location[0] - overlayLocation[0],
                location[1] - overlayLocation[1],
                location[0] - overlayLocation[0] + view.width,
                location[1] - overlayLocation[1] + view.height,
            )
        }.reduce { result, next ->
            result.apply { union(next) }
        }
    }

    private fun estimatedTransportBounds(width: Int, height: Int): Rect {
        val transportWidth = (width * 0.38f).roundToInt().coerceAtLeast(dp(180))
        val transportHeight = dp(72)
        return Rect(
            (width - transportWidth) / 2,
            (height - transportHeight) / 2,
            (width + transportWidth) / 2,
            (height + transportHeight) / 2,
        )
    }

    private fun timelineBounds(fallbackHeight: Int): Rect? {
        val overlayLocation = IntArray(2)
        getLocationOnScreen(overlayLocation)
        val rects = timelineViews
            .filter { it.isShown && it.width > 0 && it.height > 0 }
            .map { view ->
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                Rect(
                    location[0] - overlayLocation[0],
                    location[1] - overlayLocation[1],
                    location[0] - overlayLocation[0] + view.width,
                    location[1] - overlayLocation[1] + view.height,
                )
            }
        if (rects.isEmpty()) return null
        return rects.reduce { result, next -> result.apply { union(next) } }
            .apply {
                top = top.coerceIn(inset, fallbackHeight - inset)
                bottom = bottom.coerceIn(top, fallbackHeight - inset)
            }
    }

    private fun hasVisibleDescendant(view: View): Boolean {
        if (view.visibility != View.VISIBLE) return false
        if (view !is ViewGroup) return true
        return (0 until view.childCount).any { hasVisibleDescendant(view.getChildAt(it)) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
