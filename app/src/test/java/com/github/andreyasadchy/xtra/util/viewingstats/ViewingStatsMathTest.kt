package com.github.andreyasadchy.xtra.util.viewingstats

import com.github.andreyasadchy.xtra.model.stats.ViewingInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ViewingStatsMathTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun midnightSpanningIntervalIsApportionedToBothCalendarDays() {
        val start = timestamp(2024, Calendar.JANUARY, 1, 23, 30, utc)
        val end = timestamp(2024, Calendar.JANUARY, 2, 1, 30, utc)
        val days = ViewingStatsMath.dailyTotals(
            intervals = listOf(interval(1L, "channel-a", start, end, 2.hours)),
            fromInclusive = ViewingStatsRanges.localDayStart(start, utc),
            toExclusive = ViewingStatsRanges.addDays(ViewingStatsRanges.localDayStart(start, utc), 2, utc),
            timeZone = utc,
        )

        assertEquals(30.minutes, days[0].watchedMs)
        assertEquals(90.minutes, days[1].watchedMs)
    }

    @Test
    fun partialRangeDoesNotAssignTheWholeIntervalToTheOverlap() {
        val intervalStart = timestamp(2024, Calendar.JANUARY, 1, 23, 0, utc)
        val intervalEnd = timestamp(2024, Calendar.JANUARY, 2, 2, 0, utc)
        val rangeStart = timestamp(2024, Calendar.JANUARY, 2, 0, 0, utc)
        val rangeEnd = intervalEnd
        val result = ViewingStatsMath.calculate(
            intervals = listOf(interval(1L, "channel-a", intervalStart, intervalEnd, 3.hours)),
            fromInclusive = rangeStart,
            toExclusive = rangeEnd,
            timeZone = utc,
        )

        assertEquals(2.hours, result.totalWatchedMs)
        assertEquals(2.hours, result.topChannels.single().watchedMs)
        assertEquals(2.hours, result.longestSessionMs)
    }

    @Test
    fun partialRangeClipsTimeBucketAggregationToo() {
        val firstStart = timestamp(2024, Calendar.JANUARY, 1, 23, 0, utc)
        val firstEnd = timestamp(2024, Calendar.JANUARY, 2, 2, 0, utc)
        val secondStart = timestamp(2024, Calendar.JANUARY, 2, 3, 0, utc)
        val secondEnd = timestamp(2024, Calendar.JANUARY, 2, 5, 30, utc)
        val result = ViewingStatsMath.calculate(
            intervals = listOf(
                interval(1L, "channel-a", firstStart, firstEnd, 3.hours),
                interval(2L, "channel-b", secondStart, secondEnd, 150.minutes),
            ),
            fromInclusive = timestamp(2024, Calendar.JANUARY, 2, 0, 0, utc),
            toExclusive = timestamp(2024, Calendar.JANUARY, 2, 6, 0, utc),
            timeZone = utc,
        )

        // The 03:00–06:00 bucket wins only when the first interval is clipped to 2h.
        assertEquals(1, result.mostActiveTimeBucket)
    }

    @Test
    fun topChannelsAggregateByBroadcasterIdAndKeepNewestSnapshot() {
        val first = timestamp(2024, Calendar.JANUARY, 1, 10, 0, utc)
        val second = first + 2.hours
        val result = ViewingStatsMath.calculate(
            intervals = listOf(
                interval(1L, "channel-a", first, first + 1.hours, 1.hours, channelName = "Old name"),
                interval(2L, "channel-a", second, second + 1.hours, 1.hours, channelName = "New name"),
                interval(3L, "channel-b", first, first + 90.minutes, 90.minutes, channelName = "Other"),
            ),
            fromInclusive = first,
            toExclusive = second + 1.hours,
            timeZone = utc,
        )

        assertEquals(2, result.topChannels.size)
        assertEquals("channel-a", result.topChannels[0].channelId)
        assertEquals("New name", result.topChannels[0].channelName)
        assertEquals(2.hours, result.topChannels[0].watchedMs)
        assertEquals(90.minutes, result.longestSessionMs)
    }

    @Test
    fun previousPeriodZeroProducesNoPercentage() {
        assertNull(ViewingStatsMath.percentageChange(10.minutes, 0L))
        assertEquals(100, ViewingStatsMath.percentageChange(2.hours, 1.hours))
    }

    @Test
    fun sevenDayRangeHasExactlySevenLocalDatesAndEqualComparisonDuration() {
        val now = timestamp(2024, Calendar.AUGUST, 13, 7, 0, utc)
        val bounds = ViewingStatsRanges.bounds(
            range = ViewingStatsRange.LAST_7_DAYS,
            now = now,
            earliestRecordedAt = now - 100.days,
            timeZone = utc,
        )

        assertEquals(timestamp(2024, Calendar.AUGUST, 7, 0, 0, utc), bounds.fromInclusive)
        assertEquals(bounds.fromInclusive, bounds.previousToExclusive)
        assertEquals(now, bounds.toExclusive)
        assertEquals(
            bounds.toExclusive - bounds.fromInclusive,
            bounds.previousToExclusive!! - bounds.previousFromInclusive!!,
        )

        val calculated = ViewingStatsMath.calculate(
            intervals = (-1..6).map { dayOffset ->
                val start = ViewingStatsRanges.addDays(bounds.fromInclusive, dayOffset, utc)
                interval(
                    sessionId = dayOffset.toLong() + 2L,
                    channelId = "channel-$dayOffset",
                    start = start,
                    end = start + 30.minutes,
                    watched = 30.minutes,
                )
            },
            fromInclusive = bounds.fromInclusive,
            toExclusive = bounds.toExclusive,
            timeZone = utc,
            calendarDayCount = ViewingStatsRanges.calendarDayCount(ViewingStatsRange.LAST_7_DAYS),
        )

        assertEquals(7, calculated.dailyTotals.size)
        assertEquals(7, calculated.activeDays())
    }

    @Test
    fun daylightSavingDayUsesActualElapsedMilliseconds() {
        val zone = TimeZone.getTimeZone("America/New_York")
        val start = timestamp(2024, Calendar.MARCH, 10, 0, 0, zone)
        val next = ViewingStatsRanges.addDays(start, 1, zone)
        val totals = ViewingStatsMath.dailyTotals(
            intervals = listOf(interval(1L, "channel-a", start, next, next - start)),
            fromInclusive = start,
            toExclusive = next,
            timeZone = zone,
        )

        assertEquals(23.hours, totals.single().watchedMs)
    }

    @Test
    fun veryLargeTotalsRemainNonNegativeAndDoNotOverflowPercentage() {
        val result = ViewingStatsMath.calculate(
            intervals = listOf(interval(1L, "channel-a", 0L, 24.hours, Long.MAX_VALUE)),
            fromInclusive = 0L,
            toExclusive = 24.hours,
            timeZone = utc,
        )

        assertTrue(result.totalWatchedMs >= 0L)
        assertEquals(Int.MAX_VALUE, ViewingStatsMath.percentageChange(Long.MAX_VALUE, 1L))
    }

    private fun interval(
        sessionId: Long,
        channelId: String,
        start: Long,
        end: Long,
        watched: Long,
        channelName: String? = channelId,
    ) = ViewingInterval(
        id = sessionId,
        sessionId = sessionId,
        channelId = channelId,
        channelLogin = channelId,
        channelName = channelName,
        channelImage = null,
        startAt = start,
        endAt = end,
        watchedMs = watched,
        lastCheckpointAt = end,
    )

    private fun timestamp(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: TimeZone): Long {
        return Calendar.getInstance(zone).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis
    }

    private val Int.minutes: Long get() = this * 60_000L
    private val Int.hours: Long get() = this * 60.minutes
    private val Int.days: Long get() = this * 24.hours

    private fun ViewingStatsCalculated.activeDays(): Int = dailyTotals.count { it.watchedMs > 0L }
}
