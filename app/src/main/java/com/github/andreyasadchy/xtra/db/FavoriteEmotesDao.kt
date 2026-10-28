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

    @Query("SELECT * FROM favorite_emotes WHERE provider = :provider AND emote_id = :emoteId LIMIT 1")
    suspend fun get(provider: String, emoteId: String): FavoriteEmote?

    @Query("UPDATE favorite_emotes SET provider = :newProvider, emote_id = :newEmoteId WHERE provider = :oldProvider AND emote_id = :oldEmoteId")
    suspend fun updateKey(
        oldProvider: String,
        oldEmoteId: String,
        newProvider: String,
        newEmoteId: String,
    )

    @Transaction
    suspend fun updateOrder(order: List<FavoriteEmoteKey>) {
        order.forEachIndexed { index, key ->
            updateSortOrder(key.provider.name, key.emoteId, index)
        }
    }

    @Transaction
    suspend fun migrateKey(oldKey: FavoriteEmoteKey, newKey: FavoriteEmoteKey) {
        if (get(oldKey.provider.name, oldKey.emoteId) == null) return
        if (get(newKey.provider.name, newKey.emoteId) != null) {
            delete(oldKey.provider.name, oldKey.emoteId)
        } else {
            updateKey(
                oldProvider = oldKey.provider.name,
                oldEmoteId = oldKey.emoteId,
                newProvider = newKey.provider.name,
                newEmoteId = newKey.emoteId,
            )
        }
    }

    @Query("DELETE FROM favorite_emotes WHERE provider = :provider AND emote_id = :emoteId")
    suspend fun delete(provider: String, emoteId: String)
}
