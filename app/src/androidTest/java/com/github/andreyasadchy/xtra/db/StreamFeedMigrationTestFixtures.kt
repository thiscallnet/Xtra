package com.github.andreyasadchy.xtra.db

import androidx.sqlite.db.SupportSQLiteDatabase

/** Helpers for constructing historical stream-feed schemas from the current test database. */
internal object StreamFeedMigrationTestFixtures {

    /** Recreates the pre-Partner stream-feed schema used by database versions through 52. */
    fun useVersion52Schema(database: SupportSQLiteDatabase) {
        if (!tableExists(database, "stream_feed_items")) return
        if (!columnExists(database, "stream_feed_items", "broadcasterType") &&
            !columnExists(database, "stream_feed_items", "broadcasterTypeFetchedAtEpochMs")
        ) return

        database.execSQL("DROP TABLE IF EXISTS stream_feed_items_version52")
        database.execSQL(
            "CREATE TABLE stream_feed_items_version52 (" +
                    "feedKey TEXT NOT NULL, itemKey TEXT NOT NULL, position INTEGER NOT NULL, " +
                    "streamId TEXT, channelId TEXT, channelLogin TEXT, channelName TEXT, " +
                    "channelImageURL TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, title TEXT, " +
                    "thumbnailURL TEXT, createdAt TEXT, viewerCount INTEGER, tags TEXT, " +
                    "generation INTEGER NOT NULL, PRIMARY KEY(feedKey, itemKey))"
        )
        database.execSQL(
            "INSERT INTO stream_feed_items_version52 (" +
                    "feedKey, itemKey, position, streamId, channelId, channelLogin, channelName, " +
                    "channelImageURL, gameId, gameSlug, gameName, title, thumbnailURL, createdAt, " +
                    "viewerCount, tags, generation) " +
                    "SELECT feedKey, itemKey, position, streamId, channelId, channelLogin, channelName, " +
                    "channelImageURL, gameId, gameSlug, gameName, title, thumbnailURL, createdAt, " +
                    "viewerCount, tags, generation FROM stream_feed_items"
        )
        database.execSQL("DROP TABLE stream_feed_items")
        database.execSQL("ALTER TABLE stream_feed_items_version52 RENAME TO stream_feed_items")
        database.execSQL("CREATE INDEX index_stream_feed_items_feedKey_position ON stream_feed_items(feedKey, position)")
        database.execSQL("CREATE INDEX index_stream_feed_items_feedKey_channelId ON stream_feed_items(feedKey, channelId)")
    }

    private fun tableExists(database: SupportSQLiteDatabase, tableName: String): Boolean =
        database.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(tableName),
        ).use { it.moveToFirst() }

    private fun columnExists(database: SupportSQLiteDatabase, tableName: String, columnName: String): Boolean =
        database.query("PRAGMA table_info($tableName)").use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == columnName) return@use true
            }
            false
        }
}
