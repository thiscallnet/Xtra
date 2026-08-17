package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.andreyasadchy.xtra.model.chat.FavoriteEmote
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteEmotesDao {

    @Query("SELECT * FROM favorite_emotes ORDER BY favorited_at DESC")
    fun getAllFlow(): Flow<List<FavoriteEmote>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEmote)

    @Query("DELETE FROM favorite_emotes WHERE provider = :provider AND emote_id = :emoteId")
    suspend fun delete(provider: String, emoteId: String)
}
