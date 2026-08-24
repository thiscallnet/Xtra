package com.github.andreyasadchy.xtra.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GameFeedDao {

    @Query(
        """
        SELECT items.*
        FROM game_feed_items AS items
        WHERE items.feedKey = :feedKey
          AND (
            NOT EXISTS (
                SELECT 1 FROM game_feed_states
                WHERE feedKey = :feedKey
            )
            OR items.generation = (
                SELECT activeGeneration FROM game_feed_states
                WHERE feedKey = :feedKey
            )
          )
        ORDER BY items.position ASC, items.itemKey ASC
        """
    )
    fun pagingSource(feedKey: String): PagingSource<Int, CachedGameFeedItem>

    @Query("SELECT * FROM game_feed_items WHERE feedKey = :feedKey ORDER BY position ASC, itemKey ASC")
    fun itemsForFeed(feedKey: String): List<CachedGameFeedItem>

    @Query(
        """
        SELECT items.*
        FROM game_feed_items AS items
        WHERE items.feedKey = :feedKey
          AND (
            NOT EXISTS (
                SELECT 1 FROM game_feed_states
                WHERE feedKey = :feedKey
            )
            OR items.generation = (
                SELECT activeGeneration FROM game_feed_states
                WHERE feedKey = :feedKey
            )
          )
        ORDER BY items.position ASC, items.itemKey ASC
        LIMIT :limit
        """
    )
    fun activeItemsFlow(feedKey: String, limit: Int): Flow<List<CachedGameFeedItem>>

    @Query("SELECT * FROM game_feed_states WHERE feedKey = :feedKey")
    fun state(feedKey: String): GameFeedState?

    @Query("SELECT COUNT(*) FROM game_feed_items WHERE feedKey = :feedKey AND generation = (SELECT activeGeneration FROM game_feed_states WHERE feedKey = :feedKey)")
    fun activeItemCount(feedKey: String): Int

    @Query("SELECT * FROM game_feed_states ORDER BY lastAccessAt DESC")
    fun allStates(): List<GameFeedState>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertItems(items: List<CachedGameFeedItem>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertState(state: GameFeedState)

    @Query("DELETE FROM game_feed_items WHERE feedKey = :feedKey")
    fun deleteItems(feedKey: String)

    @Query("DELETE FROM game_feed_items WHERE feedKey = :feedKey AND generation != :generation")
    fun deleteItemsExceptGeneration(feedKey: String, generation: Long)

    @Query("DELETE FROM game_feed_states WHERE feedKey = :feedKey")
    fun deleteState(feedKey: String)
}
