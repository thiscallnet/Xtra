package com.github.andreyasadchy.xtra.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object NotificationMigrations {

    val FROM_50 = Migration(50, 51) { db: SupportSQLiteDatabase ->
        db.execSQL(
            "CREATE TABLE shown_notifications_new (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "channelId TEXT NOT NULL, streamId TEXT NOT NULL, startedAt INTEGER NOT NULL)"
        )
        db.execSQL(
            "INSERT INTO shown_notifications_new (channelId, streamId, startedAt) " +
                    "SELECT channelId, 'legacy:' || channelId || ':' || startedAt, startedAt " +
                    "FROM shown_notifications"
        )
        db.execSQL("DROP TABLE shown_notifications")
        db.execSQL("ALTER TABLE shown_notifications_new RENAME TO shown_notifications")
        db.execSQL(
            "CREATE UNIQUE INDEX index_shown_notifications_streamId " +
                    "ON shown_notifications(streamId)"
        )
    }
}
