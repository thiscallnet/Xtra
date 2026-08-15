package com.github.andreyasadchy.xtra.db

import androidx.room.migration.Migration

/** Schema migration for cache-first account and detail pages. */
object MetadataCacheMigrations {
    val FROM_42 = Migration(42, 43) { db ->
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS metadata_cache (" +
                    "kind TEXT NOT NULL, cacheKey TEXT NOT NULL, payload TEXT NOT NULL, " +
                    "updatedAt INTEGER NOT NULL, lastAccessAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(kind, cacheKey))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_metadata_cache_lastAccessAt ON metadata_cache(lastAccessAt)")
    }
}
