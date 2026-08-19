package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.github.andreyasadchy.xtra.model.chat.FavoriteEmote
import com.github.andreyasadchy.xtra.model.chat.FavoriteEmoteKey
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteEmotesDao {

    @Query("SELECT * FROM favorite_emotes ORDER BY sort_order ASC, favorited_at DESC")
    fun getAllFlow(): Flow<List<FavoriteEmote>>

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM favorite_emotes")
    suspend fun getNextSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEmote)

    @Transaction
    suspend fun insertAtEnd(favorite: FavoriteEmote) {
        insert(favorite.copy(sortOrder = getNextSortOrder()))
    }

    @Query("UPDATE favorite_emotes SET sort_order = :sortOrder WHERE provider = :provider AND emote_id = :emoteId")
    suspend fun updateSortOrder(provider: String, emoteId: String, sortOrder: Int)

    @Transaction
    suspend fun updateOrder(order: List<FavoriteEmoteKey>) {
        order.forEachIndexed { index, key ->
            updateSortOrder(key.provider.name, key.emoteId, index)
        }
    }

    @Query("DELETE FROM favorite_emotes WHERE provider = :provider AND emote_id = :emoteId")
    suspend fun delete(provider: String, emoteId: String)
}
