package com.github.andreyasadchy.xtra.model.chat

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "emote_usage",
    primaryKeys = ["viewer_id", "usage_key"],
    indices = [
        Index(value = ["viewer_id", "channel_id"]),
        Index(value = ["viewer_id", "provider", "emote_id"]),
    ],
)
data class EmoteUsage(
    @ColumnInfo(name = "viewer_id")
    val viewerId: String,
    @ColumnInfo(name = "usage_key")
    val usageKey: String,
    val provider: String,
    @ColumnInfo(name = "emote_id")
    val emoteId: String,
    val scope: String,
    @ColumnInfo(name = "channel_id")
    val channelId: String?,
    @ColumnInfo(name = "use_count")
    val useCount: Long,
    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long,
)
