package com.github.andreyasadchy.xtra.ui.player.hud

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.roundToInt

class HudTimelineContent @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {
    private val density = resources.displayMetrics.density
    private val labelWidth = (72f * density).roundToInt().coerceAtLeast(1)
    private val previewGap = (4f * density).roundToInt()
    private var liveRewindEnabled = false
    private var liveRewindPreviewFraction: Float? = null
    private var scrubPreview: TextView? = null

    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        scrubPreview = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            isSingleLine = true
            setPadding(
                (8f * density).roundToInt(),
                (5f * density).roundToInt(),
                (8f * density).roundToInt(),
                (5f * density).roundToInt(),
            )
            background = GradientDrawable().apply {
                setColor(0xE61A1820.toInt())
                cornerRadius = 6f * density
            }
            elevation = 4f * density
            visibility = GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(scrubPreview)
    }

    fun setLiveRewindEnabled(enabled: Boolean) {
        if (liveRewindEnabled == enabled) return
        liveRewindEnabled = enabled
        if (!enabled) clearLiveRewindPreview()
        findViewById<TextView>(com.github.andreyasadchy.xtra.R.id.position)?.let { position ->
            if (enabled) {
                position.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                position.setPadding((8f * density).roundToInt(), 0, 0, 0)
            } else {
                position.gravity = Gravity.CENTER
                position.setPadding(0, 0, 0, 0)
            }
        }
        requestLayout()
    }

    fun setLiveRewindPreview(text: CharSequence, fraction: Float) {
        val preview = scrubPreview ?: return
        val nextFraction = fraction.coerceIn(0f, 1f)
        if (preview.text != text) preview.text = text
        val changed = liveRewindPreviewFraction != nextFraction || preview.visibility != VISIBLE
        liveRewindPreviewFraction = nextFraction
        preview.visibility = VISIBLE
        if (changed) requestLayout() else invalidate()
    }

    fun clearLiveRewindPreview() {
        liveRewindPreviewFraction = null
        scrubPreview?.let { preview ->
            if (preview.visibility != GONE) {
                preview.visibility = GONE
                requestLayout()
            }
        }
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
        val preview = scrubPreview

        if (liveRewindEnabled) {
            // Live rewind keeps one stable track geometry. The behind-live
            // label and scrub bubble are overlays, so showing them cannot
            // move the bar or its live affordance.
            position?.measure(
                MeasureSpec.makeMeasureSpec(labelWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
            duration?.measure(
                MeasureSpec.makeMeasureSpec(labelWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
            bottom?.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
        } else {
            val showLabels =
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
        }
        preview?.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST),
        )
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child !== position && child !== duration && child !== bottom && child !== preview) {
                measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            }
        }
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val position = findViewById<View>(com.github.andreyasadchy.xtra.R.id.position)
        val duration = findViewById<View>(com.github.andreyasadchy.xtra.R.id.duration)
        val timeline = findViewById<View>(com.github.andreyasadchy.xtra.R.id.bottomLayout)
        val preview = scrubPreview
        if (liveRewindEnabled) {
            timeline?.layout(0, 0, width, timeline.measuredHeight)
            position?.layout(0, 0, position.measuredWidth, position.measuredHeight)
            duration?.layout(width - duration.measuredWidth, 0, width, duration.measuredHeight)
            preview?.let { bubble ->
                val fraction = liveRewindPreviewFraction ?: 0.5f
                val bubbleLeft = (width * fraction - bubble.measuredWidth / 2f)
                    .roundToInt()
                    .coerceIn(0, (width - bubble.measuredWidth).coerceAtLeast(0))
                val bubbleTop = -bubble.measuredHeight - previewGap
                bubble.layout(
                    bubbleLeft,
                    bubbleTop,
                    bubbleLeft + bubble.measuredWidth,
                    bubbleTop + bubble.measuredHeight,
                )
            }
        } else {
            position?.layout(0, 0, position.measuredWidth, position.measuredHeight)
            duration?.layout(width - duration.measuredWidth, 0, width, duration.measuredHeight)
            timeline?.layout(
                position?.measuredWidth ?: 0,
                0,
                width - (duration?.measuredWidth ?: 0),
                timeline.measuredHeight,
            )
        }
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child !== position && child !== duration && child !== timeline && child !== preview) {
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
