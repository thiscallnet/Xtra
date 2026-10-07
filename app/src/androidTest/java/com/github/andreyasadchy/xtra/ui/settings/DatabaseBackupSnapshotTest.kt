package com.github.andreyasadchy.xtra.ui.settings

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.db.AppDatabase
import com.github.andreyasadchy.xtra.model.VideoPosition
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a separate database; never replaces or restores the user's database. */
@RunWith(AndroidJUnit4::class)
class DatabaseBackupSnapshotTest {
    @Test
    fun committedWalPositionsSurviveStandaloneBackup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "backup-verification-${UUID.randomUUID()}"
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        val snapshot = File(context.cacheDir, "$name.snapshot")
        try {
            val source = context.getDatabasePath(name)
            database.openHelper.writableDatabase.query("PRAGMA wal_autocheckpoint=0").use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
            repeat(50) { database.videoPositions().insert(VideoPosition(it.toLong(), it * 1_000L)) }
            assertTrue(File(source.path + "-wal").length() > 32L)

            assertEquals(AppDatabase.VERSION, DatabaseBackupSnapshot.capture(database, source, snapshot))
            // Subsequent writes must neither disappear from the live DB nor leak into the snapshot.
            database.videoPositions().insert(VideoPosition(99L, 99_000L))
            assertEquals(99_000L, database.videoPositions().getById(99L)?.position)
            File(snapshot.path + "-wal").delete()
            File(snapshot.path + "-shm").delete()
            SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READONLY).use { restored ->
                restored.rawQuery("SELECT COUNT(*), SUM(position) FROM video_positions", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(50, cursor.getInt(0))
                    assertEquals(1_225_000L, cursor.getLong(1))
                }
                restored.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("ok", cursor.getString(0))
                }
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
            snapshot.delete()
            File(snapshot.path + "-wal").delete()
            File(snapshot.path + "-shm").delete()
        }
    }
}
