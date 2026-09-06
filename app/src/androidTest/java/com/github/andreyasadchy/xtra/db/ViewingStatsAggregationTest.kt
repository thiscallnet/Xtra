package com.github.andreyasadchy.xtra.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.model.stats.ViewingInterval
import com.github.andreyasadchy.xtra.model.stats.ViewingSession
import com.github.andreyasadchy.xtra.repository.ViewingStatsRepository
import com.github.andreyasadchy.xtra.util.viewingstats.ViewingStatsMath
import com.github.andreyasadchy.xtra.util.viewingstats.ViewingStatsRange
import com.github.andreyasadchy.xtra.util.viewingstats.ViewingStatsRanges
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewingStatsAggregationTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: ViewingStatsRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
        repository = ViewingStatsRepository(database.viewingStats())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun aggregatesIntervalsIntoChannelsCategoriesAndContentTypes() = runBlocking {
        val now = 1_720_000_000_000L
        val sessionId = database.viewingStats().insertSession(
            ViewingSession(
                channelId = "channel-a",
                channelLogin = "channel-a",
                channelName = "Channel A",
                channelImage = null,
                contentType = "live",
                contentId = "stream-1",
                startedAt = now - 2 * HOUR,
                endedAt = now - HOUR,
                watchedMs = HOUR,
                lastCheckpointAt = now - HOUR,
            ),
        )
        database.viewingStats().insertInterval(
            ViewingInterval(
                sessionId = sessionId,
                channelId = "channel-a",
                channelLogin = "channel-a",
                channelName = "Channel A",
                channelImage = null,
                categoryId = "game-1",
                categoryName = "Game One",
                contentType = "live",
                contentId = "stream-1",
                startAt = now - 2 * HOUR,
                endAt = now - HOUR,
                watchedMs = HOUR,
                lastCheckpointAt = now - HOUR,
            ),
        )

        val snapshot = repository.loadStatistics(
            range = ViewingStatsRange.LAST_7_DAYS,
            now = now,
            timeZone = TimeZone.getTimeZone("UTC"),
        )

        assertEquals(HOUR, snapshot.totalWatchMs)
        assertEquals(1, snapshot.sessionCount)
        assertEquals(1, snapshot.channelCount)
        assertEquals(1, snapshot.categoryCount)
        assertEquals(1, snapshot.topChannels.size)
        assertEquals(1, snapshot.topCategories.size)
        assertEquals(HOUR, snapshot.topChannels.single().watchedMs)
        assertEquals(HOUR, snapshot.topCategories.single().watchedMs)
        assertEquals("live", snapshot.contentTypes.single().contentType)
        assertTrue(snapshot.timeline.any { it.watchedMs == HOUR })
    }

    @Test
    fun overlapBoundariesRemainHalfOpenAndUnfilteredFastPathMatches() = runBlocking {
        suspend fun insertBoundaryInterval(startAt: Long, endAt: Long, watchedMs: Long) {
            val sessionId = insertSession(
                channelId = "boundary-$startAt",
                startedAt = startAt,
                endedAt = endAt,
                watchedMs = watchedMs,
            )
            database.viewingStats().insertInterval(
                ViewingInterval(
                    sessionId = sessionId,
                    channelId = "boundary-$startAt",
                    channelLogin = null,
                    channelName = null,
                    channelImage = null,
                    contentType = "live",
                    startAt = startAt,
                    endAt = endAt,
                    watchedMs = watchedMs,
                    lastCheckpointAt = endAt,
                ),
            )
        }

        insertBoundaryInterval(0L, 100L, 100L)
        insertBoundaryInterval(100L, 200L, 100L)
        insertBoundaryInterval(200L, 300L, 100L)
        insertBoundaryInterval(50L, 250L, 200L)
        insertBoundaryInterval(199L, 201L, 2L)

        val dao = database.viewingStats()
        val overview = dao.getOverview(100L, 200L)

        assertEquals(201L, overview.totalWatchMs)
        assertEquals(overview, dao.getUnfilteredOverview(100L, 200L))
        assertEquals(201L, dao.getUnfilteredTotalWatchMs(100L, 200L))
    }

    @Test
    fun channelCategoryAndContentFiltersKeepTheirExistingResults() = runBlocking {
        val firstSession = insertSession("channel-a", 0L, 100L, 100L)
        val secondSession = insertSession("channel-b", 0L, 100L, 100L)
        database.viewingStats().insertInterval(
            interval(
                sessionId = firstSession,
                startAt = 0L,
                endAt = 100L,
                channelName = "Channel A",
                watchedMs = 100L,
            ).copy(categoryId = null, categoryName = "Game A", contentType = "live"),
        )
        database.viewingStats().insertInterval(
            interval(
                sessionId = secondSession,
                startAt = 0L,
                endAt = 100L,
                channelName = "Channel B",
                watchedMs = 100L,
            ).copy(
                channelId = "channel-b",
                channelLogin = "channel-b",
                categoryId = "game-b",
                categoryName = "Game B",
                contentType = "vod",
            ),
        )

        val dao = database.viewingStats()
        assertEquals(200L, dao.getOverview(0L, 100L).totalWatchMs)
        assertEquals(100L, dao.getOverview(0L, 100L, channelId = "channel-a").totalWatchMs)
        assertEquals(100L, dao.getOverview(0L, 100L, categoryKey = "name:game a").totalWatchMs)
        assertEquals(100L, dao.getOverview(0L, 100L, contentType = "vod").totalWatchMs)
    }

    @Test
    fun emptyHistoryRetainsTheEmptySnapshot() = runBlocking {
        val snapshot = repository.loadStatistics(
            range = ViewingStatsRange.LAST_7_DAYS,
            now = 1_720_000_000_000L,
            timeZone = TimeZone.getTimeZone("UTC"),
        )

        assertEquals(0L, snapshot.totalWatchMs)
        assertEquals(0, snapshot.sessionCount)
        assertTrue(snapshot.timeline.isEmpty())
        assertTrue(snapshot.topChannels.isEmpty())
    }

    @Test
    fun selectedRangeClipsAnIntervalWithoutLoadingRawHistoryIntoRepository() = runBlocking {
        val now = 1_720_000_000_000L
        val sessionId = database.viewingStats().insertSession(
            ViewingSession(
                channelId = "channel-a",
                contentType = "live",
                startedAt = now - 10 * DAY,
                endedAt = now,
                watchedMs = 10 * DAY,
                lastCheckpointAt = now,
                channelLogin = null,
                channelName = null,
                channelImage = null,
                contentId = null,
            ),
        )
        database.viewingStats().insertInterval(
            ViewingInterval(
                sessionId = sessionId,
                channelId = "channel-a",
                channelLogin = null,
                channelName = null,
                channelImage = null,
                contentType = "live",
                startAt = now - 10 * DAY,
                endAt = now,
                watchedMs = 10 * DAY,
                lastCheckpointAt = now,
            ),
        )

        val snapshot = repository.loadStatistics(
            range = ViewingStatsRange.LAST_7_DAYS,
            now = now,
            timeZone = TimeZone.getTimeZone("UTC"),
        )

        val bounds = ViewingStatsRanges.bounds(
            range = ViewingStatsRange.LAST_7_DAYS,
            now = now,
            earliestRecordedAt = now - 10 * DAY,
            timeZone = TimeZone.getTimeZone("UTC"),
        )
        assertEquals(bounds.toExclusive - bounds.fromInclusive, snapshot.totalWatchMs)
    }

    @Test
    fun patternAggregationSplitsLongIntervalsAcrossLocalBuckets() = runBlocking {
        val zone = TimeZone.getTimeZone("UTC")
        val start = timestamp(2024, Calendar.JANUARY, 1, 20, 50, zone)
        val end = timestamp(2024, Calendar.JANUARY, 1, 23, 10, zone)
        val sessionId = insertSession(
            channelId = "channel-pattern",
            startedAt = start,
            endedAt = end,
            watchedMs = end - start,
        )
        database.viewingStats().insertInterval(
            ViewingInterval(
                sessionId = sessionId,
                channelId = "channel-pattern",
                channelLogin = "channel-pattern",
                channelName = "Pattern",
                channelImage = null,
                contentType = "live",
                startAt = start,
                endAt = end,
                watchedMs = end - start,
                lastCheckpointAt = end,
            ),
        )

        val snapshot = repository.loadStatistics(
            range = ViewingStatsRange.ALL_TIME,
            now = timestamp(2024, Calendar.JANUARY, 2, 1, 0, zone),
            timeZone = zone,
        )

        assertEquals(7, snapshot.mostActiveTimeBucket)
    }

    @Test
    fun repositoryPatternAggregationUsesLocalClockBoundariesAcrossSpringForward() = runBlocking {
        val zone = TimeZone.getTimeZone("America/New_York")
        val start = timestamp(2024, Calendar.MARCH, 9, 0, 0, zone)
        val end = ViewingStatsRanges.addDays(start, 2, zone)
        val sessionId = insertSession("channel-spring", start, end, end - start)
        database.viewingStats().insertInterval(
            interval(sessionId, start, end, "Spring", watchedMs = end - start),
        )

        val patterns = repository.loadPatternTotals(start, end, zone)
        val sunday = ViewingStatsMath.weekday(
            ViewingStatsRanges.addDays(start, 1, zone),
            zone,
        )
        assertEquals(end - start, patterns.sumOf { it.watchedMs })
        assertEquals(2 * HOUR, patterns.single { it.weekday == sunday && it.timeBucket == 0 }.watchedMs)
        assertEquals(3 * HOUR, patterns.single { it.weekday == sunday && it.timeBucket == 1 }.watchedMs)

        val snapshot = repository.loadStatistics(ViewingStatsRange.ALL_TIME, end, zone)
        assertEquals(end - start, snapshot.totalWatchMs)
        assertEquals(snapshot.totalWatchMs, patterns.sumOf { it.watchedMs })
    }

    @Test
    fun repositoryPatternAggregationUsesLocalClockBoundariesAcrossFallBack() = runBlocking {
        val zone = TimeZone.getTimeZone("America/New_York")
        val start = timestamp(2024, Calendar.NOVEMBER, 2, 0, 0, zone)
        val end = ViewingStatsRanges.addDays(start, 2, zone)
        val sessionId = insertSession("channel-fall", start, end, end - start)
        database.viewingStats().insertInterval(
            interval(sessionId, start, end, "Fall", watchedMs = end - start),
        )

        val patterns = repository.loadPatternTotals(start, end, zone)
        val sunday = ViewingStatsMath.weekday(
            ViewingStatsRanges.addDays(start, 1, zone),
            zone,
        )
        assertEquals(end - start, patterns.sumOf { it.watchedMs })
        assertEquals(4 * HOUR, patterns.single { it.weekday == sunday && it.timeBucket == 0 }.watchedMs)
        assertEquals(3 * HOUR, patterns.single { it.weekday == sunday && it.timeBucket == 1 }.watchedMs)

        val snapshot = repository.loadStatistics(ViewingStatsRange.ALL_TIME, end, zone)
        assertEquals(end - start, snapshot.totalWatchMs)
        assertEquals(snapshot.totalWatchMs, patterns.sumOf { it.watchedMs })
    }

    @Test
    fun aggregatePresentationMetadataComesFromNewestInterval() = runBlocking {
        val oldStart = 1_720_000_000_000L - 2 * HOUR
        val newStart = 1_720_000_000_000L - HOUR
        val oldSession = insertSession("channel-a", oldStart, oldStart + HOUR, HOUR)
        val newSession = insertSession("channel-a", newStart, newStart + HOUR, HOUR)
        database.viewingStats().insertInterval(interval(oldSession, oldStart, oldStart + HOUR, "zOld name"))
        database.viewingStats().insertInterval(interval(newSession, newStart, newStart + HOUR, "New name"))

        val channels = database.viewingStats().getChannelTotals(
            fromInclusive = oldStart,
            toExclusive = newStart + HOUR,
            limit = 5,
        )

        assertEquals("New name", channels.single().channelName)
    }

    @Test
    fun allTimeHistorySpanningFiveYearsDoesNotRequireOneUnboundedDailyQuery() = runBlocking {
        val zone = TimeZone.getTimeZone("UTC")
        val first = Calendar.getInstance(zone).apply {
            clear()
            set(2019, Calendar.JANUARY, 1, 12, 0, 0)
        }
        repeat(60) { month ->
            val start = Calendar.getInstance(zone).apply {
                timeInMillis = first.timeInMillis
                add(Calendar.MONTH, month)
            }.timeInMillis
            val end = start + MINUTE
            val sessionId = insertSession("channel-$month", start, end, MINUTE)
            database.viewingStats().insertInterval(
                ViewingInterval(
                    sessionId = sessionId,
                    channelId = "channel-$month",
                    channelLogin = "channel-$month",
                    channelName = "Channel $month",
                    channelImage = null,
                    categoryId = "game-$month",
                    categoryName = "Game $month",
                    contentType = "live",
                    startAt = start,
                    endAt = end,
                    watchedMs = MINUTE,
                    lastCheckpointAt = end,
                ),
            )
        }

        val snapshot = repository.loadStatistics(
            range = ViewingStatsRange.ALL_TIME,
            now = timestamp(2024, Calendar.JULY, 1, 0, 0, zone),
            timeZone = zone,
        )

        assertEquals(60 * MINUTE, snapshot.totalWatchMs)
        assertTrue(snapshot.dailyTotals.size >= 1_800)
        assertEquals(60, snapshot.activeDays)
    }

    private suspend fun insertSession(
        channelId: String,
        startedAt: Long,
        endedAt: Long,
        watchedMs: Long,
    ): Long = database.viewingStats().insertSession(
        ViewingSession(
            channelId = channelId,
            channelLogin = channelId,
            channelName = channelId,
            channelImage = null,
            contentType = "live",
            contentId = channelId,
            startedAt = startedAt,
            endedAt = endedAt,
            watchedMs = watchedMs,
            lastCheckpointAt = endedAt,
        ),
    )

    private fun interval(
        sessionId: Long,
        startAt: Long,
        endAt: Long,
        channelName: String,
        watchedMs: Long = endAt - startAt,
    ) = ViewingInterval(
        sessionId = sessionId,
        channelId = "channel-a",
        channelLogin = "channel-a",
        channelName = channelName,
        channelImage = null,
        contentType = "live",
        contentId = "stream-a",
        startAt = startAt,
        endAt = endAt,
        watchedMs = watchedMs,
        lastCheckpointAt = endAt,
    )

    private fun timestamp(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: TimeZone): Long {
        return Calendar.getInstance(zone).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis
    }

    private companion object {
        const val HOUR = 60 * 60 * 1_000L
        const val DAY = 24 * HOUR
        const val MINUTE = 60 * 1_000L
    }
}
