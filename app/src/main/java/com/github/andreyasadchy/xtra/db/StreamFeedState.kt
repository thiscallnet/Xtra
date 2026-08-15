package com.github.andreyasadchy.xtra.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Refresh and pagination metadata for a cached stream feed. */
@Entity(tableName = "stream_feed_states")
data class StreamFeedState(
    @PrimaryKey val feedKey: String,
    val nextCursor: String? = null,
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val lastAccessAt: Long = 0L,
    val failureBackoffUntil: Long? = null,
    val rateLimitUntil: Long? = null,
    /** API identity for the persisted cursor; cursors are API-specific. */
    val nextCursorApi: String? = null,
    /** Generation currently being refreshed/appended for this feed. */
    val activeGeneration: Long = 0L,
    /** When the retained stale tail started being kept behind the active generation. */
    val staleTailRetainedAt: Long? = null,
)
