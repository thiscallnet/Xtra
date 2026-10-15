package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.github.andreyasadchy.xtra.model.ui.GameSort

@Dao
interface GameSortDao {

    @Query("SELECT * FROM sort_game WHERE id = :id")
    fun getById(id: String): GameSort?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(item: GameSort)

    @Query("UPDATE sort_game SET streamSort = NULL, streamTags = NULL, streamLanguages = NULL WHERE id = :id")
    fun clearStreamSort(id: String)

    @Query("UPDATE sort_game SET videoSort = NULL, videoPeriod = NULL, videoType = NULL, videoLanguages = NULL WHERE id = :id")
    fun clearVideoSort(id: String)

    @Query("UPDATE sort_game SET clipPeriod = NULL, clipLanguages = NULL WHERE id = :id")
    fun clearClipSort(id: String)

    @Query("""DELETE FROM sort_game WHERE id = :id
        AND streamSort IS NULL AND streamTags IS NULL AND streamLanguages IS NULL
        AND videoSort IS NULL AND videoPeriod IS NULL AND videoType IS NULL AND videoLanguages IS NULL
        AND clipPeriod IS NULL AND clipLanguages IS NULL""")
    fun deleteIfEmpty(id: String)

    @Transaction
    fun deleteStreamSort(id: String) {
        clearStreamSort(id)
        deleteIfEmpty(id)
    }

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
