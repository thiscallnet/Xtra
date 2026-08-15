package com.github.andreyasadchy.xtra.db

import androidx.room.Entity
import androidx.room.Index

/**
 * The durable representation of one live stream card in one feed variant.
 *
 * The feed key is deliberately part of the identity: the same channel can be
 * present in Top, Followed, and multiple game/filter feeds without the feeds
 * overwriting each other.
 */
@Entity(
    tableName = "stream_feed_items",
    primaryKeys = ["feedKey", "itemKey"],
    indices = [
        Index(value = ["feedKey", "position"]),
        Index(value = ["feedKey", "channelId"]),
    ],
)
data class CachedStreamFeedItem(
    val feedKey: String,
    val itemKey: String,
    val position: Int,
    val streamId: String? = null,
    val channelId: String? = null,
    val channelLogin: String? = null,
    val channelName: String? = null,
    val channelImageURL: String? = null,
    val gameId: String? = null,
    val gameSlug: String? = null,
    val gameName: String? = null,
    val title: String? = null,
    val thumbnailURL: String? = null,
    val createdAt: String? = null,
    val viewerCount: Int? = null,
    val tags: String? = null,
    /** Refresh generation used to retain stale deep-page rows during SWR. */
    val generation: Long = 0L,
)
