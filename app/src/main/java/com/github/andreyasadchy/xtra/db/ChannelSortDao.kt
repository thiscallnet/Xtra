package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.github.andreyasadchy.xtra.model.ui.ChannelSort

@Dao
interface ChannelSortDao {

    @Query("SELECT * FROM sort_channel WHERE id = :id")
    fun getById(id: String): ChannelSort?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(item: ChannelSort)

    @Query("UPDATE sort_channel SET videoSort = NULL, videoType = NULL WHERE id = :id")
    fun clearVideoSort(id: String)

    @Query("UPDATE sort_channel SET clipPeriod = NULL WHERE id = :id")
    fun clearClipSort(id: String)

    @Query("DELETE FROM sort_channel WHERE id = :id AND videoSort IS NULL AND videoType IS NULL AND clipPeriod IS NULL")
    fun deleteIfEmpty(id: String)

    @Transaction
    fun deleteVideoSort(id: String) {
        clearVideoSort(id)
        deleteIfEmpty(id)
    }

    @Transaction
    fun deleteClipSort(id: String) {
        clearClipSort(id)
        deleteIfEmpty(id)
    }
}
