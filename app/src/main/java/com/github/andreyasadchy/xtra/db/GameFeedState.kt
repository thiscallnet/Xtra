package com.github.andreyasadchy.xtra.db

import androidx.room.Entity

/** Refresh and pagination metadata for one cached category feed. */
@Entity(tableName = "game_feed_states")
data class GameFeedState(
    @androidx.room.PrimaryKey val feedKey: String,
    val nextCursor: String? = null,
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val lastAccessAt: Long = 0L,
    val failureBackoffUntil: Long? = null,
    val rateLimitUntil: Long? = null,
    val nextCursorApi: String? = null,
    val activeGeneration: Long = 0L,
    val staleTailRetainedAt: Long? = null,
)
