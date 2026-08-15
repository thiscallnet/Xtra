package com.github.andreyasadchy.xtra.db

import androidx.room.Entity
import androidx.room.Index

/**
 * Small durable metadata cache shared by account and public detail pages.
 * Payloads are typed and versioned by their cache kind in MetadataCache.
 */
@Entity(
    tableName = "metadata_cache",
    primaryKeys = ["kind", "cacheKey"],
    indices = [Index(value = ["lastAccessAt"])],
)
data class MetadataCacheEntry(
    val kind: String,
    val cacheKey: String,
    val payload: String,
    val updatedAt: Long,
    val lastAccessAt: Long,
)
