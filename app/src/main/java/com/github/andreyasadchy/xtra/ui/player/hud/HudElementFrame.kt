package com.github.andreyasadchy.xtra.ui.player.hud

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import java.util.IdentityHashMap
import kotlin.math.roundToInt

class HudElementFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private var geometry: ResolvedHudElement? = null
    private var active = false
    private var touchTarget: View? = null
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchMoved = false
    private var expandedTouchTarget: View? = null
    private val baselineMetrics = IdentityHashMap<View, PresentationMetrics>()
    private val canonicalMetrics = IdentityHashMap<View, PresentationMetrics>()

    private data class PresentationMetrics(
        val textSize: Float?,
        val maxWidth: Int?,
        val layoutWidth: Int?,
        val layoutHeight: Int?,
        val padding: IntArray,
        val margin: IntArray?,
        val iconSize: Int?,
        val iconPadding: Int?,
    )

    init {
        clipChildren = false
        clipToPadding = false
        isFocusable = false
    }

    fun setActive(value: Boolean) {
        active = value
        alpha = if (value) 1f else 0f
        importantForAccessibility = if (value) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO
        // The frame owns hit geometry, but focus belongs to the actionable child.
        // Keeping the frame unfocusable also prevents Android's default focus
        // highlight from drawing a stray shape at the frame origin.
        isFocusable = false
    }

    fun isActive(): Boolean = active

    fun setGeometry(value: ResolvedHudElement?) {
        if (geometry == value) return
        geometry = value
        if (!isInLayout) requestLayout()
    }

    fun naturalVisualSize(): HudSize = HudSize(measuredContentWidth().toFloat(), measuredContentHeight().toFloat())

    fun measureNatural(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        getChildAt(0)?.let { child ->
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
        }
    }

    fun hitRect(): HudRect? = geometry?.hitRect

    fun visualRect(): HudRect? = geometry?.visualRect

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val resolved = geometry
        if (resolved == null) {
            val child = getChildAt(0)
            if (child != null) {
                measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
                setMeasuredDimension(
                    resolveSize(child.measuredWidth, widthMeasureSpec),
                    resolveSize(child.measuredHeight, heightMeasureSpec),
                )
            } else {
                setMeasuredDimension(0, 0)
            }
            return
        }

        val visualWidth = resolved.visualRect.width.roundToInt().coerceAtLeast(0)
        val visualHeight = resolved.visualRect.height.roundToInt().coerceAtLeast(0)
        val child = getChildAt(0)
        if (child != null) {
            child.measure(
                MeasureSpec.makeMeasureSpec(visualWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(visualHeight, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(
            resolved.hitRect.width.roundToInt().coerceAtLeast(1),
            resolved.hitRect.height.roundToInt().coerceAtLeast(1),
        )
    }

    /**
     * Restores the canonical XML presentation before natural measurement.
     *
     * Geometry is resolved from canonical content sizes. Presentation scale is
     * applied only after the engine has returned its visual rectangle, so a
     * previous slider change can never feed back into the next measurement.
     */
    fun resetPresentationMetrics() {
        val child = getChildAt(0) ?: return
        captureBaseline(child)
        restoreCanonical(child)
        (child as? HudTimelineContent)?.setPresentationScale(1f)
    }

    /** Records the current unscaled, orientation-specific presentation state. */
    fun captureCanonicalPresentationMetrics() {
        val child = getChildAt(0) ?: return
        captureBaseline(child)
        captureCanonical(child)
    }

    /** Applies scale to the actual content metrics, without view transforms. */
    fun applyPresentationScale(scale: Float) {
        val child = getChildAt(0) ?: return
        captureBaseline(child)
        val effectiveScale = scale.coerceAtLeast(0.01f)
        (child as? HudTimelineContent)?.setPresentationScale(effectiveScale)
        applyScale(child, effectiveScale)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        val resolved = geometry
        val childLeft = if (resolved == null) 0 else {
            (resolved.visualRect.left - resolved.hitRect.left).roundToInt()
        }
        val childTop = if (resolved == null) 0 else {
            (resolved.visualRect.top - resolved.hitRect.top).roundToInt()
        }
        child.layout(childLeft, childTop, childLeft + child.measuredWidth, childTop + child.measuredHeight)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!active) return false
        if (isCompositeFrame()) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                expandedTouchTarget = findLocalTouchTarget(event.x, event.y)
            }
            expandedTouchTarget?.let { target ->
                val handled = dispatchToLocalTarget(target, event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    expandedTouchTarget = null
                }
                if (handled || event.actionMasked != MotionEvent.ACTION_DOWN) return true
                expandedTouchTarget = null
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!active) return false
        val child = touchTarget ?: if (isCompositeFrame()) {
            null
        } else {
            getSingleClickableChild(getChildAt(0))
        }
        if (child == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchTarget = child
                touchDownX = event.x
                touchDownY = event.y
                touchMoved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
                if (!touchMoved &&
                    (event.x - touchDownX) * (event.x - touchDownX) +
                    (event.y - touchDownY) * (event.y - touchDownY) > slop * slop
                ) {
                    touchMoved = true
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val shouldClick = !touchMoved &&
                    event.x >= 0f && event.x <= width &&
                    event.y >= 0f && event.y <= height &&
                    child.isEnabled && child.isShown
                touchTarget = null
                touchMoved = false
                if (shouldClick) child.performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                touchTarget = null
                touchMoved = false
                return true
            }
        }
        return false
    }

    private fun getSingleClickableChild(view: View?): View? {
        val clickable = mutableListOf<View>()
        collectClickableChildren(view, clickable)
        return clickable.singleOrNull()
    }

    private fun isCompositeFrame(): Boolean = when (tag as? String) {
        HudElementId.STREAM_INFO.name, HudElementId.TIMELINE.name -> true
        else -> false
    }

    private fun findLocalTouchTarget(x: Float, y: Float): View? {
        val candidates = mutableListOf<View>()
        collectClickableChildren(getChildAt(0), candidates)
        return candidates
            .asSequence()
            .filter { it.isEnabled && it.isShown }
            .mapNotNull { target ->
                val rect = Rect()
                target.getDrawingRect(rect)
                offsetDescendantRectToMyCoords(target, rect)
                val expandedWidth = maxOf(rect.width().toFloat(), 48f * resources.displayMetrics.density).roundToInt()
                // Metadata has several adjacent text targets. Expanding its
                // targets vertically would make a title tap activate the
                // channel link above it, so only expand its horizontal touch
                // affordance. Timeline actions already own a full-height band.
                val expandedHeight = if (tag == HudElementId.STREAM_INFO.name) {
                    rect.height()
                } else {
                    maxOf(rect.height().toFloat(), 48f * resources.displayMetrics.density).roundToInt()
                }
                val expanded = Rect(
                    rect.centerX() - expandedWidth / 2,
                    rect.centerY() - expandedHeight / 2,
                    rect.centerX() + (expandedWidth + 1) / 2,
                    rect.centerY() + (expandedHeight + 1) / 2,
                )
                if (expanded.contains(x.roundToInt(), y.roundToInt())) target else null
            }
            .minByOrNull { target ->
                val rect = Rect()
                target.getDrawingRect(rect)
                offsetDescendantRectToMyCoords(target, rect)
                (rect.centerX() - x).let { dx ->
                    val dy = rect.centerY() - y
                    dx * dx + dy * dy
                }
            }
    }

    private fun dispatchToLocalTarget(target: View, event: MotionEvent): Boolean {
        val location = descendantOffset(target)
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(-location.first, -location.second)
        val handled = target.dispatchTouchEvent(copy)
        copy.recycle()
        return handled
    }

    private fun descendantOffset(target: View): Pair<Float, Float> {
        var current: View = target
        var x = 0f
        var y = 0f
        while (current !== this) {
            x += current.left - current.scrollX
            y += current.top - current.scrollY
            current = current.parent as? View ?: break
        }
        return x to y
    }

    private fun collectClickableChildren(view: View?, result: MutableList<View>) {
        if (view == null || view.visibility != VISIBLE) return
        if (view.hasOnClickListeners() || view.hasOnLongClickListeners()) {
            result += view
            return
        }
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) {
                collectClickableChildren(view.getChildAt(index), result)
            }
        }
    }

    private fun measuredContentWidth(): Int = getChildAt(0)?.measuredWidth ?: 0

    private fun measuredContentHeight(): Int = getChildAt(0)?.measuredHeight ?: 0

    private fun captureBaseline(view: View) {
        if (!baselineMetrics.containsKey(view)) {
            val margins = (view.layoutParams as? MarginLayoutParams)?.let {
                intArrayOf(it.leftMargin, it.topMargin, it.rightMargin, it.bottomMargin)
            }
            baselineMetrics[view] = PresentationMetrics(
                textSize = (view as? TextView)?.textSize,
                maxWidth = (view as? TextView)?.maxWidth,
                layoutWidth = view.layoutParams?.width,
                layoutHeight = view.layoutParams?.height,
                padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom),
                margin = margins,
                iconSize = (view as? MaterialButton)?.iconSize,
                iconPadding = (view as? MaterialButton)?.iconPadding,
            )
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) captureBaseline(view.getChildAt(index))
        }
    }

    private fun restoreBaseline(view: View) {
        val metrics = canonicalMetrics[view] ?: baselineMetrics[view]
        if (metrics != null) {
            (view as? TextView)?.let { text ->
                metrics.textSize?.let { text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, it) }
                metrics.maxWidth?.let { text.maxWidth = it }
            }
            view.layoutParams?.let { params ->
                metrics.layoutWidth?.let { params.width = it }
                metrics.layoutHeight?.let { params.height = it }
                view.layoutParams = params
            }
            view.setPadding(
                metrics.padding[0],
                metrics.padding[1],
                metrics.padding[2],
                metrics.padding[3],
            )
            (view as? MaterialButton)?.let { button ->
                metrics.iconSize?.let { button.iconSize = it }
                metrics.iconPadding?.let { button.iconPadding = it }
            }
            metrics.margin?.let { margin ->
                (view.layoutParams as? MarginLayoutParams)?.let { params ->
                    params.setMargins(margin[0], margin[1], margin[2], margin[3])
                    view.layoutParams = params
                }
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) restoreBaseline(view.getChildAt(index))
        }
    }

    private fun restoreCanonical(view: View) = restoreBaseline(view)

    private fun captureCanonical(view: View) {
        canonicalMetrics[view] = metricsFor(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) captureCanonical(view.getChildAt(index))
        }
    }

    private fun metricsFor(view: View): PresentationMetrics {
        val margins = (view.layoutParams as? MarginLayoutParams)?.let {
            intArrayOf(it.leftMargin, it.topMargin, it.rightMargin, it.bottomMargin)
        }
        return PresentationMetrics(
            textSize = (view as? TextView)?.textSize,
            maxWidth = (view as? TextView)?.maxWidth,
            layoutWidth = view.layoutParams?.width,
            layoutHeight = view.layoutParams?.height,
            padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom),
            margin = margins,
            iconSize = (view as? MaterialButton)?.iconSize,
            iconPadding = (view as? MaterialButton)?.iconPadding,
        )
    }

    private fun applyScale(view: View, scale: Float) {
        val text = view as? TextView
        if (text != null) {
            text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, text.textSize * scale)
            if (text.maxWidth != Int.MAX_VALUE) {
                text.maxWidth = (text.maxWidth * scale).roundToInt().coerceAtLeast(1)
            }
        }
        view.setPadding(
            (view.paddingLeft * scale).roundToInt(),
            (view.paddingTop * scale).roundToInt(),
            (view.paddingRight * scale).roundToInt(),
            (view.paddingBottom * scale).roundToInt(),
        )
        (view as? MaterialButton)?.let { button ->
            button.iconSize = (button.iconSize * scale).roundToInt().coerceAtLeast(1)
            button.iconPadding = (button.iconPadding * scale).roundToInt().coerceAtLeast(0)
        }
        if (view.id == com.github.andreyasadchy.xtra.R.id.channelAvatar) {
            (view.layoutParams as? MarginLayoutParams)?.let { params ->
                params.width = (params.width * scale).roundToInt().coerceAtLeast(1)
                params.height = (params.height * scale).roundToInt().coerceAtLeast(1)
                view.layoutParams = params
            }
        }
        (view.layoutParams as? MarginLayoutParams)?.let { params ->
            params.setMargins(
                (params.leftMargin * scale).roundToInt(),
                (params.topMargin * scale).roundToInt(),
                (params.rightMargin * scale).roundToInt(),
                (params.bottomMargin * scale).roundToInt(),
            )
            view.layoutParams = params
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyScale(view.getChildAt(index), scale)
        }
    }
}
