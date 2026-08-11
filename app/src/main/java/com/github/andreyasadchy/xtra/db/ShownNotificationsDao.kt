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

    @Query("SELECT * FROM shown_notifications WHERE channelId = :channelId")
    fun getById(channelId: String): ShownNotification?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertList(items: List<ShownNotification>)

    @Delete
    fun deleteList(items: List<ShownNotification>)
}
