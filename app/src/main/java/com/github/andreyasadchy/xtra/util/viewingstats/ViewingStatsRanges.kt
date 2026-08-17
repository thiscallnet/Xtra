package com.github.andreyasadchy.xtra.util.viewingstats

import java.util.Calendar
import java.util.TimeZone

enum class ViewingStatsRange {
    LAST_7_DAYS,
    LAST_30_DAYS,
    LAST_90_DAYS,
    LAST_YEAR,
    ALL_TIME,
}

data class ViewingStatsRangeBounds(
    val fromInclusive: Long,
    val toExclusive: Long,
    val previousFromInclusive: Long? = null,
    val previousToExclusive: Long? = null,
)

object ViewingStatsRanges {

    const val TIME_BUCKET_HOURS = 3
    const val TIME_BUCKET_COUNT = 24 / TIME_BUCKET_HOURS

    fun bounds(
        range: ViewingStatsRange,
        now: Long,
        earliestRecordedAt: Long?,
        timeZone: TimeZone,
    ): ViewingStatsRangeBounds {
        if (range == ViewingStatsRange.ALL_TIME) {
            return ViewingStatsRangeBounds(
                fromInclusive = earliestRecordedAt ?: now,
                toExclusive = now,
            )
        }

        val days = when (range) {
            ViewingStatsRange.LAST_7_DAYS -> 7
            ViewingStatsRange.LAST_30_DAYS -> 30
            ViewingStatsRange.LAST_90_DAYS -> 90
            ViewingStatsRange.LAST_YEAR -> 365
            ViewingStatsRange.ALL_TIME -> error("All time is handled above")
        }
        // The current window is made from exactly N local calendar dates,
        // including today's partial date. Its comparison window uses the same
        // elapsed duration, so DST and the current time-of-day are preserved
        // without adding an eighth date to the current chart.
        val todayStart = localDayStart(now, timeZone)
        val from = addDays(todayStart, -(days - 1), timeZone)
        val periodDurationMs = (now - from).coerceAtLeast(0L)
        val previousTo = from
        val previousFrom = subtract(previousTo, periodDurationMs)
        return ViewingStatsRangeBounds(
            fromInclusive = from,
            toExclusive = now,
            previousFromInclusive = previousFrom,
            previousToExclusive = previousTo,
        )
    }

    fun calendarDayCount(range: ViewingStatsRange): Int? = when (range) {
        ViewingStatsRange.LAST_7_DAYS -> 7
        ViewingStatsRange.LAST_30_DAYS -> 30
        ViewingStatsRange.LAST_90_DAYS -> 90
        ViewingStatsRange.LAST_YEAR -> 365
        ViewingStatsRange.ALL_TIME -> null
    }

    /**
     * Produces local-calendar buckets for the chart. Short ranges stay daily;
     * longer ranges use calendar weeks/months so every point has an explicit,
     * human-readable time span instead of opaque bar compaction.
     */
    fun timelineBuckets(
        range: ViewingStatsRange,
        bounds: ViewingStatsRangeBounds,
        timeZone: TimeZone,
    ): List<StatsTimelineBucketBounds> {
        if (bounds.toExclusive <= bounds.fromInclusive) return emptyList()
        val firstDay = localDayStart(bounds.fromInclusive, timeZone)
        val bucketDays = when (range) {
            ViewingStatsRange.LAST_7_DAYS,
            ViewingStatsRange.LAST_30_DAYS,
            ViewingStatsRange.LAST_90_DAYS,
            -> 1
            ViewingStatsRange.LAST_YEAR -> 7
            ViewingStatsRange.ALL_TIME -> 0
        }
        val result = mutableListOf<StatsTimelineBucketBounds>()
        var start = if (range == ViewingStatsRange.ALL_TIME) localMonthStart(bounds.fromInclusive, timeZone) else firstDay
        while (start < bounds.toExclusive) {
            val end = if (range == ViewingStatsRange.ALL_TIME) {
                addMonths(start, 1, timeZone).coerceAtMost(bounds.toExclusive)
            } else {
                addDays(start, bucketDays, timeZone).coerceAtMost(bounds.toExclusive)
            }
            result += StatsTimelineBucketBounds(
                startAt = maxOf(start, bounds.fromInclusive),
                endAt = end,
            )
            if (end <= start) break
            start = end
        }
        return result
    }

    fun dailyBuckets(
        bounds: ViewingStatsRangeBounds,
        timeZone: TimeZone,
    ): List<StatsTimelineBucketBounds> {
        if (bounds.toExclusive <= bounds.fromInclusive) return emptyList()
        val result = mutableListOf<StatsTimelineBucketBounds>()
        var start = localDayStart(bounds.fromInclusive, timeZone)
        while (start < bounds.toExclusive) {
            val end = addDays(start, 1, timeZone).coerceAtMost(bounds.toExclusive)
            result += StatsTimelineBucketBounds(
                startAt = maxOf(start, bounds.fromInclusive),
                endAt = end,
            )
            if (end <= start) break
            start = end
        }
        return result
    }

    /**
     * Returns the local-clock 3-hour buckets for one calendar day.
     *
     * The boundaries are set to 00:00, 03:00, ..., 21:00 in the supplied
     * timezone instead of advancing elapsed hours. This keeps the bucket
     * labels anchored to local clock time on daylight-saving transitions;
     * the affected bucket is consequently allowed to last 2 or 4 elapsed
     * hours.
     */
    fun timeBucketsForLocalDay(
        dayStart: Long,
        timeZone: TimeZone,
    ): List<StatsTimelineBucketBounds> {
        val localStart = localDayStart(dayStart, timeZone)
        val nextDayStart = nextLocalDayStart(localStart, timeZone)
        return (0 until TIME_BUCKET_COUNT).map { index ->
            val start = localClockTime(localStart, index * TIME_BUCKET_HOURS, timeZone)
            val end = if (index == TIME_BUCKET_COUNT - 1) {
                nextDayStart
            } else {
                localClockTime(localStart, (index + 1) * TIME_BUCKET_HOURS, timeZone)
            }
            StatsTimelineBucketBounds(startAt = start, endAt = end)
        }
    }

    fun localDayStart(timestamp: Long, timeZone: TimeZone): Long {
        return Calendar.getInstance(timeZone).apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun nextLocalDayStart(dayStart: Long, timeZone: TimeZone): Long = addDays(dayStart, 1, timeZone)

    private fun localMonthStart(timestamp: Long, timeZone: TimeZone): Long {
        return Calendar.getInstance(timeZone).apply {
            timeInMillis = timestamp
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun addMonths(timestamp: Long, months: Int, timeZone: TimeZone): Long {
        return Calendar.getInstance(timeZone).apply {
            timeInMillis = timestamp
            add(Calendar.MONTH, months)
        }.timeInMillis
    }

    fun addDays(timestamp: Long, days: Int, timeZone: TimeZone): Long {
        return Calendar.getInstance(timeZone).apply {
            timeInMillis = timestamp
            add(Calendar.DAY_OF_MONTH, days)
        }.timeInMillis
    }

    private fun localClockTime(dayStart: Long, hour: Int, timeZone: TimeZone): Long {
        return Calendar.getInstance(timeZone).apply {
            timeInMillis = dayStart
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun subtract(timestamp: Long, duration: Long): Long {
        return if (duration > 0L && timestamp < Long.MIN_VALUE + duration) {
            Long.MIN_VALUE
        } else {
            timestamp - duration
        }
    }

}

data class StatsTimelineBucketBounds(
    val startAt: Long,
    val endAt: Long,
)
