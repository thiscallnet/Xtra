package com.github.andreyasadchy.xtra.util.viewingstats

import com.github.andreyasadchy.xtra.model.stats.ViewingInterval
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

data class DailyWatchTotal(
    val dayStart: Long,
    val watchedMs: Long,
)

data class ChannelWatchTotal(
    val channelId: String,
    val channelLogin: String?,
    val channelName: String?,
    val channelImage: String?,
    val watchedMs: Long,
)

data class ViewingStatsCalculated(
    val totalWatchedMs: Long,
    val dailyTotals: List<DailyWatchTotal>,
    val topChannels: List<ChannelWatchTotal>,
    val longestSessionMs: Long,
    val mostActiveWeekday: Int?,
    val mostActiveTimeBucket: Int?,
    val longestActiveDayStreak: Int,
)

/** Pure aggregation helpers shared by the repository and JVM tests. */
object ViewingStatsMath {

    fun calculate(
        intervals: List<ViewingInterval>,
        fromInclusive: Long,
        toExclusive: Long,
        timeZone: TimeZone,
        topLimit: Int = 5,
        calendarDayCount: Int? = null,
    ): ViewingStatsCalculated {
        val dailyTotals = dailyTotals(
            intervals = intervals,
            fromInclusive = fromInclusive,
            toExclusive = toExclusive,
            timeZone = timeZone,
            calendarDayCount = calendarDayCount,
        )
        val totalWatchedMs = dailyTotals.fold(0L) { total, day -> safeAdd(total, day.watchedMs) }
        val channelTotals = linkedMapOf<String, MutableChannelTotal>()
        val sessionTotals = mutableMapOf<Long, Long>()
        val timeBuckets = LongArray(TIME_BUCKET_COUNT)

        intervals.forEach { interval ->
            forEachSegment(interval, fromInclusive, toExclusive, timeZone, ::localDayStart) { start, end, watched ->
                val channel = channelTotals.getOrPut(interval.channelId) {
                    MutableChannelTotal(
                        channelId = interval.channelId,
                        channelLogin = interval.channelLogin,
                        channelName = interval.channelName,
                        channelImage = interval.channelImage,
                        lastSeenAt = interval.endAt,
                    )
                }
                if (interval.endAt >= channel.lastSeenAt) {
                    channel.channelLogin = interval.channelLogin ?: channel.channelLogin
                    channel.channelName = interval.channelName ?: channel.channelName
                    channel.channelImage = interval.channelImage ?: channel.channelImage
                    channel.lastSeenAt = interval.endAt
                }
                channel.watchedMs = safeAdd(channel.watchedMs, watched)
                sessionTotals[interval.sessionId] = safeAdd(sessionTotals[interval.sessionId] ?: 0L, watched)
            }
            forEachTimeBucketSegment(interval, fromInclusive, toExclusive, timeZone) { bucket, watched ->
                timeBuckets[bucket] = safeAdd(timeBuckets[bucket], watched)
            }
        }

        val weekdayTotals = LongArray(DAYS_IN_WEEK)
        dailyTotals.forEach { day ->
            val weekday = weekday(day.dayStart, timeZone)
            weekdayTotals[weekday] = safeAdd(weekdayTotals[weekday], day.watchedMs)
        }

        return ViewingStatsCalculated(
            totalWatchedMs = totalWatchedMs,
            dailyTotals = dailyTotals,
            topChannels = channelTotals.values
                .sortedWith(compareByDescending<MutableChannelTotal> { it.watchedMs }.thenBy { it.channelId })
                .take(topLimit.coerceAtLeast(0))
                .map {
                    ChannelWatchTotal(
                        channelId = it.channelId,
                        channelLogin = it.channelLogin,
                        channelName = it.channelName,
                        channelImage = it.channelImage,
                        watchedMs = it.watchedMs,
                    )
                },
            longestSessionMs = sessionTotals.values.maxOrNull() ?: 0L,
            mostActiveWeekday = weekdayTotals.indexOfMaxOrNull(),
            mostActiveTimeBucket = timeBuckets.indexOfMaxOrNull(),
            longestActiveDayStreak = longestActiveDayStreak(dailyTotals),
        )
    }

    fun dailyTotals(
        intervals: List<ViewingInterval>,
        fromInclusive: Long,
        toExclusive: Long,
        timeZone: TimeZone,
        calendarDayCount: Int? = null,
    ): List<DailyWatchTotal> {
        if (toExclusive <= fromInclusive) return emptyList()
        val totals = linkedMapOf<Long, Long>()
        var day = ViewingStatsRanges.localDayStart(fromInclusive, timeZone)
        val endDay = calendarDayCount
            ?.takeIf { it > 0 }
            ?.let { ViewingStatsRanges.addDays(day, it, timeZone) }
            ?: toExclusive
        while (day < endDay) {
            totals[day] = 0L
            val next = ViewingStatsRanges.nextLocalDayStart(day, timeZone)
            if (next <= day) break
            day = next
        }
        intervals.forEach { interval ->
            forEachSegment(interval, fromInclusive, toExclusive, timeZone, ::localDayStart) { start, _, watched ->
                val key = localDayStart(start, timeZone)
                if (totals.containsKey(key)) {
                    totals[key] = safeAdd(totals[key] ?: 0L, watched)
                }
            }
        }
        return totals.map { DailyWatchTotal(it.key, it.value) }
    }

    fun longestActiveDayStreak(dailyTotals: List<DailyWatchTotal>): Int {
        var current = 0
        var longest = 0
        dailyTotals.forEach {
            if (it.watchedMs > 0) {
                current += 1
                longest = max(longest, current)
            } else {
                current = 0
            }
        }
        return longest
    }

    fun percentageChange(current: Long, previous: Long): Int? {
        if (previous <= 0L) return null
        val change = ((current.toDouble() - previous.toDouble()) / previous.toDouble()) * 100.0
        if (!change.isFinite()) return null
        return change.roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    fun localDayStart(timestamp: Long, timeZone: TimeZone): Long =
        ViewingStatsRanges.localDayStart(timestamp, timeZone)

    fun weekday(dayStart: Long, timeZone: TimeZone): Int {
        val day = Calendar.getInstance(timeZone).apply { timeInMillis = dayStart }.get(Calendar.DAY_OF_WEEK)
        // Monday = 0, ..., Sunday = 6.
        return (day + 5) % DAYS_IN_WEEK
    }

    fun timeBucket(timestamp: Long, timeZone: TimeZone): Int {
        val hour = Calendar.getInstance(timeZone).apply { timeInMillis = timestamp }.get(Calendar.HOUR_OF_DAY)
        return (hour / HOURS_PER_BUCKET).coerceIn(0, TIME_BUCKET_COUNT - 1)
    }

    private fun forEachSegment(
        interval: ViewingInterval,
        fromInclusive: Long,
        toExclusive: Long,
        timeZone: TimeZone,
        boundaryStart: (Long, TimeZone) -> Long,
        block: (start: Long, end: Long, watchedMs: Long) -> Unit,
    ) {
        val intervalStart = interval.startAt
        val intervalEnd = interval.endAt
        if (intervalEnd <= intervalStart || interval.watchedMs <= 0L) return
        val clippedStart = max(intervalStart, fromInclusive)
        val clippedEnd = min(intervalEnd, toExclusive)
        if (clippedEnd <= clippedStart) return

        val wallDuration = intervalEnd - intervalStart
        val clippedDuration = clippedEnd - clippedStart
        val clippedWatchedMs = proportional(interval.watchedMs, clippedDuration, wallDuration)
        var cursor = clippedStart
        var assigned = 0L
        while (cursor < clippedEnd) {
            val boundary = boundaryStart(cursor, timeZone)
            val nextBoundary = when (boundaryStart) {
                ::localDayStart -> ViewingStatsRanges.nextLocalDayStart(boundary, timeZone)
                else -> nextLocalTimeBucketStart(boundary, timeZone)
            }
            val segmentEnd = min(clippedEnd, max(cursor + 1L, nextBoundary))
            val isLast = segmentEnd >= clippedEnd
            val watched = if (isLast) {
                max(0L, clippedWatchedMs - assigned)
            } else {
                proportional(clippedWatchedMs, segmentEnd - cursor, clippedDuration)
            }
            block(cursor, segmentEnd, watched)
            assigned = safeAdd(assigned, watched)
            cursor = segmentEnd
        }
    }

    private fun forEachTimeBucketSegment(
        interval: ViewingInterval,
        fromInclusive: Long,
        toExclusive: Long,
        timeZone: TimeZone,
        block: (bucket: Int, watchedMs: Long) -> Unit,
    ) {
        val intervalStart = interval.startAt
        val intervalEnd = interval.endAt
        if (intervalEnd <= intervalStart || interval.watchedMs <= 0L) return
        val clippedStart = max(intervalStart, fromInclusive)
        val clippedEnd = min(intervalEnd, toExclusive)
        if (clippedEnd <= clippedStart) return
        val wallDuration = intervalEnd - intervalStart
        val clippedDuration = clippedEnd - clippedStart
        val clippedWatchedMs = proportional(interval.watchedMs, clippedDuration, wallDuration)
        var cursor = clippedStart
        var assigned = 0L
        while (cursor < clippedEnd) {
            val bucketStart = localTimeBucketStart(cursor, timeZone)
            val nextBoundary = nextLocalTimeBucketStart(bucketStart, timeZone)
            val segmentEnd = min(clippedEnd, max(cursor + 1L, nextBoundary))
            val isLast = segmentEnd >= clippedEnd
            val watched = if (isLast) {
                max(0L, clippedWatchedMs - assigned)
            } else {
                proportional(clippedWatchedMs, segmentEnd - cursor, clippedDuration)
            }
            block(timeBucket(cursor, timeZone), watched)
            assigned = safeAdd(assigned, watched)
            cursor = segmentEnd
        }
    }

    private fun localTimeBucketStart(timestamp: Long, timeZone: TimeZone): Long {
        return Calendar.getInstance(timeZone).apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, get(Calendar.HOUR_OF_DAY) / HOURS_PER_BUCKET * HOURS_PER_BUCKET)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun nextLocalTimeBucketStart(bucketStart: Long, timeZone: TimeZone): Long {
        return Calendar.getInstance(timeZone).apply {
            timeInMillis = bucketStart
            add(Calendar.HOUR_OF_DAY, HOURS_PER_BUCKET)
        }.timeInMillis
    }

    private fun proportional(total: Long, numerator: Long, denominator: Long): Long {
        if (total <= 0L || numerator <= 0L || denominator <= 0L) return 0L
        val value = total.toDouble() * numerator.toDouble() / denominator.toDouble()
        return when {
            value >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
            else -> value.roundToLong().coerceAtLeast(0L)
        }
    }

    private fun safeAdd(left: Long, right: Long): Long {
        return if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }

    private fun LongArray.indexOfMaxOrNull(): Int? {
        var bestIndex = -1
        var bestValue = 0L
        forEachIndexed { index, value ->
            if (value > bestValue) {
                bestValue = value
                bestIndex = index
            }
        }
        return bestIndex.takeIf { it >= 0 }
    }

    private data class MutableChannelTotal(
        val channelId: String,
        var channelLogin: String?,
        var channelName: String?,
        var channelImage: String?,
        var watchedMs: Long = 0L,
        var lastSeenAt: Long,
    )

    private const val DAYS_IN_WEEK = 7
    private const val HOURS_PER_BUCKET = 3
    private const val TIME_BUCKET_COUNT = 24 / HOURS_PER_BUCKET
}
