package com.github.andreyasadchy.xtra.model

import androidx.room.Entity
import androidx.room.Index

/** A stream start observed for a followed channel, rounded to the minute. */
@Entity(
    tableName = "channel_stream_starts",
    primaryKeys = ["channelId", "startedAt"],
    indices = [Index(value = ["startedAt"])],
)
data class ChannelStreamStart(
    val channelId: String,
    val startedAt: Long,
)
