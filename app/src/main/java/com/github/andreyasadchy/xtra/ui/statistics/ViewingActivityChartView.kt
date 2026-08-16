package com.github.andreyasadchy.xtra.ui.statistics

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.withStyledAttributes
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.viewingstats.TimelineWatchTotal
import java.text.DateFormat
import java.util.Date
import kotlin.math.ceil
import kotlin.math.max

/**
 * Small dependency-free interactive timeline. The repository supplies
 * explicit local-time buckets; this view only handles rendering and pointer
 * selection/scrubbing.
 */
@SuppressLint("ResourceType")
class ViewingActivityChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val bars = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedBar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val baseline = Paint(Paint.ANTI_ALIAS_FLAG)
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val calloutText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val calloutBackground = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bucketRect = RectF()
    private val calloutRect = RectF()
    private var buckets: List<TimelineWatchTotal> = emptyList()
    private var selectedIndex = -1
    private var dragging = false
    private var horizontalGesture = false
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    var onBucketSelected: ((index: Int, bucket: TimelineWatchTotal) -> Unit)? = null

    init {
        context.withStyledAttributes(attrs, intArrayOf(android.R.attr.colorAccent, android.R.attr.textColorSecondary)) {
            bars.color = getColor(0, 0xff6750a4.toInt())
            selectedBar.color = lighten(getColor(0, 0xff6750a4.toInt()))
            baseline.color = getColor(1, 0xff777777.toInt())
            marker.color = getColor(0, 0xff6750a4.toInt())
            label.color = baseline.color
            calloutText.color = getColor(1, 0xff777777.toInt())
            calloutBackground.color = getColor(1, 0x22777777)
        }
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        minimumHeight = (148 * resources.displayMetrics.density).toInt()
    }

    fun setBuckets(value: List<TimelineWatchTotal>) {
        buckets = value
        selectedIndex = selectedIndex.coerceIn(-1, value.lastIndex)
        updateContentDescription()
        invalidate()
    }

    fun setSelectedIndex(index: Int) {
        selectedIndex = index.coerceIn(-1, buckets.lastIndex)
        updateContentDescription()
        invalidate()
    }

    fun selectedBucket(): TimelineWatchTotal? = buckets.getOrNull(selectedIndex)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (buckets.isEmpty() || width <= 0 || height <= 0) return

        val density = resources.displayMetrics.density
        val left = density * 8f
        val right = width - density * 8f
        val top = density * 28f
        val bottom = height - density * 26f
        val chartHeight = max(1f, bottom - top)
        val maxValue = buckets.maxOfOrNull { it.watchedMs } ?: 0L
        val bucketWidth = (right - left) / buckets.size.coerceAtLeast(1)
        val gap = minOf(density * 3f, bucketWidth * 0.25f)
        val labelStep = max(1, ceil(buckets.size / 6f).toInt())

        baseline.strokeWidth = density
        canvas.drawLine(left, bottom, right, bottom, baseline)
        buckets.forEachIndexed { index, bucket ->
            val xStart = left + index * bucketWidth + gap
            val xEnd = left + (index + 1) * bucketWidth - gap
            val ratio = if (maxValue > 0L) bucket.watchedMs.toDouble() / maxValue else 0.0
            val yStart = bottom - (chartHeight * ratio).toFloat()
            bucketRect.set(xStart, yStart, maxOf(xStart + 1f, xEnd), bottom)
            canvas.drawRoundRect(
                bucketRect,
                density * 2f,
                density * 2f,
                if (index == selectedIndex) selectedBar else bars,
            )
            if (index % labelStep == 0 || index == buckets.lastIndex) {
                label.textSize = density * 10f
                canvas.drawText(formatDateLabel(bucket), (xStart + xEnd) / 2f, height - density * 8f, label)
            }
        }

        buckets.getOrNull(selectedIndex)?.let { bucket ->
            val x = left + (selectedIndex + 0.5f) * bucketWidth
            marker.strokeWidth = density * 1.5f
            canvas.drawLine(x, top, x, bottom, marker)
            val title = formatDateLabel(bucket)
            val value = formatDuration(bucket.watchedMs)
            calloutText.textSize = density * 11f
            val textWidth = max(calloutText.measureText(title), calloutText.measureText(value))
            val boxWidth = textWidth + density * 20f
            val boxHeight = density * 34f
            val boxLeft = (x - boxWidth / 2f).coerceIn(left, right - boxWidth)
            val boxTop = density * 2f
            calloutRect.set(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight)
            canvas.drawRoundRect(calloutRect, density * 6f, density * 6f, calloutBackground)
            canvas.drawText(title, boxLeft + boxWidth / 2f, boxTop + density * 14f, calloutText)
            canvas.drawText(value, boxLeft + boxWidth / 2f, boxTop + density * 28f, calloutText)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (buckets.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                horizontalGesture = false
                moved = false
                downX = event.x
                downY = event.y
                // Let the parent decide whether a vertical scroll should win.
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                if (!horizontalGesture) {
                    val deltaX = event.x - downX
                    val deltaY = event.y - downY
                    if (kotlin.math.abs(deltaX) > touchSlop || kotlin.math.abs(deltaY) > touchSlop) {
                        moved = true
                        if (kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY)) {
                            horizontalGesture = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                        } else {
                            // A vertical gesture belongs to the scrolling page.
                            dragging = false
                            parent?.requestDisallowInterceptTouchEvent(false)
                            return false
                        }
                    }
                }
                if (horizontalGesture) selectAt(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging && (!moved || horizontalGesture)) selectAt(event.x)
                dragging = false
                horizontalGesture = false
                moved = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                horizontalGesture = false
                moved = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        val nextIndex = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ->
                (if (selectedIndex < 0) 0 else selectedIndex + 1).takeIf { it <= buckets.lastIndex }
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ->
                (if (selectedIndex < 0) buckets.lastIndex else selectedIndex - 1).takeIf { it >= 0 }
            else -> null
        }
        return if (nextIndex != null) {
            selectIndex(nextIndex)
            true
        } else {
            super.performAccessibilityAction(action, arguments)
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = buckets.size > 1
        if (buckets.size > 1) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun selectAt(x: Float) {
        val left = resources.displayMetrics.density * 8f
        val right = width - left
        val index = (((x - left) / ((right - left) / buckets.size))
            .toInt())
            .coerceIn(0, buckets.lastIndex)
        selectIndex(index)
    }

    private fun selectIndex(index: Int) {
        if (index == selectedIndex || buckets.isEmpty()) return
        selectedIndex = index
        updateContentDescription()
        invalidate()
        onBucketSelected?.invoke(index, buckets[index])
    }

    private fun updateContentDescription() {
        contentDescription = when (val selected = buckets.getOrNull(selectedIndex)) {
            null -> if (buckets.isEmpty()) {
                context.getString(R.string.statistics_chart_empty_description)
            } else {
                context.getString(R.string.statistics_chart_description, buckets.size, formatDuration(buckets.sumOf { it.watchedMs }))
            }
            else -> context.getString(
                R.string.statistics_chart_selected_description,
                formatDateLabel(selected),
                formatDuration(selected.watchedMs),
                selected.sessionCount,
            )
        }
    }

    private fun formatDateLabel(bucket: TimelineWatchTotal): String {
        val dateFormat = DateFormat.getDateInstance(DateFormat.SHORT)
        val first = dateFormat.format(Date(bucket.startAt))
        val last = dateFormat.format(Date((bucket.endAt - 1L).coerceAtLeast(bucket.startAt)))
        return if (first == last) first else context.getString(R.string.statistics_chart_date_range, first, last)
    }

    private fun formatDuration(milliseconds: Long): String {
        val minutes = milliseconds.coerceAtLeast(0L) / 60_000L
        val hours = minutes / 60L
        return when {
            hours > 0L -> context.getString(R.string.statistics_duration_hours, hours, minutes % 60L)
            minutes > 0L -> context.getString(R.string.statistics_duration_minutes, minutes)
            else -> context.getString(R.string.statistics_duration_less_than_minute)
        }
    }

    private fun lighten(color: Int): Int {
        val red = ((color shr 16) and 0xff).coerceAtMost(255)
        val green = ((color shr 8) and 0xff).coerceAtMost(255)
        val blue = (color and 0xff).coerceAtMost(255)
        return android.graphics.Color.rgb(
            (red + 255) / 2,
            (green + 255) / 2,
            (blue + 255) / 2,
        )
    }
}
