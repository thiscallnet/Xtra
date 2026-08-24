package com.github.andreyasadchy.xtra.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedCache
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedFreshnessPolicy
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedKey
import com.github.andreyasadchy.xtra.model.chat.FavoriteEmote
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseNames = mutableListOf<String>()

    @After
    fun deleteTestDatabases() {
        databaseNames.forEach(context::deleteDatabase)
    }

    @Test
    fun migrateCurrentVersion38To46CreatesStatisticsAndStreamFeedSchema() {
        val name = "migration-v38.db"
        prepareDatabase(name, version = 38, historicalVersion39 = false)

        val database = openMigratedDatabase(name)
        val sqlite = database.openHelper.readableDatabase
        assertStatisticsSchema(sqlite)
        assertStreamFeedSchema(sqlite)
        database.close()
    }

    @Test
    fun migrateHistoricalVersion39NormalizesAndCreatesCurrentSchema() {
        val name = "migration-historical-v39.db"
        prepareDatabase(name, version = 39, historicalVersion39 = true)

        val database = openMigratedDatabase(name)
        val sqlite = database.openHelper.readableDatabase
        assertStatisticsSchema(sqlite)
        assertStreamFeedSchema(sqlite)
        assertTrue(tableExists(sqlite, "notification_events"))
        assertFalse(tableExists(sqlite, "live_notification_logs"))
        database.close()
    }

    @Test
    fun migrateInvalidatedV43RetainedTailUsesLastAccessForExpiry() = runBlocking {
        val name = "migration-v43-invalidated-tail.db"
        val feedKey = StreamFeedKey("top:migration-invalidated")
        prepareInvalidatedV43Feed(name, feedKey.value)

        val database = openMigratedDatabase(name)
        val state = database.streamFeedDao().state(feedKey.value)
        assertEquals(null, state?.lastSuccessAt)
        assertEquals(1_000L, state?.staleTailRetainedAt)
        assertEquals(
            listOf("channel:active", "channel:old"),
            database.streamFeedDao().itemsForFeed(feedKey.value).map { it.itemKey },
        )

        StreamFeedCache(database).touchAccess(
            feedKey,
            nowMs = 1_000L + StreamFeedFreshnessPolicy.MAX_RETAINED_STALE_TAIL_AGE_MS + 1L,
        )

        assertEquals(
            listOf("channel:active"),
            database.streamFeedDao().itemsForFeed(feedKey.value).map { it.itemKey },
        )
        database.close()
    }

    @Test
    fun migrateVersion43Through44And45RetainsExistingViewingStats() {
        val name = "migration-v43-stats.db"
        prepareVersion43Database(name)
        insertLegacyViewingStats(name)

        val database = openMigratedDatabase(name)
        val sqlite = database.openHelper.readableDatabase
        assertStatisticsSchema(sqlite)
        assertStreamFeedSchema(sqlite)
        assertRetainedViewingStats(sqlite)
        database.close()
    }

    @Test
    fun migrateVersion44To46RetainsExistingViewingStats() {
        val name = "migration-v44-stats.db"
        prepareVersion44Database(name)
        insertLegacyViewingStats(name)

        val database = openMigratedDatabase(name)
        val sqlite = database.openHelper.readableDatabase
        assertEquals(AppDatabase.VERSION, scalarInt(sqlite, "PRAGMA user_version"))
        assertRetainedViewingStats(sqlite)
        database.close()
    }

    @Test
    fun migrateVersion45To46CreatesFavoritesWithoutChangingExistingData() = runBlocking {
        val name = "migration-v45-favorites.db"
        prepareVersion45WithoutFavorites(name)

        val database = openMigratedDatabase(name)
        val sqlite = database.openHelper.readableDatabase
        assertEquals(AppDatabase.VERSION, scalarInt(sqlite, "PRAGMA user_version"))
        assertTrue(tableExists(sqlite, "favorite_emotes"))
        assertEquals("legacy", database.recentEmotes().getAll().single().name)

        database.favoriteEmotes().insert(FavoriteEmote("BTTV", "emote-id", 123L))
        assertEquals("emote-id", database.favoriteEmotes().getAllFlow().first().single().emoteId)
        database.close()
    }

    @Test
    fun migrateVersion49To50CreatesGameFeedSchema() {
        val name = "migration-v49-game-feed.db"
        prepareVersion49Database(name)

        val database = openMigratedDatabase(name)
        assertGameFeedSchema(database.openHelper.readableDatabase)
        database.close()
    }

    private fun openMigratedDatabase(name: String): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(
                ViewingStatsMigrations.FROM_38,
                ViewingStatsMigrations.FROM_HISTORICAL_39,
                StreamFeedMigrations.FROM_40,
                StreamFeedMigrations.FROM_41,
                MetadataCacheMigrations.FROM_42,
                StreamFeedMigrations.FROM_43,
                ViewingStatsMigrations.FROM_44,
                Migration(45, 46) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS favorite_emotes (provider TEXT NOT NULL, emote_id TEXT NOT NULL, favorited_at INTEGER NOT NULL, PRIMARY KEY (provider, emote_id))")
                },
                Migration(46, 47) { db ->
                    db.execSQL("ALTER TABLE favorite_emotes ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
                },
                Migration(47, 48) { db ->
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS video_history (
                            id INTEGER NOT NULL PRIMARY KEY,
                            position INTEGER NOT NULL,
                            durationSeconds INTEGER,
                            channelId TEXT,
                            channelLogin TEXT,
                            channelName TEXT,
                            channelImageURL TEXT,
                            title TEXT,
                            thumbnailURL TEXT,
                            gameId TEXT,
                            gameSlug TEXT,
                            gameName TEXT,
                            createdAt TEXT,
                            updatedAt INTEGER NOT NULL
                        )
                    """.trimIndent())
                },
                Migration(48, 49) { db ->
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_videos_url ON videos(url)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_videos_status ON videos(status)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_videos_videoId ON videos(videoId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_videos_channel_id ON videos(channel_id)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_video_history_updatedAt ON video_history(updatedAt)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_video_history_channelId ON video_history(channelId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_recent_search_type_lastSearched ON recent_search(type, lastSearched)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_recent_search_query_type ON recent_search(query, type)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_videoId ON bookmarks(videoId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_userId ON bookmarks(userId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_notification_events_channelId ON notification_events(channelId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_notification_events_queuedAt ON notification_events(queuedAt)")
                },
                GameFeedMigrations.FROM_49,
            )
            .build()
            .also { it.openHelper.writableDatabase }
    }

    /**
     * Builds a complete current-schema database, then removes only the
     * statistics additions and adjusts user_version to model an older install.
     * This keeps the test focused on the production migration without copying
     * every historical table definition into test code.
     */
    private fun prepareDatabase(name: String, version: Int, historicalVersion39: Boolean) {
        context.deleteDatabase(name)
        databaseNames += name
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL("DROP INDEX IF EXISTS index_viewing_intervals_session_id")
        sqlite.execSQL("DROP INDEX IF EXISTS index_viewing_intervals_channel_id_start_at")
        sqlite.execSQL("DROP INDEX IF EXISTS index_viewing_intervals_start_at")
        sqlite.execSQL("DROP TABLE IF EXISTS viewing_intervals")
        sqlite.execSQL("DROP INDEX IF EXISTS index_viewing_sessions_channel_id_started_at")
        sqlite.execSQL("DROP INDEX IF EXISTS index_viewing_sessions_started_at")
        sqlite.execSQL("DROP TABLE IF EXISTS viewing_sessions")
        sqlite.execSQL("DROP INDEX IF EXISTS index_stream_feed_items_feedKey_position")
        sqlite.execSQL("DROP INDEX IF EXISTS index_stream_feed_items_feedKey_channelId")
        sqlite.execSQL("DROP TABLE IF EXISTS stream_feed_items")
        sqlite.execSQL("DROP TABLE IF EXISTS stream_feed_states")
        sqlite.execSQL("DROP INDEX IF EXISTS index_metadata_cache_lastAccessAt")
        sqlite.execSQL("DROP TABLE IF EXISTS metadata_cache")
        sqlite.execSQL("DROP INDEX IF EXISTS index_game_feed_items_feedKey_position")
        sqlite.execSQL("DROP TABLE IF EXISTS game_feed_items")
        sqlite.execSQL("DROP TABLE IF EXISTS game_feed_states")
        if (version < 46) {
            sqlite.execSQL("DROP TABLE IF EXISTS favorite_emotes")
        }
        if (historicalVersion39) {
            sqlite.execSQL("DROP TABLE IF EXISTS notification_events")
            sqlite.execSQL("CREATE TABLE live_notification_logs (id INTEGER PRIMARY KEY NOT NULL)")
        }
        sqlite.execSQL("PRAGMA user_version = $version")
        database.close()
    }

    private fun prepareVersion49Database(name: String) {
        context.deleteDatabase(name)
        databaseNames += name
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL("DROP INDEX IF EXISTS index_game_feed_items_feedKey_position")
        sqlite.execSQL("DROP TABLE IF EXISTS game_feed_items")
        sqlite.execSQL("DROP TABLE IF EXISTS game_feed_states")
        sqlite.execSQL("PRAGMA user_version = 49")
        database.close()
    }

    private fun prepareInvalidatedV43Feed(name: String, feedKey: String) {
        context.deleteDatabase(name)
        databaseNames += name
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL("DROP TABLE IF EXISTS favorite_emotes")
        dropViewingStatsTables(sqlite)
        sqlite.execSQL("DROP INDEX IF EXISTS index_stream_feed_items_feedKey_position")
        sqlite.execSQL("DROP INDEX IF EXISTS index_stream_feed_items_feedKey_channelId")
        sqlite.execSQL("DROP TABLE IF EXISTS stream_feed_items")
        sqlite.execSQL("DROP TABLE IF EXISTS stream_feed_states")
        sqlite.execSQL(
            "CREATE TABLE stream_feed_items (" +
                    "feedKey TEXT NOT NULL, itemKey TEXT NOT NULL, position INTEGER NOT NULL, " +
                    "streamId TEXT, channelId TEXT, channelLogin TEXT, channelName TEXT, " +
                    "channelImageURL TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, title TEXT, " +
                    "thumbnailURL TEXT, createdAt TEXT, viewerCount INTEGER, tags TEXT, " +
                    "generation INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(feedKey, itemKey))"
        )
        sqlite.execSQL("CREATE INDEX index_stream_feed_items_feedKey_position ON stream_feed_items(feedKey, position)")
        sqlite.execSQL("CREATE INDEX index_stream_feed_items_feedKey_channelId ON stream_feed_items(feedKey, channelId)")
        sqlite.execSQL(
            "CREATE TABLE stream_feed_states (" +
                    "feedKey TEXT NOT NULL PRIMARY KEY, nextCursor TEXT, lastSuccessAt INTEGER, " +
                    "lastAttemptAt INTEGER, lastAccessAt INTEGER NOT NULL, " +
                    "failureBackoffUntil INTEGER, rateLimitUntil INTEGER, nextCursorApi TEXT, " +
                    "activeGeneration INTEGER NOT NULL DEFAULT 0)"
        )
        createLegacyViewingStatsTables { sql -> sqlite.execSQL(sql) }
        sqlite.execSQL(
            "INSERT INTO stream_feed_items (feedKey, itemKey, position, channelId, generation) " +
                    "VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>(feedKey, "channel:active", 0, "active", 2),
        )
        sqlite.execSQL(
            "INSERT INTO stream_feed_items (feedKey, itemKey, position, channelId, generation) " +
                    "VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>(feedKey, "channel:old", 1, "old", 1),
        )
        sqlite.execSQL(
            "INSERT INTO stream_feed_states (" +
                    "feedKey, lastSuccessAt, lastAccessAt, activeGeneration) VALUES (?, NULL, ?, ?)",
            arrayOf<Any?>(feedKey, 1_000L, 2),
        )
        sqlite.execSQL("PRAGMA user_version = 43")
        database.close()
    }

    private fun prepareVersion43Database(name: String) {
        context.deleteDatabase(name)
        databaseNames += name
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL("DROP TABLE IF EXISTS favorite_emotes")
        dropViewingStatsTables(sqlite)
        sqlite.execSQL("DROP INDEX IF EXISTS index_stream_feed_items_feedKey_position")
        sqlite.execSQL("DROP INDEX IF EXISTS index_stream_feed_items_feedKey_channelId")
        sqlite.execSQL("DROP TABLE IF EXISTS stream_feed_items")
        sqlite.execSQL("DROP TABLE IF EXISTS stream_feed_states")
        sqlite.execSQL(
            "CREATE TABLE stream_feed_items (" +
                    "feedKey TEXT NOT NULL, itemKey TEXT NOT NULL, position INTEGER NOT NULL, " +
                    "streamId TEXT, channelId TEXT, channelLogin TEXT, channelName TEXT, " +
                    "channelImageURL TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, title TEXT, " +
                    "thumbnailURL TEXT, createdAt TEXT, viewerCount INTEGER, tags TEXT, " +
                    "generation INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(feedKey, itemKey))"
        )
        sqlite.execSQL("CREATE INDEX index_stream_feed_items_feedKey_position ON stream_feed_items(feedKey, position)")
        sqlite.execSQL("CREATE INDEX index_stream_feed_items_feedKey_channelId ON stream_feed_items(feedKey, channelId)")
        sqlite.execSQL(
            "CREATE TABLE stream_feed_states (" +
                    "feedKey TEXT NOT NULL PRIMARY KEY, nextCursor TEXT, lastSuccessAt INTEGER, " +
                    "lastAttemptAt INTEGER, lastAccessAt INTEGER NOT NULL, " +
                    "failureBackoffUntil INTEGER, rateLimitUntil INTEGER, nextCursorApi TEXT, " +
                    "activeGeneration INTEGER NOT NULL DEFAULT 0)"
        )
        sqlite.execSQL("PRAGMA user_version = 43")
        database.close()
    }

    private fun prepareVersion44Database(name: String) {
        context.deleteDatabase(name)
        databaseNames += name
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        database.openHelper.writableDatabase.execSQL("DROP TABLE IF EXISTS favorite_emotes")
        dropViewingStatsTables(database.openHelper.writableDatabase)
        database.openHelper.writableDatabase.execSQL("PRAGMA user_version = 44")
        database.close()
    }

    private fun prepareVersion45WithoutFavorites(name: String) {
        context.deleteDatabase(name)
        databaseNames += name
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL("DROP TABLE IF EXISTS favorite_emotes")
        sqlite.execSQL("INSERT INTO recent_emotes (name, used_at) VALUES ('legacy', 1000)")
        sqlite.execSQL("PRAGMA user_version = 45")
        database.close()
    }

    private fun dropViewingStatsTables(sqlite: SupportSQLiteDatabase) {
        sqlite.execSQL("DROP INDEX IF EXISTS index_viewing_intervals_session_id")
        sqlite.execSQL("DROP INDEX IF EXISTS index_viewing_intervals_content_type_start_at")
        sqlite.execSQL("DROP INDEX IF EXISTS index_viewing_intervals_category_id_start_at")
        sqlite.execSQL("DROP INDEX IF EXISTS index_viewing_intervals_channel_id_start_at")
        sqlite.execSQL("DROP INDEX IF EXISTS index_viewing_intervals_start_at")
        sqlite.execSQL("DROP TABLE IF EXISTS viewing_intervals")
        sqlite.execSQL("DROP INDEX IF EXISTS index_viewing_sessions_channel_id_started_at")
        sqlite.execSQL("DROP INDEX IF EXISTS index_viewing_sessions_started_at")
        sqlite.execSQL("DROP TABLE IF EXISTS viewing_sessions")
    }

    private fun insertLegacyViewingStats(name: String) {
        val sqlite = SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        createLegacyViewingStatsTables { sql -> sqlite.execSQL(sql) }
        sqlite.execSQL(
            "INSERT INTO viewing_sessions VALUES (9001, 'channel-a', 'channel-a', 'Channel A', NULL, " +
                    "'live', 'stream-a', 1000, 61000, 60000, 61000)"
        )
        sqlite.execSQL(
            "INSERT INTO viewing_intervals VALUES (9001, 9001, 'channel-a', 'channel-a', 'Channel A', NULL, " +
                    "1000, 61000, 60000, 61000)"
        )
        sqlite.close()
    }

    private fun createLegacyViewingStatsTables(execSql: (String) -> Unit) {
        execSql(
            "CREATE TABLE viewing_sessions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, channel_id TEXT NOT NULL, " +
                    "channel_login TEXT, channel_name TEXT, channel_image TEXT, " +
                    "content_type TEXT NOT NULL, content_id TEXT, started_at INTEGER NOT NULL, " +
                    "ended_at INTEGER NOT NULL, watched_ms INTEGER NOT NULL, last_checkpoint_at INTEGER NOT NULL)"
        )
        execSql(
            "CREATE TABLE viewing_intervals (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, session_id INTEGER NOT NULL, " +
                    "channel_id TEXT NOT NULL, channel_login TEXT, channel_name TEXT, channel_image TEXT, " +
                    "start_at INTEGER NOT NULL, end_at INTEGER NOT NULL, watched_ms INTEGER NOT NULL, " +
                    "last_checkpoint_at INTEGER NOT NULL, FOREIGN KEY(session_id) REFERENCES viewing_sessions(id) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        execSql("CREATE INDEX index_viewing_sessions_started_at ON viewing_sessions(started_at)")
        execSql(
            "CREATE INDEX index_viewing_sessions_channel_id_started_at " +
                    "ON viewing_sessions(channel_id, started_at)"
        )
        execSql("CREATE INDEX index_viewing_intervals_start_at ON viewing_intervals(start_at)")
        execSql(
            "CREATE INDEX index_viewing_intervals_channel_id_start_at " +
                    "ON viewing_intervals(channel_id, start_at)"
        )
        execSql("CREATE INDEX index_viewing_intervals_session_id ON viewing_intervals(session_id)")
    }

    private fun assertRetainedViewingStats(database: SupportSQLiteDatabase) {
        database.query(
            "SELECT category_id, category_name, content_type, content_id, watched_ms " +
                    "FROM viewing_intervals WHERE id = 9001"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
            assertEquals("live", cursor.getString(2))
            assertEquals("stream-a", cursor.getString(3))
            assertEquals(60000L, cursor.getLong(4))
        }
    }

    private fun assertStatisticsSchema(database: SupportSQLiteDatabase) {
        assertEquals(AppDatabase.VERSION, scalarInt(database, "PRAGMA user_version"))
        assertTrue(tableExists(database, "viewing_sessions"))
        assertTrue(tableExists(database, "viewing_intervals"))
        assertTrue(indexExists(database, "index_viewing_sessions_started_at"))
        assertTrue(indexExists(database, "index_viewing_intervals_start_at"))
        assertTrue(indexExists(database, "index_viewing_intervals_category_id_start_at"))
        assertTrue(columnExists(database, "viewing_intervals", "category_id"))
        assertTrue(columnExists(database, "viewing_intervals", "content_type"))
    }

    private fun assertStreamFeedSchema(database: SupportSQLiteDatabase) {
        assertTrue(tableExists(database, "stream_feed_items"))
        assertTrue(tableExists(database, "stream_feed_states"))
        assertTrue(indexExists(database, "index_stream_feed_items_feedKey_position"))
        assertTrue(indexExists(database, "index_stream_feed_items_feedKey_channelId"))
        assertTrue(columnExists(database, "stream_feed_items", "generation"))
        assertTrue(columnExists(database, "stream_feed_states", "nextCursorApi"))
        assertTrue(columnExists(database, "stream_feed_states", "activeGeneration"))
        assertTrue(columnExists(database, "stream_feed_states", "staleTailRetainedAt"))
        assertTrue(tableExists(database, "metadata_cache"))
        assertTrue(indexExists(database, "index_metadata_cache_lastAccessAt"))
    }

    private fun assertGameFeedSchema(database: SupportSQLiteDatabase) {
        assertTrue(tableExists(database, "game_feed_items"))
        assertTrue(tableExists(database, "game_feed_states"))
        assertTrue(indexExists(database, "index_game_feed_items_feedKey_position"))
        assertTrue(columnExists(database, "game_feed_items", "generation"))
        assertTrue(columnExists(database, "game_feed_states", "nextCursorApi"))
        assertTrue(columnExists(database, "game_feed_states", "activeGeneration"))
        assertTrue(columnExists(database, "game_feed_states", "staleTailRetainedAt"))
    }

    private fun tableExists(database: SupportSQLiteDatabase, tableName: String): Boolean {
        return database.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(tableName),
        ).use { it.moveToFirst() }
    }

    private fun indexExists(database: SupportSQLiteDatabase, indexName: String): Boolean {
        return database.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(indexName),
        ).use { it.moveToFirst() }
    }

    private fun scalarInt(database: SupportSQLiteDatabase, query: String): Int {
        return database.query(query).use {
            check(it.moveToFirst())
            it.getInt(0)
        }
    }

    private fun columnExists(database: SupportSQLiteDatabase, tableName: String, columnName: String): Boolean {
        return database.query("PRAGMA table_info($tableName)").use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == columnName) return@use true
            }
            false
        }
    }
}
