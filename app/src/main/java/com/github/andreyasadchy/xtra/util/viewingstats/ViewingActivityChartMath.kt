package com.github.andreyasadchy.xtra.util.viewingstats

import kotlin.math.ceil
import kotlin.math.roundToLong

/** A chart bar may represent more than one calendar date. */
data class ViewingActivityChartBucket(
    val firstDayStart: Long,
    val lastDayStart: Long,
    val dayCount: Int,
    val watchedMs: Long,
) {

    /** Compare compacted bars as daily averages, not unequal bucket sums. */
    val dailyAverageMs: Long
        get() {
            if (watchedMs <= 0L || dayCount <= 0) return 0L
            val average = watchedMs.toDouble() / dayCount.toDouble()
            return if (average >= Long.MAX_VALUE.toDouble()) {
                Long.MAX_VALUE
            } else {
                average.roundToLong()
            }
        }
}

object ViewingActivityChartMath {

    /**
     * Compacts contiguous calendar dates while retaining their span. A final
     * shorter bucket is represented by its daily average, so it cannot look
     * like an artificial low-activity day beside full multi-day buckets.
     */
    fun compact(
        totals: List<DailyWatchTotal>,
        maxPoints: Int,
    ): List<ViewingActivityChartBucket> {
        if (totals.isEmpty()) return emptyList()
        val pointLimit = maxPoints.coerceAtLeast(1)
        val bucketSize = if (totals.size <= pointLimit) {
            1
        } else {
            ceil(totals.size / pointLimit.toDouble()).toInt()
        }
        return totals.chunked(bucketSize).map { bucket ->
            ViewingActivityChartBucket(
                firstDayStart = bucket.first().dayStart,
                lastDayStart = bucket.last().dayStart,
                dayCount = bucket.size,
                watchedMs = bucket.fold(0L) { total, point -> safeAdd(total, point.watchedMs) },
            )
        }
    }

    private fun safeAdd(left: Long, right: Long): Long {
        return if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }
}
