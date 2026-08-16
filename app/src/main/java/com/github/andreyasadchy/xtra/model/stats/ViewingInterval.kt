package com.github.andreyasadchy.xtra.model.stats

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "viewing_intervals",
    foreignKeys = [
        ForeignKey(
            entity = ViewingSession::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["start_at"]),
        Index(value = ["channel_id", "start_at"]),
        Index(value = ["category_id", "start_at"]),
        Index(value = ["content_type", "start_at"]),
        Index(value = ["session_id"]),
    ],
)
data class ViewingInterval(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
    @ColumnInfo(name = "channel_id")
    val channelId: String,
    @ColumnInfo(name = "channel_login")
    val channelLogin: String?,
    @ColumnInfo(name = "channel_name")
    val channelName: String?,
    @ColumnInfo(name = "channel_image")
    val channelImage: String?,
    @ColumnInfo(name = "category_id")
    val categoryId: String? = null,
    @ColumnInfo(name = "category_name")
    val categoryName: String? = null,
    @ColumnInfo(name = "category_image")
    val categoryImage: String? = null,
    @ColumnInfo(name = "content_type")
    val contentType: String = ViewingPlaybackMetadata.CONTENT_TYPE_UNKNOWN,
    @ColumnInfo(name = "content_id")
    val contentId: String? = null,
    @ColumnInfo(name = "stream_title")
    val streamTitle: String? = null,
    @ColumnInfo(name = "start_at")
    val startAt: Long,
    @ColumnInfo(name = "end_at")
    val endAt: Long,
    @ColumnInfo(name = "watched_ms")
    val watchedMs: Long,
    @ColumnInfo(name = "last_checkpoint_at")
    val lastCheckpointAt: Long,
)
