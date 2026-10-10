package com.github.andreyasadchy.xtra.db

import androidx.room.migration.Migration

object SearchHistoryMigrations {

    /** Keyword history is replaced by the channels, categories and videos users actually opened. */
    val FROM_54 = Migration(54, 55) { db ->
        db.execSQL("DROP TABLE IF EXISTS recent_search")
        db.execSQL("CREATE TABLE IF NOT EXISTS search_history (kind TEXT NOT NULL, refId TEXT NOT NULL, title TEXT NOT NULL, slug TEXT, imageUrl TEXT, openCount INTEGER NOT NULL, lastOpenedAt INTEGER NOT NULL, payload TEXT, PRIMARY KEY (kind, refId))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_search_history_lastOpenedAt ON search_history (lastOpenedAt)")
    }
}
