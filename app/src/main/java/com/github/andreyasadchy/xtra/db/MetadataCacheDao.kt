package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MetadataCacheDao {

    @Query("SELECT * FROM metadata_cache WHERE kind = :kind AND cacheKey = :cacheKey LIMIT 1")
    fun entry(kind: String, cacheKey: String): MetadataCacheEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entry: MetadataCacheEntry)

    @Query("UPDATE metadata_cache SET lastAccessAt = :nowMs WHERE kind = :kind AND cacheKey = :cacheKey")
    fun touch(kind: String, cacheKey: String, nowMs: Long)

    @Query("DELETE FROM metadata_cache WHERE kind = :kind AND cacheKey = :cacheKey")
    fun delete(kind: String, cacheKey: String)

    @Query("SELECT * FROM metadata_cache ORDER BY lastAccessAt DESC")
    fun allEntries(): List<MetadataCacheEntry>

    @Query("SELECT * FROM metadata_cache WHERE kind = :kind")
    fun entries(kind: String): List<MetadataCacheEntry>
}
