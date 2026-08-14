package com.github.andreyasadchy.xtra.db

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    fun migrateCurrentVersion38To40CreatesStatisticsSchema() {
        val name = "migration-v38.db"
        prepareDatabase(name, version = 38, historicalVersion39 = false)

        val database = openMigratedDatabase(name)
        assertStatisticsSchema(database.openHelper.readableDatabase)
        database.close()
    }

    @Test
    fun migrateHistoricalVersion39NormalizesAndCreatesStatisticsSchema() {
        val name = "migration-historical-v39.db"
        prepareDatabase(name, version = 39, historicalVersion39 = true)

        val database = openMigratedDatabase(name)
        val sqlite = database.openHelper.readableDatabase
        assertStatisticsSchema(sqlite)
        assertTrue(tableExists(sqlite, "notification_events"))
        assertFalse(tableExists(sqlite, "live_notification_logs"))
        database.close()
    }

    private fun openMigratedDatabase(name: String): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(
                ViewingStatsMigrations.FROM_38,
                ViewingStatsMigrations.FROM_HISTORICAL_39,
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
        if (historicalVersion39) {
            sqlite.execSQL("DROP TABLE IF EXISTS notification_events")
            sqlite.execSQL("CREATE TABLE live_notification_logs (id INTEGER PRIMARY KEY NOT NULL)")
        }
        sqlite.execSQL("PRAGMA user_version = $version")
        database.close()
    }

    private fun assertStatisticsSchema(database: SupportSQLiteDatabase) {
        assertEquals(40, scalarInt(database, "PRAGMA user_version"))
        assertTrue(tableExists(database, "viewing_sessions"))
        assertTrue(tableExists(database, "viewing_intervals"))
        assertTrue(indexExists(database, "index_viewing_sessions_started_at"))
        assertTrue(indexExists(database, "index_viewing_intervals_start_at"))
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
}
