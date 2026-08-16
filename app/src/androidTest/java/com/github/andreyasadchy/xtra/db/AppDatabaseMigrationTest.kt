package com.github.andreyasadchy.xtra.db

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedCache
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedFreshnessPolicy
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedKey
import kotlinx.coroutines.runBlocking
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
    fun migrateCurrentVersion38To44CreatesStatisticsAndStreamFeedSchema() {
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

    private fun openMigratedDatabase(name: String): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(
                ViewingStatsMigrations.FROM_38,
                ViewingStatsMigrations.FROM_HISTORICAL_39,
                StreamFeedMigrations.FROM_40,
                StreamFeedMigrations.FROM_41,
                MetadataCacheMigrations.FROM_42,
                StreamFeedMigrations.FROM_43,
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
        if (historicalVersion39) {
            sqlite.execSQL("DROP TABLE IF EXISTS notification_events")
            sqlite.execSQL("CREATE TABLE live_notification_logs (id INTEGER PRIMARY KEY NOT NULL)")
        }
        sqlite.execSQL("PRAGMA user_version = $version")
        database.close()
    }

    private fun prepareInvalidatedV43Feed(name: String, feedKey: String) {
        context.deleteDatabase(name)
        databaseNames += name
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val sqlite = database.openHelper.writableDatabase
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

    private fun assertStatisticsSchema(database: SupportSQLiteDatabase) {
        assertEquals(44, scalarInt(database, "PRAGMA user_version"))
        assertTrue(tableExists(database, "viewing_sessions"))
        assertTrue(tableExists(database, "viewing_intervals"))
        assertTrue(indexExists(database, "index_viewing_sessions_started_at"))
        assertTrue(indexExists(database, "index_viewing_intervals_start_at"))
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
