package com.github.andreyasadchy.xtra.ui.settings

import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import java.io.File

/** Captures committed WAL pages without checkpointing or closing the app's live database. */
internal object DatabaseBackupSnapshot {
    fun capture(database: RoomDatabase, source: File, destination: File): Int {
        val snapshotWal = File(destination.path + "-wal")
        database.runInTransaction {
            // The write transaction pins the WAL and blocks writers during both copies.
            source.copyTo(destination)
            val sourceWal = File(source.path + "-wal")
            if (sourceWal.exists()) sourceWal.copyTo(snapshotWal)
        }
        return SQLiteDatabase.openDatabase(destination.path, null, SQLiteDatabase.OPEN_READWRITE).use { snapshot ->
            snapshot.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                    "Unable to checkpoint the backup snapshot"
                }
            }
            snapshot.version
        }
    }
}
