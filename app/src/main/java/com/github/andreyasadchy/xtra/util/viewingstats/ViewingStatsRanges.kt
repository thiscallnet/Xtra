package com.github.andreyasadchy.xtra.util.viewingstats

import java.util.Calendar
import java.util.TimeZone

enum class ViewingStatsRange {
    LAST_7_DAYS,
    LAST_30_DAYS,
    LAST_90_DAYS,
    ALL_TIME,
}

data class ViewingStatsRangeBounds(
    val fromInclusive: Long,
    val toExclusive: Long,
    val previousFromInclusive: Long? = null,
    val previousToExclusive: Long? = null,
)

object ViewingStatsRanges {

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
        ViewingStatsRange.ALL_TIME -> null
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

    fun addDays(timestamp: Long, days: Int, timeZone: TimeZone): Long {
        return Calendar.getInstance(timeZone).apply {
            timeInMillis = timestamp
            add(Calendar.DAY_OF_MONTH, days)
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
