package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.github.andreyasadchy.xtra.model.chat.EmoteUsage
import kotlinx.coroutines.flow.Flow

@Dao
interface EmoteUsageDao {

    @Query("SELECT * FROM emote_usage WHERE viewer_id = :viewerId AND (channel_id IS NULL OR channel_id = :channelId)")
    fun observeForChannel(viewerId: String, channelId: String): Flow<List<EmoteUsage>>

    @Query("SELECT * FROM emote_usage WHERE viewer_id = :viewerId AND (channel_id IS NULL OR channel_id = :channelId)")
    suspend fun getForChannel(viewerId: String, channelId: String): List<EmoteUsage>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(usage: EmoteUsage): Long

    @Query(
        "UPDATE emote_usage SET use_count = use_count + :count, last_used_at = MAX(last_used_at, :lastUsedAt) " +
                "WHERE viewer_id = :viewerId AND usage_key = :usageKey"
    )
    suspend fun incrementExisting(viewerId: String, usageKey: String, count: Long, lastUsedAt: Long)

    /**
     * INSERT OR IGNORE followed by UPDATE is supported by the SQLite version on API 26.
     * Room wraps this method in one transaction, so concurrent increments cannot be lost.
     */
    @Transaction
    suspend fun increment(
        viewerId: String,
        usageKey: String,
        provider: String,
        emoteId: String,
        scope: String,
        channelId: String?,
        count: Long,
        lastUsedAt: Long,
    ) {
        val inserted = insertIfAbsent(
            EmoteUsage(
                viewerId = viewerId,
                usageKey = usageKey,
                provider = provider,
                emoteId = emoteId,
                scope = scope,
                channelId = channelId,
                useCount = count,
                lastUsedAt = lastUsedAt,
            ),
        )
        if (inserted == -1L) {
            incrementExisting(viewerId, usageKey, count, lastUsedAt)
        }
    }
}
