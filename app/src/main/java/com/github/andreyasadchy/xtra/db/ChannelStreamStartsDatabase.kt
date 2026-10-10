package com.github.andreyasadchy.xtra.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.github.andreyasadchy.xtra.model.ChannelStreamStart

/**
 * Separate from [AppDatabase] so older releases never open or migrate this file.
 * Settings backups carry its rows separately as stream-starts.json, so the schema stays free to change.
 */
@Database(
    entities = [ChannelStreamStart::class],
    version = 1,
    exportSchema = false,
)
abstract class ChannelStreamStartsDatabase : RoomDatabase() {
    abstract fun channelStreamStarts(): ChannelStreamStartsDao
}
