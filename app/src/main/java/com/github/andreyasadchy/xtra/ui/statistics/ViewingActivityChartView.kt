package com.github.andreyasadchy.xtra.ui.statistics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.withStyledAttributes
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.viewingstats.DailyWatchTotal
import com.github.andreyasadchy.xtra.util.viewingstats.ViewingActivityChartBucket
import com.github.andreyasadchy.xtra.util.viewingstats.ViewingActivityChartMath
import kotlin.math.ceil
import kotlin.math.max

class ViewingActivityChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val bars = Paint(Paint.ANTI_ALIAS_FLAG)
    private val baseline = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private var dailyTotals: List<DailyWatchTotal> = emptyList()

    init {
        context.withStyledAttributes(attrs, intArrayOf(android.R.attr.colorAccent, android.R.attr.textColorSecondary)) {
            bars.color = getColor(0, 0xff6750a4.toInt())
            baseline.color = getColor(1, 0xff777777.toInt())
            label.color = baseline.color
        }
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        minimumHeight = (112 * resources.displayMetrics.density).toInt()
    }

    fun setDailyTotals(totals: List<DailyWatchTotal>) {
        dailyTotals = totals
        contentDescription = totals.firstOrNull()?.let {
            context.getString(
                R.string.statistics_chart_description,
                totals.size,
                formatDuration(totals.fold(0L) { total, day ->
                    if (Long.MAX_VALUE - total < day.watchedMs) Long.MAX_VALUE else total + day.watchedMs
                }),
            )
        } ?: context.getString(R.string.statistics_chart_empty_description)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dailyTotals.isEmpty() || width <= 0 || height <= 0) return

        val points = ViewingActivityChartMath.compact(dailyTotals, MAX_POINTS)
        val left = resources.displayMetrics.density * 8f
        val right = width - left
        val top = resources.displayMetrics.density * 10f
        val bottom = height - resources.displayMetrics.density * 26f
        val chartHeight = max(1f, bottom - top)
        val maxValue = points.maxOfOrNull { it.dailyAverageMs } ?: 0L
        val barWidth = (right - left) / points.size
        val gap = minOf(resources.displayMetrics.density * 3f, barWidth * 0.25f)
        val labelStep = max(1, ceil(points.size / 6f).toInt())

        baseline.strokeWidth = resources.displayMetrics.density
        canvas.drawLine(left, bottom, right, bottom, baseline)
        points.forEachIndexed { index, point ->
            val xStart = left + index * barWidth + gap
            val xEnd = left + (index + 1) * barWidth - gap
            val ratio = if (maxValue > 0L) point.dailyAverageMs.toDouble() / maxValue else 0.0
            val yStart = bottom - (chartHeight * ratio).toFloat()
            canvas.drawRoundRect(
                RectF(xStart, yStart, maxOf(xStart + 1f, xEnd), bottom),
                resources.displayMetrics.density * 2f,
                resources.displayMetrics.density * 2f,
                bars,
            )
            if (index % labelStep == 0 || index == points.lastIndex) {
                label.textSize = resources.displayMetrics.density * 10f
                val date = formatDateLabel(point)
                canvas.drawText(date, (xStart + xEnd) / 2f, height - resources.displayMetrics.density * 8f, label)
            }
        }
    }

    private fun formatDateLabel(point: ViewingActivityChartBucket): String {
        val dateFormat = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT)
        val first = dateFormat.format(java.util.Date(point.firstDayStart))
        return if (point.dayCount == 1) {
            first
        } else {
            context.getString(
                R.string.statistics_chart_date_range,
                first,
                dateFormat.format(java.util.Date(point.lastDayStart)),
            )
        }
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

    companion object {
        private const val MAX_POINTS = 30
    }
}
