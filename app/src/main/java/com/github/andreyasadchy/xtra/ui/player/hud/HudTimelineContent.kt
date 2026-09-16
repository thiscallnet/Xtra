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
    // Keep the regular VOD labels in their existing slots. Live rewind uses one
    // stable combined group so changing between LIVE and replay cannot move the
    // scrub track or make either time label collide with it.
    private val labelWidth = (72f * density).roundToInt().coerceAtLeast(1)
    private val liveTimeGroupWidth = (160f * density).roundToInt().coerceAtLeast(1)
    private val compactLiveTimeGroupWidth = (112f * density).roundToInt().coerceAtLeast(1)
    private val liveTimeGroupGap = (8f * density).roundToInt()
    private val compactLiveTimeGroupGap = (6f * density).roundToInt()
    private val compactTimelineInset = (8f * density).roundToInt()
    private val previewGap = (4f * density).roundToInt()
    // The whole 48dp timeline is the touch target. Media3 places the visible
    // bar at the bottom of that view; keeping the view full-height prevents
    // the old translated 18dp lane from lifting the purple line off the edge.
    private val compactLeadingActionReservation = (52f * density).roundToInt()
    private var presentationScale = 1f
    private var liveRewindEnabled = false
    private var liveRewindTimePosition = HudTimelineTimePosition.LEFT
    private var liveRewindPreviewFraction: Float? = null
    private var leadingActionPresent = false
    private var scrubPreview: TextView? = null

    init {
        clipChildren = false
        clipToPadding = false
    }

    /**
     * The parent frame scales ordinary Android presentation properties
     * recursively. This view also owns custom layout math, so those metrics
     * need the same scale or the visible track and time group drift apart at
     * the smaller timeline sizes.
     */
    fun setPresentationScale(value: Float) {
        presentationScale = value.takeIf { it.isFinite() }?.coerceAtLeast(0.01f) ?: 1f
    }

    private fun scaled(value: Int): Int = (value * presentationScale).roundToInt().coerceAtLeast(0)

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

    fun setLiveRewindTimePosition(value: HudTimelineTimePosition) {
        if (liveRewindTimePosition == value) return
        liveRewindTimePosition = value
        if (liveRewindEnabled) requestLayout()
    }

    fun setLiveRewindEnabled(enabled: Boolean) {
        if (liveRewindEnabled == enabled) return
        liveRewindEnabled = enabled
        if (!enabled) clearLiveRewindPreview()
        findViewById<TextView>(com.github.andreyasadchy.xtra.R.id.position)?.let { position ->
            position.gravity = Gravity.CENTER
            position.setPadding(0, 0, 0, 0)
        }
        findViewById<TextView>(com.github.andreyasadchy.xtra.R.id.duration)?.let { duration ->
            duration.gravity = Gravity.CENTER
            duration.setPadding(0, 0, 0, 0)
        }
        requestLayout()
    }

    /** Leaves room for the compact chat action before the live time label. */
    fun setLeadingActionPresent(present: Boolean) {
        if (leadingActionPresent == present) return
        leadingActionPresent = present
        if (liveRewindEnabled) requestLayout()
    }

    private data class LiveLayoutMetrics(
        val groupWidth: Int,
        val groupGap: Int,
        val leftInset: Int,
        val rightInset: Int,
    )

    private fun liveLayoutMetrics(width: Int): LiveLayoutMetrics {
        val compact = width / presentationScale < 600f * density
        val requestedLeftInset = if (compact) {
            scaled(compactTimelineInset + if (leadingActionPresent) {
                compactLeadingActionReservation
            } else {
                0
            })
        } else {
            0
        }
        val requestedRightInset = if (compact) scaled(compactTimelineInset) else 0
        val available = (width - requestedLeftInset - requestedRightInset).coerceAtLeast(0)
        val requestedGroup = scaled(if (compact) compactLiveTimeGroupWidth else liveTimeGroupWidth)
        val groupWidth = requestedGroup.coerceAtMost(available)
        val requestedGap = scaled(if (compact) compactLiveTimeGroupGap else liveTimeGroupGap)
        val groupGap = requestedGap.coerceAtMost((available - groupWidth).coerceAtLeast(0))
        return LiveLayoutMetrics(
            groupWidth = groupWidth,
            groupGap = groupGap,
            leftInset = requestedLeftInset.coerceAtMost(width),
            rightInset = requestedRightInset.coerceAtMost(width),
        )
    }

    private fun configureLiveTimeGroup(view: View?, compact: Boolean) {
        (view as? TextView)?.apply {
            gravity = Gravity.BOTTOM or Gravity.START
            val edgePadding = scaled((4f * density).roundToInt())
            setPadding(
                edgePadding,
                0,
                0,
                scaled(((if (compact) 10f else 8f) * density).roundToInt()),
            )
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }
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
        val liveTimeGroup = findViewById<View>(com.github.andreyasadchy.xtra.R.id.liveTimeGroup)
        val duration = findViewById<View>(com.github.andreyasadchy.xtra.R.id.duration)
        val bottom = findViewById<View>(com.github.andreyasadchy.xtra.R.id.bottomLayout)
        val preview = scrubPreview
        // Labels and the time bar share the complete touch-height view. The
        // time bar itself owns the bottom drawable position through Media3's
        // bar_gravity attribute.
        val labelHeight = height

        if (liveRewindEnabled) {
            val metrics = liveLayoutMetrics(width)
            configureLiveTimeGroup(
                liveTimeGroup,
                compact = width / presentationScale < 600f * density,
            )
            liveTimeGroup?.measure(
                MeasureSpec.makeMeasureSpec(metrics.groupWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(labelHeight, MeasureSpec.EXACTLY),
            )
            position?.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
            )
            duration?.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
            )
            bottom?.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
        } else {
            val showLabels =
                position?.visibility == View.VISIBLE ||
                    duration?.visibility == View.VISIBLE
            val labelSlotWidth = if (showLabels) scaled(labelWidth) else 0
            position?.measure(
                MeasureSpec.makeMeasureSpec(labelSlotWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(labelHeight, MeasureSpec.EXACTLY),
            )
            liveTimeGroup?.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
            )
            duration?.measure(
                MeasureSpec.makeMeasureSpec(labelSlotWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(labelHeight, MeasureSpec.EXACTLY),
            )
            bottom?.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
        }
        if (preview?.visibility == VISIBLE) {
            preview.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST),
            )
        } else {
            preview?.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
            )
        }
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child !== position && child !== liveTimeGroup && child !== duration && child !== bottom && child !== preview) {
                measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            }
        }
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val position = findViewById<View>(com.github.andreyasadchy.xtra.R.id.position)
        val liveTimeGroup = findViewById<View>(com.github.andreyasadchy.xtra.R.id.liveTimeGroup)
        val duration = findViewById<View>(com.github.andreyasadchy.xtra.R.id.duration)
        val timeline = findViewById<View>(com.github.andreyasadchy.xtra.R.id.bottomLayout)
        val preview = scrubPreview
        if (liveRewindEnabled) {
            val metrics = liveLayoutMetrics(width)
            val groupWidth = liveTimeGroup?.measuredWidth ?: metrics.groupWidth
            val groupLeft: Int
            if (liveRewindTimePosition == HudTimelineTimePosition.RIGHT) {
                groupLeft = width - metrics.rightInset - groupWidth
            } else {
                groupLeft = metrics.leftInset
            }
            timeline?.layout(0, 0, width, height)
            liveTimeGroup?.layout(groupLeft, 0, groupLeft + groupWidth, height)
            position?.layout(0, 0, 0, 0)
            duration?.layout(0, 0, 0, 0)
            preview?.let { bubble ->
                val fraction = liveRewindPreviewFraction ?: 0.5f
                val bubbleCenter = width * fraction
                val bubbleLeft = (bubbleCenter - bubble.measuredWidth / 2f)
                    .roundToInt()
                    .coerceIn(0, (width - bubble.measuredWidth).coerceAtLeast(0))
                val bubbleTop = height - bubble.measuredHeight - scaled(previewGap)
                bubble.layout(
                    bubbleLeft,
                    bubbleTop,
                    bubbleLeft + bubble.measuredWidth,
                    bubbleTop + bubble.measuredHeight,
                )
            }
        } else {
            position?.layout(0, 0, position.measuredWidth, height)
            liveTimeGroup?.layout(0, 0, 0, 0)
            duration?.layout(width - duration.measuredWidth, 0, width, height)
            timeline?.layout(0, 0, width, height)
            preview?.layout(0, 0, 0, 0)
        }
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child !== position && child !== liveTimeGroup && child !== duration && child !== timeline && child !== preview) {
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
