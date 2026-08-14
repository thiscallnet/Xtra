package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.github.andreyasadchy.xtra.model.stats.ViewingInterval
import com.github.andreyasadchy.xtra.model.stats.ViewingSession

@Dao
interface ViewingStatsDao {

    @Insert
    suspend fun insertSession(session: ViewingSession): Long

    @Update
    suspend fun updateSession(session: ViewingSession)

    @Insert
    suspend fun insertInterval(interval: ViewingInterval): Long

    @Update
    suspend fun updateInterval(interval: ViewingInterval)

    @Query(
        "SELECT * FROM viewing_intervals " +
                "WHERE watched_ms > 0 AND start_at < :toExclusive AND end_at > :fromInclusive " +
                "ORDER BY start_at ASC"
    )
    suspend fun getIntervals(fromInclusive: Long, toExclusive: Long): List<ViewingInterval>

    @Query(
        "SELECT COUNT(DISTINCT session_id) FROM viewing_intervals " +
                "WHERE watched_ms > 0 AND start_at < :toExclusive AND end_at > :fromInclusive"
    )
    suspend fun getSessionCount(fromInclusive: Long, toExclusive: Long): Int

    @Query(
        "SELECT COUNT(DISTINCT channel_id) FROM viewing_intervals " +
                "WHERE watched_ms > 0 AND start_at < :toExclusive AND end_at > :fromInclusive"
    )
    suspend fun getChannelCount(fromInclusive: Long, toExclusive: Long): Int

    @Query("SELECT MIN(started_at) FROM viewing_sessions WHERE watched_ms > 0")
    suspend fun getEarliestRecordedAt(): Long?

    /** These unbounded aggregates avoid loading the whole interval total in Java for All Time. */
    @Query("SELECT COALESCE(SUM(watched_ms), 0) FROM viewing_intervals WHERE watched_ms > 0")
    suspend fun getAllTimeWatchedMs(): Long

    @Query("SELECT COALESCE(MAX(watched_ms), 0) FROM viewing_sessions WHERE watched_ms > 0")
    suspend fun getAllTimeLongestSessionMs(): Long

    @Query("DELETE FROM viewing_intervals")
    suspend fun deleteAllIntervals()

    @Query("DELETE FROM viewing_sessions")
    suspend fun deleteAllSessions()

    @Transaction
    suspend fun deleteAll() {
        deleteAllIntervals()
        deleteAllSessions()
    }
}
