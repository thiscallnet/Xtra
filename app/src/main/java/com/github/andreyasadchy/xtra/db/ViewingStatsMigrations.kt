package com.github.andreyasadchy.xtra.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Statistics migrations are kept separate so both upgrade paths can be
 * exercised without constructing the application's network dependencies.
 */
object ViewingStatsMigrations {

    /** The current released database is version 38. */
    val FROM_38 = Migration(38, 40) { db ->
        createStatisticsSchema(db)
    }

    /**
     * Version 39 existed historically with the removed live notification log
     * table. Normalize that schema to the current version-38 shape first.
     */
    val FROM_HISTORICAL_39 = Migration(39, 40) { db ->
        db.execSQL("DROP TABLE IF EXISTS live_notification_logs")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS notification_events (" +
                    "eventId TEXT NOT NULL, channelId TEXT NOT NULL, streamId TEXT, " +
                    "channelLogin TEXT, channelName TEXT, channelImageURL TEXT, gameName TEXT, " +
                    "title TEXT, thumbnailURL TEXT, createdAt TEXT, viewerCount INTEGER, " +
                    "startedAt INTEGER NOT NULL, queuedAt INTEGER NOT NULL, PRIMARY KEY (eventId))"
        )
        createStatisticsSchema(db)
    }

    private fun createStatisticsSchema(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS viewing_sessions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "channel_id TEXT NOT NULL, channel_login TEXT, channel_name TEXT, " +
                    "channel_image TEXT, content_type TEXT NOT NULL, content_id TEXT, " +
                    "started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL, watched_ms INTEGER NOT NULL, " +
                    "last_checkpoint_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_viewing_sessions_started_at " +
                    "ON viewing_sessions(started_at)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_viewing_sessions_channel_id_started_at " +
                    "ON viewing_sessions(channel_id, started_at)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS viewing_intervals (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, session_id INTEGER NOT NULL, " +
                    "channel_id TEXT NOT NULL, channel_login TEXT, channel_name TEXT, channel_image TEXT, " +
                    "start_at INTEGER NOT NULL, end_at INTEGER NOT NULL, watched_ms INTEGER NOT NULL, " +
                    "last_checkpoint_at INTEGER NOT NULL, FOREIGN KEY(session_id) REFERENCES viewing_sessions(id) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_viewing_intervals_start_at " +
                    "ON viewing_intervals(start_at)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_viewing_intervals_channel_id_start_at " +
                    "ON viewing_intervals(channel_id, start_at)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_viewing_intervals_session_id " +
                    "ON viewing_intervals(session_id)"
        )
    }
}
