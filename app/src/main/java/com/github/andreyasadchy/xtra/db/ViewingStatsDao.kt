package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import com.github.andreyasadchy.xtra.model.stats.ViewingInterval
import com.github.andreyasadchy.xtra.model.stats.ViewingSession
import kotlinx.coroutines.flow.Flow

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

    @Transaction
    suspend fun updateCheckpoints(
        intervals: List<ViewingInterval>,
        sessions: List<ViewingSession>,
    ) {
        intervals.forEach { updateInterval(it) }
        sessions.forEach { updateSession(it) }
    }

    @Query(
        "SELECT * FROM viewing_intervals " +
                "WHERE watched_ms > 0 AND start_at < :toExclusive AND end_at > :fromInclusive " +
                "AND (:channelId IS NULL OR channel_id = :channelId) " +
                "AND (:categoryKey IS NULL OR COALESCE(NULLIF(category_id, ''), " +
                "CASE WHEN category_name IS NOT NULL AND TRIM(category_name) != '' " +
                "THEN 'name:' || LOWER(TRIM(category_name)) END) = :categoryKey) " +
                "AND (:contentType IS NULL OR content_type = :contentType) " +
                "ORDER BY start_at DESC, id DESC LIMIT :limit"
    )
    suspend fun getRecentIntervals(
        fromInclusive: Long,
        toExclusive: Long,
        channelId: String? = null,
        categoryKey: String? = null,
        contentType: String? = null,
        limit: Int,
    ): List<ViewingInterval>

    @Query("SELECT MIN(started_at) FROM viewing_sessions WHERE watched_ms > 0")
    suspend fun getEarliestRecordedAt(): Long?

    @Query("SELECT MAX(last_checkpoint_at) FROM viewing_intervals")
    fun observeLastCheckpointAt(): Flow<Long?>

    @Query(
        """
        SELECT COALESCE(SUM(CASE
            WHEN end_at <= start_at THEN watched_ms
            ELSE CAST(ROUND(
                watched_ms * 1.0 *
                (MIN(end_at, :toExclusive) - MAX(start_at, :fromInclusive)) /
                NULLIF(end_at - start_at, 0)
            ) AS INTEGER)
        END), 0)
        FROM viewing_intervals
        WHERE watched_ms > 0
          AND start_at < :toExclusive
          AND end_at > :fromInclusive
          AND (:channelId IS NULL OR channel_id = :channelId)
          AND (:categoryKey IS NULL OR COALESCE(
                NULLIF(category_id, ''),
                CASE
                    WHEN category_name IS NOT NULL AND TRIM(category_name) != ''
                    THEN 'name:' || LOWER(TRIM(category_name))
                END
              ) = :categoryKey)
          AND (:contentType IS NULL OR content_type = :contentType)
        """
    )
    suspend fun getTotalWatchMs(
        fromInclusive: Long,
        toExclusive: Long,
        channelId: String? = null,
        categoryKey: String? = null,
        contentType: String? = null,
    ): Long

    @Query(
        """
        SELECT COALESCE(SUM(CASE
            WHEN end_at <= start_at THEN watched_ms
            ELSE CAST(ROUND(
                watched_ms * 1.0 *
                (MIN(end_at, :toExclusive) - MAX(start_at, :fromInclusive)) /
                NULLIF(end_at - start_at, 0)
            ) AS INTEGER)
        END), 0)
        FROM viewing_intervals
        WHERE watched_ms > 0
          AND start_at < :toExclusive
          AND end_at > :fromInclusive
        """
    )
    suspend fun getUnfilteredTotalWatchMs(
        fromInclusive: Long,
        toExclusive: Long,
    ): Long

    @Query(
        """
        WITH filtered AS (
            SELECT
                session_id,
                channel_id,
                category_id,
                category_name,
                content_type,
                CASE
                    WHEN end_at <= start_at THEN watched_ms
                    ELSE CAST(ROUND(
                        watched_ms * 1.0 *
                        (MIN(end_at, :toExclusive) - MAX(start_at, :fromInclusive)) /
                        NULLIF(end_at - start_at, 0)
                    ) AS INTEGER)
                END AS clipped_watched_ms,
                COALESCE(
                    NULLIF(category_id, ''),
                    CASE
                        WHEN category_name IS NOT NULL AND TRIM(category_name) != ''
                        THEN 'name:' || LOWER(TRIM(category_name))
                    END
                ) AS category_key
            FROM viewing_intervals
            WHERE watched_ms > 0
              AND start_at < :toExclusive
              AND end_at > :fromInclusive
              AND (:channelId IS NULL OR channel_id = :channelId)
              AND (:categoryKey IS NULL OR COALESCE(
                    NULLIF(category_id, ''),
                    CASE
                        WHEN category_name IS NOT NULL AND TRIM(category_name) != ''
                        THEN 'name:' || LOWER(TRIM(category_name))
                    END
                  ) = :categoryKey)
              AND (:contentType IS NULL OR content_type = :contentType)
        )
        SELECT
            COALESCE(SUM(clipped_watched_ms), 0) AS total_watch_ms,
            COUNT(DISTINCT session_id) AS session_count,
            COUNT(DISTINCT channel_id) AS channel_count,
            COUNT(DISTINCT CASE WHEN category_key IS NOT NULL THEN category_key END) AS category_count
        FROM filtered
        """
    )
    suspend fun getOverview(
        fromInclusive: Long,
        toExclusive: Long,
        channelId: String? = null,
        categoryKey: String? = null,
        contentType: String? = null,
    ): ViewingStatsOverviewRow

    @Query(
        """
        WITH filtered AS (
            SELECT
                session_id,
                channel_id,
                category_id,
                category_name,
                content_type,
                CASE
                    WHEN end_at <= start_at THEN watched_ms
                    ELSE CAST(ROUND(
                        watched_ms * 1.0 *
                        (MIN(end_at, :toExclusive) - MAX(start_at, :fromInclusive)) /
                        NULLIF(end_at - start_at, 0)
                    ) AS INTEGER)
                END AS clipped_watched_ms,
                COALESCE(
                    NULLIF(category_id, ''),
                    CASE
                        WHEN category_name IS NOT NULL AND TRIM(category_name) != ''
                        THEN 'name:' || LOWER(TRIM(category_name))
                    END
                ) AS category_key
            FROM viewing_intervals
            WHERE watched_ms > 0
              AND start_at < :toExclusive
              AND end_at > :fromInclusive
        )
        SELECT
            COALESCE(SUM(clipped_watched_ms), 0) AS total_watch_ms,
            COUNT(DISTINCT session_id) AS session_count,
            COUNT(DISTINCT channel_id) AS channel_count,
            COUNT(DISTINCT CASE WHEN category_key IS NOT NULL THEN category_key END) AS category_count
        FROM filtered
        """
    )
    suspend fun getUnfilteredOverview(
        fromInclusive: Long,
        toExclusive: Long,
    ): ViewingStatsOverviewRow

    @Query(
        """
        WITH filtered AS (
            SELECT
                session_id,
                CASE
                    WHEN end_at <= start_at THEN watched_ms
                    ELSE CAST(ROUND(
                        watched_ms * 1.0 *
                        (MIN(end_at, :toExclusive) - MAX(start_at, :fromInclusive)) /
                        NULLIF(end_at - start_at, 0)
                    ) AS INTEGER)
                END AS clipped_watched_ms
            FROM viewing_intervals
            WHERE watched_ms > 0
              AND start_at < :toExclusive
              AND end_at > :fromInclusive
              AND (:channelId IS NULL OR channel_id = :channelId)
              AND (:categoryKey IS NULL OR COALESCE(
                    NULLIF(category_id, ''),
                    CASE
                        WHEN category_name IS NOT NULL AND TRIM(category_name) != ''
                        THEN 'name:' || LOWER(TRIM(category_name))
                    END
                  ) = :categoryKey)
              AND (:contentType IS NULL OR content_type = :contentType)
        ), sessions AS (
            SELECT session_id, SUM(clipped_watched_ms) AS watched_ms
            FROM filtered
            GROUP BY session_id
        )
        SELECT COALESCE(MAX(watched_ms), 0)
        FROM sessions
        """
    )
    suspend fun getLongestSessionMs(
        fromInclusive: Long,
        toExclusive: Long,
        channelId: String? = null,
        categoryKey: String? = null,
        contentType: String? = null,
    ): Long

    @Query(
        """
        SELECT MAX(MIN(end_at, :toExclusive))
        FROM viewing_intervals
        WHERE watched_ms > 0
          AND start_at < :toExclusive
          AND end_at > :fromInclusive
          AND (:channelId IS NULL OR channel_id = :channelId)
          AND (:categoryKey IS NULL OR COALESCE(
                NULLIF(category_id, ''),
                CASE
                    WHEN category_name IS NOT NULL AND TRIM(category_name) != ''
                    THEN 'name:' || LOWER(TRIM(category_name))
                END
              ) = :categoryKey)
          AND (:contentType IS NULL OR content_type = :contentType)
        """
    )
    suspend fun getLastWatchedAt(
        fromInclusive: Long,
        toExclusive: Long,
        channelId: String? = null,
        categoryKey: String? = null,
        contentType: String? = null,
    ): Long?

    @Query(
        """
        WITH filtered AS (
            SELECT
                id,
                session_id,
                channel_id,
                channel_login,
                channel_name,
                channel_image,
                end_at,
                CASE
                    WHEN end_at <= start_at THEN watched_ms
                    ELSE CAST(ROUND(
                        watched_ms * 1.0 *
                        (MIN(end_at, :toExclusive) - MAX(start_at, :fromInclusive)) /
                        NULLIF(end_at - start_at, 0)
                    ) AS INTEGER)
                END AS clipped_watched_ms
            FROM viewing_intervals
            WHERE watched_ms > 0
              AND start_at < :toExclusive
              AND end_at > :fromInclusive
              AND (:channelId IS NULL OR channel_id = :channelId)
              AND (:categoryKey IS NULL OR COALESCE(
                    NULLIF(category_id, ''),
                    CASE
                        WHEN category_name IS NOT NULL AND TRIM(category_name) != ''
                        THEN 'name:' || LOWER(TRIM(category_name))
                    END
                  ) = :categoryKey)
              AND (:contentType IS NULL OR content_type = :contentType)
        ), totals AS (
            SELECT
                channel_id,
                SUM(clipped_watched_ms) AS watched_ms,
                COUNT(DISTINCT session_id) AS session_count
            FROM filtered
            GROUP BY channel_id
        )
        SELECT
            totals.channel_id,
            (SELECT f.channel_login FROM filtered f
                WHERE f.channel_id = totals.channel_id
                ORDER BY f.end_at DESC, f.id DESC LIMIT 1) AS channel_login,
            (SELECT f.channel_name FROM filtered f
                WHERE f.channel_id = totals.channel_id
                ORDER BY f.end_at DESC, f.id DESC LIMIT 1) AS channel_name,
            (SELECT f.channel_image FROM filtered f
                WHERE f.channel_id = totals.channel_id
                ORDER BY f.end_at DESC, f.id DESC LIMIT 1) AS channel_image,
            totals.watched_ms,
            totals.session_count
        FROM totals
        ORDER BY totals.watched_ms DESC
        LIMIT :limit
        """
    )
    suspend fun getChannelTotals(
        fromInclusive: Long,
        toExclusive: Long,
        limit: Int,
        channelId: String? = null,
        categoryKey: String? = null,
        contentType: String? = null,
    ): List<ViewingStatsChannelRow>

    @Query(
        """
        WITH filtered AS (
            SELECT
                id,
                session_id,
                COALESCE(
                    NULLIF(category_id, ''),
                    CASE
                        WHEN category_name IS NOT NULL AND TRIM(category_name) != ''
                        THEN 'name:' || LOWER(TRIM(category_name))
                    END
                ) AS category_key,
                category_id,
                category_name,
                category_image,
                end_at,
                CASE
                    WHEN end_at <= start_at THEN watched_ms
                    ELSE CAST(ROUND(
                        watched_ms * 1.0 *
                        (MIN(end_at, :toExclusive) - MAX(start_at, :fromInclusive)) /
                        NULLIF(end_at - start_at, 0)
                    ) AS INTEGER)
                END AS clipped_watched_ms
            FROM viewing_intervals
            WHERE watched_ms > 0
              AND start_at < :toExclusive
              AND end_at > :fromInclusive
              AND (:channelId IS NULL OR channel_id = :channelId)
              AND (:categoryKey IS NULL OR COALESCE(
                    NULLIF(category_id, ''),
                    CASE
                        WHEN category_name IS NOT NULL AND TRIM(category_name) != ''
                        THEN 'name:' || LOWER(TRIM(category_name))
                    END
                  ) = :categoryKey)
              AND (:contentType IS NULL OR content_type = :contentType)
        ), totals AS (
            SELECT
                category_key,
                SUM(clipped_watched_ms) AS watched_ms,
                COUNT(DISTINCT session_id) AS session_count
            FROM filtered
            WHERE category_key IS NOT NULL
            GROUP BY category_key
        )
        SELECT
            totals.category_key,
            (SELECT f.category_id FROM filtered f
                WHERE f.category_key = totals.category_key
                ORDER BY f.end_at DESC, f.id DESC LIMIT 1) AS category_id,
            (SELECT f.category_name FROM filtered f
                WHERE f.category_key = totals.category_key
                ORDER BY f.end_at DESC, f.id DESC LIMIT 1) AS category_name,
            (SELECT f.category_image FROM filtered f
                WHERE f.category_key = totals.category_key
                ORDER BY f.end_at DESC, f.id DESC LIMIT 1) AS category_image,
            totals.watched_ms,
            totals.session_count
        FROM totals
        ORDER BY totals.watched_ms DESC
        LIMIT :limit
        """
    )
    suspend fun getCategoryTotals(
        fromInclusive: Long,
        toExclusive: Long,
        limit: Int,
        channelId: String? = null,
        categoryKey: String? = null,
        contentType: String? = null,
    ): List<ViewingStatsCategoryRow>

    @Query(
        """
        SELECT
            content_type,
            SUM(CASE
                WHEN end_at <= start_at THEN watched_ms
                ELSE CAST(ROUND(
                    watched_ms * 1.0 *
                    (MIN(end_at, :toExclusive) - MAX(start_at, :fromInclusive)) /
                    NULLIF(end_at - start_at, 0)
                ) AS INTEGER)
            END) AS watched_ms,
            COUNT(DISTINCT session_id) AS session_count
        FROM viewing_intervals
        WHERE watched_ms > 0
          AND start_at < :toExclusive
          AND end_at > :fromInclusive
          AND (:channelId IS NULL OR channel_id = :channelId)
          AND (:categoryKey IS NULL OR COALESCE(
                NULLIF(category_id, ''),
                CASE
                    WHEN category_name IS NOT NULL AND TRIM(category_name) != ''
                    THEN 'name:' || LOWER(TRIM(category_name))
                END
              ) = :categoryKey)
          AND (:contentType IS NULL OR content_type = :contentType)
        GROUP BY content_type
        ORDER BY watched_ms DESC
        """
    )
    suspend fun getContentTypeTotals(
        fromInclusive: Long,
        toExclusive: Long,
        channelId: String? = null,
        categoryKey: String? = null,
        contentType: String? = null,
    ): List<ViewingStatsContentTypeRow>

    @RawQuery(observedEntities = [ViewingInterval::class])
    suspend fun getTimeline(query: SupportSQLiteQuery): List<ViewingStatsTimelineRow>

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

data class ViewingStatsOverviewRow(
    @ColumnInfo(name = "total_watch_ms")
    val totalWatchMs: Long,
    @ColumnInfo(name = "session_count")
    val sessionCount: Long,
    @ColumnInfo(name = "channel_count")
    val channelCount: Long,
    @ColumnInfo(name = "category_count")
    val categoryCount: Long,
)

data class ViewingStatsChannelRow(
    @ColumnInfo(name = "channel_id")
    val channelId: String,
    @ColumnInfo(name = "channel_login")
    val channelLogin: String?,
    @ColumnInfo(name = "channel_name")
    val channelName: String?,
    @ColumnInfo(name = "channel_image")
    val channelImage: String?,
    @ColumnInfo(name = "watched_ms")
    val watchedMs: Long,
    @ColumnInfo(name = "session_count")
    val sessionCount: Long,
)

data class ViewingStatsCategoryRow(
    @ColumnInfo(name = "category_key")
    val categoryKey: String,
    @ColumnInfo(name = "category_id")
    val categoryId: String?,
    @ColumnInfo(name = "category_name")
    val categoryName: String?,
    @ColumnInfo(name = "category_image")
    val categoryImage: String?,
    @ColumnInfo(name = "watched_ms")
    val watchedMs: Long,
    @ColumnInfo(name = "session_count")
    val sessionCount: Long,
)

data class ViewingStatsContentTypeRow(
    @ColumnInfo(name = "content_type")
    val contentType: String,
    @ColumnInfo(name = "watched_ms")
    val watchedMs: Long,
    @ColumnInfo(name = "session_count")
    val sessionCount: Long,
)

data class ViewingStatsTimelineRow(
    @ColumnInfo(name = "bucket_start")
    val bucketStart: Long,
    @ColumnInfo(name = "bucket_end")
    val bucketEnd: Long,
    @ColumnInfo(name = "watched_ms")
    val watchedMs: Long,
    @ColumnInfo(name = "session_count")
    val sessionCount: Long,
    @ColumnInfo(name = "channel_count")
    val channelCount: Long,
)
