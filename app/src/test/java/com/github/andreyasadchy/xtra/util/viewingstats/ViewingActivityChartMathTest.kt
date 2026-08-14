package com.github.andreyasadchy.xtra.util.viewingstats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewingActivityChartMathTest {

    @Test
    fun ninetyDayCompactionUsesDailyAveragesForShortFinalBucket() {
        val dailyWatchTime = 1.hours
        val totals = (0 until 90).map { day ->
            DailyWatchTotal(dayStart = day.toLong(), watchedMs = dailyWatchTime)
        }

        // Force compaction to expose a shorter final bucket like an older
        // 90-day range with an extra calendar date could produce.
        val buckets = ViewingActivityChartMath.compact(totals, maxPoints = 7)
        val ninetyDayBuckets = ViewingActivityChartMath.compact(totals, maxPoints = 30)

        assertTrue(buckets.last().dayCount < buckets.first().dayCount)
        assertEquals(dailyWatchTime, buckets.first().dailyAverageMs)
        assertTrue(buckets.all { it.dailyAverageMs == dailyWatchTime })
        assertEquals(90, buckets.sumOf { it.dayCount })
        assertEquals(30, ninetyDayBuckets.size)
        assertTrue(ninetyDayBuckets.all { it.dayCount == 3 })
        assertTrue(ninetyDayBuckets.all { it.dailyAverageMs == dailyWatchTime })
    }

    private val Int.hours: Long get() = this * 60.minutes
    private val Int.minutes: Long get() = this * 60_000L
}
