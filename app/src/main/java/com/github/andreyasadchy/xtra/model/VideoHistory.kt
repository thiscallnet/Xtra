package com.github.andreyasadchy.xtra.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Metadata needed to render Continue Watching without a network round trip. */
@Entity(tableName = "video_history", indices = [Index("updatedAt"), Index("channelId")])
data class VideoHistory(
    @PrimaryKey val id: Long,
    val position: Long,
    val durationSeconds: Int?,
    val channelId: String?,
    val channelLogin: String?,
    val channelName: String?,
    val channelImageURL: String?,
    val title: String?,
    val thumbnailURL: String?,
    val gameId: String?,
    val gameSlug: String?,
    val gameName: String?,
    val createdAt: String?,
    val updatedAt: Long,
)
