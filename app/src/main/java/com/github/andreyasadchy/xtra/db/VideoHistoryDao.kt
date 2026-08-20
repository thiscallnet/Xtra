package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.github.andreyasadchy.xtra.model.VideoHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoHistoryDao {

    @Query("""
        SELECT * FROM video_history
        WHERE position >= 30000
          AND (durationSeconds IS NULL OR durationSeconds <= 0 OR position < durationSeconds * 950)
        ORDER BY updatedAt DESC
        LIMIT :limit
    """)
    fun getContinueWatching(limit: Int): Flow<List<VideoHistory>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfMissing(item: VideoHistory)

    @Query("""
        UPDATE video_history SET
            durationSeconds = COALESCE(:durationSeconds, durationSeconds),
            channelId = COALESCE(:channelId, channelId),
            channelLogin = COALESCE(:channelLogin, channelLogin),
            channelName = COALESCE(:channelName, channelName),
            channelImageURL = COALESCE(:channelImageURL, channelImageURL),
            title = COALESCE(:title, title),
            thumbnailURL = COALESCE(:thumbnailURL, thumbnailURL),
            gameId = COALESCE(:gameId, gameId),
            gameSlug = COALESCE(:gameSlug, gameSlug),
            gameName = COALESCE(:gameName, gameName),
            createdAt = COALESCE(:createdAt, createdAt)
        WHERE id = :id
    """)
    fun updateMetadata(
        id: Long,
        durationSeconds: Int?,
        channelId: String?,
        channelLogin: String?,
        channelName: String?,
        channelImageURL: String?,
        title: String?,
        thumbnailURL: String?,
        gameId: String?,
        gameSlug: String?,
        gameName: String?,
        createdAt: String?,
    )

    @Transaction
    fun upsertMetadata(item: VideoHistory) {
        insertIfMissing(item)
        updateMetadata(
            id = item.id,
            durationSeconds = item.durationSeconds,
            channelId = item.channelId,
            channelLogin = item.channelLogin,
            channelName = item.channelName,
            channelImageURL = item.channelImageURL,
            title = item.title,
            thumbnailURL = item.thumbnailURL,
            gameId = item.gameId,
            gameSlug = item.gameSlug,
            gameName = item.gameName,
            createdAt = item.createdAt,
        )
    }

    @Query("UPDATE video_history SET position = :position, updatedAt = :updatedAt WHERE id = :id")
    fun updatePosition(id: Long, position: Long, updatedAt: Long)

    @Query("DELETE FROM video_history WHERE id NOT IN (SELECT id FROM video_history ORDER BY updatedAt DESC LIMIT :limit)")
    fun prune(limit: Int)

    @Query("DELETE FROM video_history")
    fun deleteAll()
}
