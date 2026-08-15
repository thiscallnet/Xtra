package com.github.andreyasadchy.xtra.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface StreamFeedDao {

    @Query("SELECT * FROM stream_feed_items WHERE feedKey = :feedKey ORDER BY position ASC, itemKey ASC")
    fun pagingSource(feedKey: String): PagingSource<Int, CachedStreamFeedItem>

    @Query("SELECT * FROM stream_feed_items WHERE feedKey = :feedKey ORDER BY position ASC, itemKey ASC")
    fun itemsForFeed(feedKey: String): List<CachedStreamFeedItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertItems(items: List<CachedStreamFeedItem>)

    @Query("DELETE FROM stream_feed_items WHERE feedKey = :feedKey")
    fun deleteItems(feedKey: String)

    @Query("DELETE FROM stream_feed_items WHERE feedKey = :feedKey AND generation != :generation")
    fun deleteItemsExceptGeneration(feedKey: String, generation: Long)

    @Query("SELECT * FROM stream_feed_states WHERE feedKey = :feedKey")
    fun state(feedKey: String): StreamFeedState?

    @Query("SELECT * FROM stream_feed_states ORDER BY lastAccessAt DESC")
    fun allStates(): List<StreamFeedState>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertState(state: StreamFeedState)

    @Query("DELETE FROM stream_feed_states WHERE feedKey = :feedKey")
    fun deleteState(feedKey: String)
}
