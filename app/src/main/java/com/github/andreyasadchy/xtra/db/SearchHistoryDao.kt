package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.github.andreyasadchy.xtra.model.ui.SearchHistoryItem
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchHistoryDao {

    @Query("SELECT * FROM search_history ORDER BY lastOpenedAt DESC")
    fun getAll(): Flow<List<SearchHistoryItem>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(item: SearchHistoryItem): Long

    @Query("SELECT * FROM search_history WHERE kind = :kind AND slug = :slug COLLATE NOCASE")
    fun findBySlug(kind: String, slug: String): List<SearchHistoryItem>

    @Query("UPDATE search_history SET title = :title, slug = COALESCE(:slug, slug), imageUrl = COALESCE(:imageUrl, imageUrl), payload = COALESCE(:payload, payload), openCount = openCount + 1 + :carried, lastOpenedAt = :openedAt WHERE kind = :kind AND refId = :refId")
    fun markOpened(kind: String, refId: String, title: String, slug: String?, imageUrl: String?, payload: String?, carried: Int, openedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun restore(items: List<SearchHistoryItem>)

    @Query("DELETE FROM search_history WHERE kind = :kind AND refId = :refId")
    fun delete(kind: String, refId: String)

    @Query("DELETE FROM search_history WHERE kind = 'video' AND rowid NOT IN (SELECT rowid FROM search_history WHERE kind = 'video' ORDER BY lastOpenedAt DESC LIMIT :limit)")
    fun trimVideos(limit: Int)

    @Query("DELETE FROM search_history WHERE kind != 'video' AND rowid NOT IN (SELECT rowid FROM search_history WHERE kind != 'video' ORDER BY lastOpenedAt DESC LIMIT :limit)")
    fun trimOthers(limit: Int)

    @Query("DELETE FROM search_history")
    fun deleteAll()

    @Transaction
    fun recordOpened(item: SearchHistoryItem) {
        val now = System.currentTimeMillis()
        // The same channel/category can arrive keyed by id or by login/slug; keep one row.
        val duplicates = item.slug?.let { findBySlug(item.kind, it) }.orEmpty().filter { it.refId != item.refId }
        duplicates.forEach { delete(it.kind, it.refId) }
        val carried = duplicates.sumOf { it.openCount }
        val fresh = SearchHistoryItem(item.kind, item.refId, item.title, item.slug, item.imageUrl, 1 + carried, now, item.payload)
        if (insertIfAbsent(fresh) == -1L) {
            markOpened(item.kind, item.refId, item.title, item.slug, item.imageUrl, item.payload, carried, now)
        }
        trimOthers(SearchHistoryItem.MAX_STORED)
        trimVideos(SearchHistoryItem.MAX_STORED_VIDEOS)
    }
}
