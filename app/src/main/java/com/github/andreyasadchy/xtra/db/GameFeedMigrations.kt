package com.github.andreyasadchy.xtra.db

import androidx.room.migration.Migration

object GameFeedMigrations {
    val FROM_49 = Migration(49, 50) { db ->
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS game_feed_items (
                feedKey TEXT NOT NULL,
                itemKey TEXT NOT NULL,
                position INTEGER NOT NULL,
                gameId TEXT,
                gameSlug TEXT,
                gameName TEXT,
                boxArtURL TEXT,
                viewerCount INTEGER,
                broadcasterCount INTEGER,
                tags TEXT,
                generation INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(feedKey, itemKey)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_game_feed_items_feedKey_position ON game_feed_items(feedKey, position)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS game_feed_states (
                feedKey TEXT NOT NULL PRIMARY KEY,
                nextCursor TEXT,
                lastSuccessAt INTEGER,
                lastAttemptAt INTEGER,
                lastAccessAt INTEGER NOT NULL,
                failureBackoffUntil INTEGER,
                rateLimitUntil INTEGER,
                nextCursorApi TEXT,
                activeGeneration INTEGER NOT NULL DEFAULT 0,
                staleTailRetainedAt INTEGER
            )
            """.trimIndent()
        )
    }
}
