package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(item: VideoHistory)

    @Query("UPDATE video_history SET position = :position, updatedAt = :updatedAt WHERE id = :id")
    fun updatePosition(id: Long, position: Long, updatedAt: Long)

    @Query("DELETE FROM video_history")
    fun deleteAll()
}
