package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.andreyasadchy.xtra.model.ChannelStreamStart

@Dao
interface ChannelStreamStartsDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(items: List<ChannelStreamStart>)

    @Query("SELECT * FROM channel_stream_starts")
    fun getAll(): List<ChannelStreamStart>

    @Query("DELETE FROM channel_stream_starts")
    fun deleteAll()

    @Query("SELECT * FROM channel_stream_starts WHERE startedAt >= :sinceMs")
    fun getSince(sinceMs: Long): List<ChannelStreamStart>

    @Query("DELETE FROM channel_stream_starts WHERE startedAt < :beforeMs")
    fun deleteBefore(beforeMs: Long)
}
