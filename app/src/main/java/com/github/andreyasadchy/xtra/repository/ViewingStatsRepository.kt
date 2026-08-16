package com.github.andreyasadchy.xtra.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import com.github.andreyasadchy.xtra.db.ViewingStatsDao
import com.github.andreyasadchy.xtra.db.ViewingStatsTimelineRow
import com.github.andreyasadchy.xtra.model.stats.ViewingInterval
import com.github.andreyasadchy.xtra.model.stats.ViewingPlaybackMetadata
import com.github.andreyasadchy.xtra.model.stats.ViewingSession
import com.github.andreyasadchy.xtra.util.viewingstats.CategoryWatchTotal
import com.github.andreyasadchy.xtra.util.viewingstats.ChannelWatchTotal
import com.github.andreyasadchy.xtra.util.viewingstats.ContentTypeWatchTotal
import com.github.andreyasadchy.xtra.util.viewingstats.DailyWatchTotal
import com.github.andreyasadchy.xtra.util.viewingstats.PatternWatchTotal
import com.github.andreyasadchy.xtra.util.viewingstats.TimelineWatchTotal
import com.github.andreyasadchy.xtra.util.viewingstats.ViewingStatsMath
import com.github.andreyasadchy.xtra.util.viewingstats.ViewingStatsRange
import com.github.andreyasadchy.xtra.util.viewingstats.ViewingStatsRangeBounds
import com.github.andreyasadchy.xtra.util.viewingstats.ViewingStatsRanges
import com.github.andreyasadchy.xtra.util.viewingstats.StatsTimelineBucketBounds
import kotlinx.coroutines.flow.Flow
import java.util.TimeZone
import kotlin.math.roundToLong

interface ViewingStatsStore {
    suspend fun insertSession(metadata: ViewingPlaybackMetadata, startedAt: Long): Long
    suspend fun updateSession(session: ViewingSession)
    suspend fun insertInterval(metadata: ViewingPlaybackMetadata, sessionId: Long, startAt: Long): Long
    suspend fun updateInterval(interval: ViewingInterval)
    suspend fun resetAll()
}

data class StatsFilter(
    val fromInclusive: Long,
    val toExclusive: Long,
    val channelId: String? = null,
    val categoryKey: String? = null,
    val contentType: String? = null,
)

class ViewingStatsRepository(
    private val dao: ViewingStatsDao,
) : ViewingStatsStore {

    override suspend fun insertSession(metadata: ViewingPlaybackMetadata, startedAt: Long): Long {
        return dao.insertSession(metadata.toSession(startedAt))
    }

    override suspend fun updateSession(session: ViewingSession) {
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

    fun observeChanges(): Flow<Long?> = dao.observeLastCheckpointAt()

    suspend fun loadStatistics(
        range: ViewingStatsRange,
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
        topLimit: Int = DEFAULT_TOP_LIMIT,
    ): ViewingStatsSnapshot {
        val earliestRecordedAt = dao.getEarliestRecordedAt()
            ?: return ViewingStatsSnapshot.empty(range, earliestRecordedAt = null)
        val bounds = ViewingStatsRanges.bounds(range, now, earliestRecordedAt, timeZone)
        val filter = StatsFilter(bounds.fromInclusive, bounds.toExclusive)
        val snapshot = loadSnapshot(
            range = range,
            bounds = bounds,
            filter = filter,
            timeZone = timeZone,
            topLimit = topLimit,
            earliestRecordedAt = earliestRecordedAt,
        )
        val previousTotal = bounds.previousFromInclusive?.let { previousFrom ->
            val previousTo = bounds.previousToExclusive ?: return@let null
            if (previousTo <= previousFrom) {
                0L
            } else {
                dao.getOverview(
                    fromInclusive = previousFrom,
                    toExclusive = previousTo,
                ).totalWatchMs
            }
        }
        return snapshot.copy(
            previousTotalMs = previousTotal,
            comparisonPercent = previousTotal?.let {
                ViewingStatsMath.percentageChange(snapshot.totalWatchMs, it)
            },
        )
    }

    suspend fun loadChannelDetail(
        channelId: String,
        range: ViewingStatsRange,
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): ViewingStatsDetailSnapshot? {
        return loadDetailForRange(
            range = range,
            now = now,
            timeZone = timeZone,
            filterBuilder = { StatsFilter(it.fromInclusive, it.toExclusive, channelId = channelId) },
            title = null,
        )
    }

    suspend fun loadCategoryDetail(
        categoryKey: String,
        range: ViewingStatsRange,
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): ViewingStatsDetailSnapshot? {
        return loadDetailForRange(
            range = range,
            now = now,
            timeZone = timeZone,
            filterBuilder = { StatsFilter(it.fromInclusive, it.toExclusive, categoryKey = categoryKey) },
            title = null,
        )
    }

    suspend fun loadBucketDetail(
        fromInclusive: Long,
        toExclusive: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): ViewingStatsDetailSnapshot {
        val filter = StatsFilter(fromInclusive, toExclusive)
        return loadDetail(
            filter = filter,
            range = null,
            timeZone = timeZone,
            title = null,
        )
    }

    suspend fun loadAllChannels(
        range: ViewingStatsRange,
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<ChannelWatchTotal> {
        val earliest = dao.getEarliestRecordedAt() ?: return emptyList()
        val bounds = ViewingStatsRanges.bounds(range, now, earliest, timeZone)
        return dao.getChannelTotals(
            fromInclusive = bounds.fromInclusive,
            toExclusive = bounds.toExclusive,
            limit = Int.MAX_VALUE,
        ).map { it.toChannelTotal() }
    }

    suspend fun loadAllCategories(
        range: ViewingStatsRange,
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<CategoryWatchTotal> {
        val earliest = dao.getEarliestRecordedAt() ?: return emptyList()
        val bounds = ViewingStatsRanges.bounds(range, now, earliest, timeZone)
        return dao.getCategoryTotals(
            fromInclusive = bounds.fromInclusive,
            toExclusive = bounds.toExclusive,
            limit = Int.MAX_VALUE,
        ).map { it.toCategoryTotal() }
    }

    suspend fun loadTotalWatchMs(
        range: ViewingStatsRange,
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Long {
        val earliest = dao.getEarliestRecordedAt() ?: return 0L
        val bounds = ViewingStatsRanges.bounds(range, now, earliest, timeZone)
        return dao.getTotalWatchMs(
            fromInclusive = bounds.fromInclusive,
            toExclusive = bounds.toExclusive,
        )
    }

    suspend fun loadPatternTotals(
        fromInclusive: Long,
        toExclusive: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<PatternWatchTotal> = loadPatternTotals(
        filter = StatsFilter(fromInclusive, toExclusive),
        timeZone = timeZone,
    )

    private suspend fun loadDetailForRange(
        range: ViewingStatsRange,
        now: Long,
        timeZone: TimeZone,
        filterBuilder: (ViewingStatsRangeBounds) -> StatsFilter,
        title: String?,
    ): ViewingStatsDetailSnapshot? {
        val earliest = dao.getEarliestRecordedAt() ?: return null
        val bounds = ViewingStatsRanges.bounds(range, now, earliest, timeZone)
        val filter = filterBuilder(bounds)
        return loadDetail(filter, range, timeZone, title)
    }

    private suspend fun loadDetail(
        filter: StatsFilter,
        range: ViewingStatsRange?,
        timeZone: TimeZone,
        title: String?,
    ): ViewingStatsDetailSnapshot {
        val buckets = if (range != null) {
            ViewingStatsRanges.timelineBuckets(
                range = range,
                bounds = ViewingStatsRangeBounds(filter.fromInclusive, filter.toExclusive),
                timeZone = timeZone,
            )
        } else {
            listOf(StatsTimelineBucketBounds(filter.fromInclusive, filter.toExclusive))
        }
        val overview = dao.getOverview(
            fromInclusive = filter.fromInclusive,
            toExclusive = filter.toExclusive,
            channelId = filter.channelId,
            categoryKey = filter.categoryKey,
            contentType = filter.contentType,
        )
        val timeline = dao.getTimeline(buildTimelineQuery(filter, buckets)).map { it.toTimelineTotal() }
        val patterns = loadPatternTotals(filter, timeZone)
        return ViewingStatsDetailSnapshot(
            title = title,
            filter = filter,
            totalWatchMs = overview.totalWatchMs,
            sessionCount = overview.sessionCount.toIntSafely(),
            averageSessionMs = if (overview.sessionCount > 0) overview.totalWatchMs / overview.sessionCount else 0L,
            lastWatchedAt = dao.getLastWatchedAt(
                filter.fromInclusive,
                filter.toExclusive,
                filter.channelId,
                filter.categoryKey,
                filter.contentType,
            ),
            timeline = timeline,
            topChannels = dao.getChannelTotals(
                filter.fromInclusive,
                filter.toExclusive,
                limit = DETAIL_LIMIT,
                channelId = filter.channelId,
                categoryKey = filter.categoryKey,
                contentType = filter.contentType,
            ).map { it.toChannelTotal() },
            topCategories = dao.getCategoryTotals(
                filter.fromInclusive,
                filter.toExclusive,
                limit = DETAIL_LIMIT,
                channelId = filter.channelId,
                categoryKey = filter.categoryKey,
                contentType = filter.contentType,
            ).map { it.toCategoryTotal() },
            contentTypes = dao.getContentTypeTotals(
                filter.fromInclusive,
                filter.toExclusive,
                channelId = filter.channelId,
                categoryKey = filter.categoryKey,
                contentType = filter.contentType,
            ).map { it.toContentTypeTotal() },
            recentIntervals = dao.getRecentIntervals(
                fromInclusive = filter.fromInclusive,
                toExclusive = filter.toExclusive,
                channelId = filter.channelId,
                categoryKey = filter.categoryKey,
                contentType = filter.contentType,
                limit = DETAIL_RECENT_LIMIT,
            )
                .asSequence()
                .mapNotNull { it.clipTo(filter) }
                .toList(),
            mostActiveWeekday = patterns.maxByOrNull { it.watchedMs }?.weekday,
            mostActiveTimeBucket = patterns.maxByOrNull { it.watchedMs }?.timeBucket,
        )
    }

    private suspend fun loadSnapshot(
        range: ViewingStatsRange,
        bounds: ViewingStatsRangeBounds,
        filter: StatsFilter,
        timeZone: TimeZone,
        topLimit: Int,
        earliestRecordedAt: Long,
    ): ViewingStatsSnapshot {
        val overview = dao.getOverview(
            fromInclusive = filter.fromInclusive,
            toExclusive = filter.toExclusive,
        )
        val timelineBounds = ViewingStatsRanges.timelineBuckets(range, bounds, timeZone)
        val timeline = if (timelineBounds.isEmpty()) {
            emptyList()
        } else {
            dao.getTimeline(buildTimelineQuery(filter, timelineBounds)).map { it.toTimelineTotal() }
        }
        // Daily metrics use the same overlap-aware SQL aggregation as the chart,
        // but in bounded chunks. All Time can span years, so putting every day
        // into one VALUES clause would eventually exceed SQLite's bind limit.
        val dailyBounds = ViewingStatsRanges.dailyBuckets(bounds, timeZone)
        val dailyTimeline = loadBucketTotals(
            filter = filter,
            buckets = dailyBounds,
            chunkSize = DAILY_QUERY_CHUNK_SIZE,
        )
        val patterns = loadPatternTotals(filter, timeZone)
        val topChannels = dao.getChannelTotals(
            fromInclusive = filter.fromInclusive,
            toExclusive = filter.toExclusive,
            limit = topLimit,
        ).map { it.toChannelTotal() }
        val topCategories = dao.getCategoryTotals(
            fromInclusive = filter.fromInclusive,
            toExclusive = filter.toExclusive,
            limit = topLimit,
        ).map { it.toCategoryTotal() }
        val contentTypes = dao.getContentTypeTotals(filter.fromInclusive, filter.toExclusive)
            .map { it.toContentTypeTotal() }
        val dailyTotals = dailyTimeline.map {
            DailyWatchTotal(dayStart = it.startAt, watchedMs = it.watchedMs)
        }
        val longestStreak = longestActiveDayStreak(dailyTotals)
        val activeDays = dailyTotals.count { it.watchedMs > 0L }
        return ViewingStatsSnapshot(
            range = range,
            bounds = bounds,
            hasActivity = overview.totalWatchMs > 0L,
            totalWatchMs = overview.totalWatchMs,
            previousTotalMs = null,
            comparisonPercent = null,
            sessionCount = overview.sessionCount.toIntSafely(),
            channelCount = overview.channelCount.toIntSafely(),
            categoryCount = overview.categoryCount.toIntSafely(),
            activeDays = activeDays,
            averageSessionMs = if (overview.sessionCount > 0) overview.totalWatchMs / overview.sessionCount else 0L,
            dailyTotals = dailyTotals,
            timeline = timeline,
            topChannels = topChannels,
            topCategories = topCategories,
            contentTypes = contentTypes,
            mostActiveWeekday = patterns.maxByOrNull { it.watchedMs }?.weekday,
            mostActiveTimeBucket = patterns.maxByOrNull { it.watchedMs }?.timeBucket,
            longestSessionMs = dao.getLongestSessionMs(filter.fromInclusive, filter.toExclusive),
            longestActiveDayStreak = longestStreak,
            peakDay = dailyTimeline.maxByOrNull { it.watchedMs }?.startAt,
            averageWatchPerActiveDay = if (activeDays > 0) overview.totalWatchMs / activeDays else 0L,
            lastWatchedAt = dao.getLastWatchedAt(filter.fromInclusive, filter.toExclusive),
            earliestRecordedAt = earliestRecordedAt,
        )
    }

    private fun buildTimelineQuery(
        filter: StatsFilter,
        buckets: List<StatsTimelineBucketBounds>,
    ): SimpleSQLiteQuery {
        val values = buckets.joinToString(",") { "(?, ?)" }
        val args = ArrayList<Any?>(2 + buckets.size * 2 + 3)
        args += filter.fromInclusive
        args += filter.toExclusive
        buckets.forEach { bucket ->
            args += bucket.startAt
            args += bucket.endAt
        }
        val extraConditions = buildList {
            filter.channelId?.let {
                add("AND i.channel_id = ?")
                args += it
            }
            filter.categoryKey?.let {
                add("AND COALESCE(NULLIF(i.category_id, ''), CASE WHEN i.category_name IS NOT NULL AND TRIM(i.category_name) != '' THEN 'name:' || LOWER(TRIM(i.category_name)) END) = ?")
                args += it
            }
            filter.contentType?.let {
                add("AND i.content_type = ?")
                args += it
            }
        }
        val sql = """
            WITH params AS (SELECT ? AS from_at, ? AS to_at),
            buckets(bucket_start, bucket_end) AS (VALUES $values)
            SELECT
                b.bucket_start AS bucket_start,
                b.bucket_end AS bucket_end,
                COALESCE(SUM(CASE
                    WHEN i.end_at <= i.start_at THEN i.watched_ms
                    ELSE CAST(ROUND(
                        i.watched_ms * 1.0 *
                        (MIN(i.end_at, b.bucket_end, p.to_at) - MAX(i.start_at, b.bucket_start, p.from_at)) /
                        NULLIF(i.end_at - i.start_at, 0)
                    ) AS INTEGER)
                END), 0) AS watched_ms,
                COUNT(DISTINCT i.session_id) AS session_count,
                COUNT(DISTINCT i.channel_id) AS channel_count
            FROM buckets b
            CROSS JOIN params p
            LEFT JOIN viewing_intervals i ON
                i.watched_ms > 0
                AND i.start_at < b.bucket_end
                AND i.end_at > b.bucket_start
                AND i.start_at < p.to_at
                AND i.end_at > p.from_at
                ${extraConditions.joinToString("\n                ")}
            GROUP BY b.bucket_start, b.bucket_end
            ORDER BY b.bucket_start ASC
        """.trimIndent()
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    /**
     * Runs explicit-boundary aggregation without allowing a long history to
     * produce an unbounded SQL statement. The boundaries remain explicit so
     * the calculation is correct for the caller's timezone and DST rules.
     */
    private suspend fun loadBucketTotals(
        filter: StatsFilter,
        buckets: List<StatsTimelineBucketBounds>,
        chunkSize: Int,
    ): List<TimelineWatchTotal> {
        if (buckets.isEmpty()) return emptyList()
        return buckets.chunked(chunkSize).flatMap { chunk ->
            dao.getTimeline(buildTimelineQuery(filter, chunk)).map { it.toTimelineTotal() }
        }
    }

    private suspend fun loadPatternTotals(
        filter: StatsFilter,
        timeZone: TimeZone,
    ): List<PatternWatchTotal> {
        val bounds = ViewingStatsRangeBounds(filter.fromInclusive, filter.toExclusive)
        val days = ViewingStatsRanges.dailyBuckets(bounds, timeZone)
        if (days.isEmpty()) return emptyList()

        val totals = LongArray(PATTERN_WEEKDAY_COUNT * PATTERN_TIME_BUCKET_COUNT)
        days.chunked(PATTERN_DAYS_PER_QUERY).forEach { dayChunk ->
            val buckets = dayChunk.flatMap { day ->
                ViewingStatsRanges.timeBucketsForLocalDay(day.startAt, timeZone)
            }
            dao.getTimeline(buildTimelineQuery(filter, buckets)).forEach { row ->
                val weekday = ViewingStatsMath.weekday(row.bucketStart, timeZone)
                val timeBucket = ViewingStatsMath.timeBucket(row.bucketStart, timeZone)
                val index = weekday * PATTERN_TIME_BUCKET_COUNT + timeBucket
                totals[index] = safeAdd(totals[index], row.watchedMs)
            }
        }
        return buildList {
            totals.forEachIndexed { index, watchedMs ->
                if (watchedMs > 0L) {
                    add(
                        PatternWatchTotal(
                            weekday = index / PATTERN_TIME_BUCKET_COUNT,
                            timeBucket = index % PATTERN_TIME_BUCKET_COUNT,
                            watchedMs = watchedMs,
                        )
                    )
                }
            }
        }
    }

    private fun safeAdd(left: Long, right: Long): Long {
        return if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }

    private fun longestActiveDayStreak(
        totals: List<DailyWatchTotal>,
    ): Int {
        if (totals.isEmpty()) return 0
        var current = 0
        var longest = 0
        totals.forEachIndexed { index, day ->
            if (day.watchedMs > 0L) {
                current += 1
                longest = maxOf(longest, current)
            } else if (index > 0) {
                current = 0
            }
        }
        return longest
    }

    private fun ViewingInterval.clipTo(filter: StatsFilter): ViewingInterval? {
        if (watchedMs <= 0L || endAt <= startAt) return null
        val clippedStart = maxOf(startAt, filter.fromInclusive)
        val clippedEnd = minOf(endAt, filter.toExclusive)
        if (clippedEnd <= clippedStart) return null
        val clippedWatchedMs = if (clippedStart == startAt && clippedEnd == endAt) {
            watchedMs
        } else {
            (watchedMs.toDouble() * (clippedEnd - clippedStart).toDouble() /
                    (endAt - startAt).toDouble()).roundToLong().coerceAtLeast(0L)
        }
        return copy(
            startAt = clippedStart,
            endAt = clippedEnd,
            watchedMs = clippedWatchedMs,
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
            categoryId = categoryId,
            categoryName = categoryName,
            categoryImage = categoryImage,
            contentType = contentType,
            contentId = contentId,
            streamTitle = title,
            startAt = startAt,
            endAt = startAt,
            watchedMs = 0L,
            lastCheckpointAt = startAt,
        )
    }

    companion object {
        const val DEFAULT_TOP_LIMIT = 5
        private const val DETAIL_LIMIT = 20
        private const val DETAIL_RECENT_LIMIT = 50
        private const val DAILY_QUERY_CHUNK_SIZE = 90
        private const val PATTERN_DAYS_PER_QUERY = 30
        private const val PATTERN_WEEKDAY_COUNT = 7
        private const val PATTERN_TIME_BUCKET_COUNT = ViewingStatsRanges.TIME_BUCKET_COUNT
    }
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
    val categoryCount: Int,
    val activeDays: Int,
    val averageSessionMs: Long,
    val dailyTotals: List<DailyWatchTotal>,
    val timeline: List<TimelineWatchTotal>,
    val topChannels: List<ChannelWatchTotal>,
    val topCategories: List<CategoryWatchTotal>,
    val contentTypes: List<ContentTypeWatchTotal>,
    val mostActiveWeekday: Int?,
    val mostActiveTimeBucket: Int?,
    val longestSessionMs: Long,
    val longestActiveDayStreak: Int,
    val peakDay: Long?,
    val averageWatchPerActiveDay: Long,
    val lastWatchedAt: Long?,
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
                categoryCount = 0,
                activeDays = 0,
                averageSessionMs = 0L,
                dailyTotals = emptyList(),
                timeline = emptyList(),
                topChannels = emptyList(),
                topCategories = emptyList(),
                contentTypes = emptyList(),
                mostActiveWeekday = null,
                mostActiveTimeBucket = null,
                longestSessionMs = 0L,
                longestActiveDayStreak = 0,
                peakDay = null,
                averageWatchPerActiveDay = 0L,
                lastWatchedAt = null,
                earliestRecordedAt = earliestRecordedAt,
            )
        }
    }
}

data class ViewingStatsDetailSnapshot(
    val title: String?,
    val filter: StatsFilter,
    val totalWatchMs: Long,
    val sessionCount: Int,
    val averageSessionMs: Long,
    val lastWatchedAt: Long?,
    val timeline: List<TimelineWatchTotal>,
    val topChannels: List<ChannelWatchTotal>,
    val topCategories: List<CategoryWatchTotal>,
    val contentTypes: List<ContentTypeWatchTotal>,
    val recentIntervals: List<ViewingInterval>,
    val mostActiveWeekday: Int?,
    val mostActiveTimeBucket: Int?,
)

private fun com.github.andreyasadchy.xtra.db.ViewingStatsChannelRow.toChannelTotal() = ChannelWatchTotal(
    channelId = channelId,
    channelLogin = channelLogin,
    channelName = channelName,
    channelImage = channelImage,
    watchedMs = watchedMs,
    sessionCount = sessionCount.toIntSafely(),
)

private fun com.github.andreyasadchy.xtra.db.ViewingStatsCategoryRow.toCategoryTotal() = CategoryWatchTotal(
    categoryKey = categoryKey,
    categoryId = categoryId,
    categoryName = categoryName,
    categoryImage = categoryImage,
    watchedMs = watchedMs,
    sessionCount = sessionCount.toIntSafely(),
)

private fun com.github.andreyasadchy.xtra.db.ViewingStatsContentTypeRow.toContentTypeTotal() = ContentTypeWatchTotal(
    contentType = contentType,
    watchedMs = watchedMs,
    sessionCount = sessionCount.toIntSafely(),
)

private fun ViewingStatsTimelineRow.toTimelineTotal() = TimelineWatchTotal(
    startAt = bucketStart,
    endAt = bucketEnd,
    watchedMs = watchedMs,
    sessionCount = sessionCount.toIntSafely(),
    channelCount = channelCount.toIntSafely(),
)

private fun Long.toIntSafely(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
