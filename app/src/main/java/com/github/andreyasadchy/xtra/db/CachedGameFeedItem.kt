package com.github.andreyasadchy.xtra.db

import androidx.room.Entity
import androidx.room.Index

/** Durable representation of one game in one cached category feed. */
@Entity(
    tableName = "game_feed_items",
    primaryKeys = ["feedKey", "itemKey"],
    indices = [
        Index(value = ["feedKey", "position"]),
    ],
)
data class CachedGameFeedItem(
    val feedKey: String,
    val itemKey: String,
    val position: Int,
    val gameId: String? = null,
    val gameSlug: String? = null,
    val gameName: String? = null,
    val boxArtURL: String? = null,
    val viewerCount: Int? = null,
    val broadcasterCount: Int? = null,
    val tags: String? = null,
    val generation: Long = 0L,
)
