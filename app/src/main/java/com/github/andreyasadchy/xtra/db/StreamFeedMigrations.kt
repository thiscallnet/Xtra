package com.github.andreyasadchy.xtra.db

import androidx.room.migration.Migration

/** Schema migrations for the cache-first live stream feed tables. */
object StreamFeedMigrations {
    val FROM_40 = Migration(40, 41) { db ->
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS stream_feed_items (" +
                    "feedKey TEXT NOT NULL, itemKey TEXT NOT NULL, position INTEGER NOT NULL, " +
                    "streamId TEXT, channelId TEXT, channelLogin TEXT, channelName TEXT, " +
                    "channelImageURL TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, title TEXT, " +
                    "thumbnailURL TEXT, createdAt TEXT, viewerCount INTEGER, tags TEXT, " +
                    "PRIMARY KEY(feedKey, itemKey))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_stream_feed_items_feedKey_position ON stream_feed_items(feedKey, position)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_stream_feed_items_feedKey_channelId ON stream_feed_items(feedKey, channelId)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS stream_feed_states (" +
                    "feedKey TEXT NOT NULL PRIMARY KEY, nextCursor TEXT, lastSuccessAt INTEGER, " +
                    "lastAttemptAt INTEGER, lastAccessAt INTEGER NOT NULL, " +
                    "failureBackoffUntil INTEGER, rateLimitUntil INTEGER)"
        )
    }

    val FROM_41 = Migration(41, 42) { db ->
        db.execSQL("ALTER TABLE stream_feed_items ADD COLUMN generation INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE stream_feed_states ADD COLUMN nextCursorApi TEXT")
        db.execSQL("ALTER TABLE stream_feed_states ADD COLUMN activeGeneration INTEGER NOT NULL DEFAULT 0")
    }

    val FROM_43 = Migration(43, 44) { db ->
        db.execSQL("ALTER TABLE stream_feed_states ADD COLUMN staleTailRetainedAt INTEGER")
        // Existing rows predate the separate tail clock. lastSuccessAt is the
        // preferred value, while lastAccessAt covers feeds invalidated before
        // this column existed.
        db.execSQL(
            "UPDATE stream_feed_states SET staleTailRetainedAt = COALESCE(lastSuccessAt, lastAccessAt) " +
                    "WHERE activeGeneration != 0"
        )
    }
}
