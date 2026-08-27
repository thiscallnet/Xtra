package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.andreyasadchy.xtra.model.ShownNotification

@Dao
interface ShownNotificationsDao {

    @Query("SELECT * FROM shown_notifications")
    fun getAll(): List<ShownNotification>

    @Query("SELECT * FROM shown_notifications WHERE channelId = :channelId ORDER BY startedAt DESC LIMIT 1")
    fun getById(channelId: String): ShownNotification?

    @Query("SELECT * FROM shown_notifications WHERE streamId = :streamId LIMIT 1")
    fun getByStreamId(streamId: String): ShownNotification?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(item: ShownNotification): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertList(items: List<ShownNotification>)

    @Delete
    fun deleteList(items: List<ShownNotification>)
}
