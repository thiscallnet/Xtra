package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.ViewingStatsDao
import com.github.andreyasadchy.xtra.model.stats.ViewingInterval
import com.github.andreyasadchy.xtra.model.stats.ViewingPlaybackMetadata
import com.github.andreyasadchy.xtra.model.stats.ViewingSession
import com.github.andreyasadchy.xtra.util.viewingstats.ChannelWatchTotal
import com.github.andreyasadchy.xtra.util.viewingstats.DailyWatchTotal
import com.github.andreyasadchy.xtra.util.viewingstats.ViewingStatsCalculated
import com.github.andreyasadchy.xtra.util.viewingstats.ViewingStatsMath
import com.github.andreyasadchy.xtra.util.viewingstats.ViewingStatsRange
import com.github.andreyasadchy.xtra.util.viewingstats.ViewingStatsRangeBounds
import com.github.andreyasadchy.xtra.util.viewingstats.ViewingStatsRanges
import java.util.TimeZone

interface ViewingStatsStore {
    suspend fun insertSession(metadata: ViewingPlaybackMetadata, startedAt: Long): Long
    suspend fun updateSession(session: ViewingSession)
    suspend fun insertInterval(metadata: ViewingPlaybackMetadata, sessionId: Long, startAt: Long): Long
    suspend fun updateInterval(interval: ViewingInterval)
    suspend fun resetAll()
}

class ViewingStatsRepository(
    private val dao: ViewingStatsDao,
) : ViewingStatsStore {

    override suspend fun insertSession(metadata: ViewingPlaybackMetadata, startedAt: Long): Long {
        return dao.insertSession(metadata.toSession(startedAt))
    }

    override suspend fun updateSession(
        session: ViewingSession,
    ) {
        dao.updateSession(session)
    }

    override suspend fun insertInterval(metadata: ViewingPlaybackMetadata, sessionId: Long, startAt: Long): Long {
        return dao.insertInterval(metadata.toInterval(sessionId, startAt))
    }

    override suspend fun updateInterval(interval: ViewingInterval) {
        dao.updateInterval(interval)
    }

    override suspend fun resetAll() {
        dao.deleteAll()
    }

    suspend fun loadStatistics(
        range: ViewingStatsRange,
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
        topLimit: Int = DEFAULT_TOP_LIMIT,
    ): ViewingStatsSnapshot {
        val earliestRecordedAt = dao.getEarliestRecordedAt()
            ?: return ViewingStatsSnapshot.empty(range, earliestRecordedAt = null)
        val bounds = ViewingStatsRanges.bounds(range, now, earliestRecordedAt, timeZone)
        // All-Time still loads intervals for timezone/DST-aware daily activity,
        // habits, and channel snapshots. Only the straightforward unbounded
        // total and longest-session aggregates are delegated to Room above.
        val intervals = if (bounds.toExclusive > bounds.fromInclusive) {
            dao.getIntervals(bounds.fromInclusive, bounds.toExclusive)
        } else {
            emptyList()
        }
        val allTimeAggregates = if (range == ViewingStatsRange.ALL_TIME) {
            AllTimeAggregates(
                totalWatchedMs = dao.getAllTimeWatchedMs(),
                longestSessionMs = dao.getAllTimeLongestSessionMs(),
            )
        } else {
            null
        }
        val calculated = ViewingStatsMath.calculate(
            intervals = intervals,
            fromInclusive = bounds.fromInclusive,
            toExclusive = bounds.toExclusive,
            timeZone = timeZone,
            topLimit = topLimit,
            calendarDayCount = ViewingStatsRanges.calendarDayCount(range),
        ).let { result ->
            allTimeAggregates?.let {
                result.copy(
                    totalWatchedMs = it.totalWatchedMs,
                    longestSessionMs = it.longestSessionMs,
                )
            } ?: result
        }
        val previousFrom = bounds.previousFromInclusive
        val previousTo = bounds.previousToExclusive
        val previousTotal = if (previousFrom != null && previousTo != null) {
            val previousIntervals = dao.getIntervals(previousFrom, previousTo)
            ViewingStatsMath.calculate(
                intervals = previousIntervals,
                fromInclusive = previousFrom,
                toExclusive = previousTo,
                timeZone = timeZone,
                topLimit = 0,
            ).totalWatchedMs
        } else {
            null
        }
        val sessionCount = if (bounds.toExclusive > bounds.fromInclusive) {
            dao.getSessionCount(bounds.fromInclusive, bounds.toExclusive)
        } else 0
        val channelCount = if (bounds.toExclusive > bounds.fromInclusive) {
            dao.getChannelCount(bounds.fromInclusive, bounds.toExclusive)
        } else 0
        return calculated.toSnapshot(
            range = range,
            bounds = bounds,
            earliestRecordedAt = earliestRecordedAt,
            sessionCount = sessionCount,
            channelCount = channelCount,
            previousTotalMs = previousTotal,
        )
    }

    private fun ViewingPlaybackMetadata.toSession(startedAt: Long): ViewingSession {
        return ViewingSession(
            channelId = normalizedChannelId!!,
            channelLogin = channelLogin,
            channelName = channelName,
            channelImage = channelImage,
            contentType = contentType,
            contentId = contentId,
            startedAt = startedAt,
            endedAt = startedAt,
            watchedMs = 0L,
            lastCheckpointAt = startedAt,
        )
    }

    private fun ViewingPlaybackMetadata.toInterval(sessionId: Long, startAt: Long): ViewingInterval {
        return ViewingInterval(
            sessionId = sessionId,
            channelId = normalizedChannelId!!,
            channelLogin = channelLogin,
            channelName = channelName,
            channelImage = channelImage,
            startAt = startAt,
            endAt = startAt,
            watchedMs = 0L,
            lastCheckpointAt = startAt,
        )
    }

    companion object {
        const val DEFAULT_TOP_LIMIT = 5
    }

    private data class AllTimeAggregates(
        val totalWatchedMs: Long,
        val longestSessionMs: Long,
    )
}

data class ViewingStatsSnapshot(
    val range: ViewingStatsRange,
    val bounds: ViewingStatsRangeBounds?,
    val hasActivity: Boolean,
    val totalWatchMs: Long,
    val previousTotalMs: Long?,
    val comparisonPercent: Int?,
    val sessionCount: Int,
    val channelCount: Int,
    val activeDays: Int,
    val averageSessionMs: Long,
    val dailyTotals: List<DailyWatchTotal>,
    val topChannels: List<ChannelWatchTotal>,
    val mostActiveWeekday: Int?,
    val mostActiveTimeBucket: Int?,
    val longestSessionMs: Long,
    val longestActiveDayStreak: Int,
    val earliestRecordedAt: Long?,
) {

    companion object {
        fun empty(range: ViewingStatsRange, earliestRecordedAt: Long?): ViewingStatsSnapshot {
            return ViewingStatsSnapshot(
                range = range,
                bounds = null,
                hasActivity = false,
                totalWatchMs = 0L,
                previousTotalMs = null,
                comparisonPercent = null,
                sessionCount = 0,
                channelCount = 0,
                activeDays = 0,
                averageSessionMs = 0L,
                dailyTotals = emptyList(),
                topChannels = emptyList(),
                mostActiveWeekday = null,
                mostActiveTimeBucket = null,
                longestSessionMs = 0L,
                longestActiveDayStreak = 0,
                earliestRecordedAt = earliestRecordedAt,
            )
        }
    }
}

private fun ViewingStatsCalculated.toSnapshot(
    range: ViewingStatsRange,
    bounds: ViewingStatsRangeBounds,
    earliestRecordedAt: Long,
    sessionCount: Int,
    channelCount: Int,
    previousTotalMs: Long?,
): ViewingStatsSnapshot {
    val averageSessionMs = if (sessionCount > 0) totalWatchedMs / sessionCount else 0L
    return ViewingStatsSnapshot(
        range = range,
        bounds = bounds,
        hasActivity = totalWatchedMs > 0L,
        totalWatchMs = totalWatchedMs,
        previousTotalMs = previousTotalMs,
        comparisonPercent = previousTotalMs?.let { ViewingStatsMath.percentageChange(totalWatchedMs, it) },
        sessionCount = sessionCount,
        channelCount = channelCount,
        activeDays = dailyTotals.count { it.watchedMs > 0L },
        averageSessionMs = averageSessionMs,
        dailyTotals = dailyTotals,
        topChannels = topChannels,
        mostActiveWeekday = mostActiveWeekday,
        mostActiveTimeBucket = mostActiveTimeBucket,
        longestSessionMs = longestSessionMs,
        longestActiveDayStreak = longestActiveDayStreak,
        earliestRecordedAt = earliestRecordedAt,
    )
}
