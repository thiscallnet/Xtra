package com.github.andreyasadchy.xtra.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "shown_notifications",
    indices = [Index(value = ["streamId"], unique = true)],
)
class ShownNotification(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val channelId: String,
    val streamId: String,
    val startedAt: Long,
) {
    /** Compatibility for callers that mark the currently viewed stream without its Twitch ID. */
    constructor(channelId: String, startedAt: Long) : this(
        channelId = channelId,
        streamId = legacyStreamId(channelId, startedAt),
        startedAt = startedAt,
    )

    companion object {
        fun legacyStreamId(channelId: String, startedAt: Long): String = "legacy:$channelId:$startedAt"
    }
}
