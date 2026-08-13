package com.github.andreyasadchy.xtra.model.stats

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "viewing_sessions",
    indices = [
        Index(value = ["started_at"]),
        Index(value = ["channel_id", "started_at"]),
    ],
)
data class ViewingSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "channel_id")
    val channelId: String,
    @ColumnInfo(name = "channel_login")
    val channelLogin: String?,
    @ColumnInfo(name = "channel_name")
    val channelName: String?,
    @ColumnInfo(name = "channel_image")
    val channelImage: String?,
    @ColumnInfo(name = "content_type")
    val contentType: String,
    @ColumnInfo(name = "content_id")
    val contentId: String?,
    @ColumnInfo(name = "started_at")
    val startedAt: Long,
    @ColumnInfo(name = "ended_at")
    val endedAt: Long,
    @ColumnInfo(name = "watched_ms")
    val watchedMs: Long,
    @ColumnInfo(name = "last_checkpoint_at")
    val lastCheckpointAt: Long,
)
