package com.github.andreyasadchy.xtra.ui.following.overview

import com.github.andreyasadchy.xtra.model.ui.Video
import kotlin.time.Instant

private const val STILL_RECORDING_GUARD_MS = 5 * 60_000L

/**
 * Picks one archive VOD per channel whose broadcast ended inside [windowMs].
 * Live channels are skipped because their newest VOD is still the current broadcast, and so is
 * any VOD that ended less than [STILL_RECORDING_GUARD_MS] ago, since a recording in progress looks the same.
 */
internal fun recentlyOfflineVideos(
    channelVideos: List<Video>,
    liveChannelIds: Set<String>,
    nowMs: Long,
    windowMs: Long,
    limit: Int,
): List<Video> {
    return channelVideos
        .asSequence()
        .filter { video -> video.channelId != null && video.channelId !in liveChannelIds }
        .mapNotNull { video ->
            val startedAt = video.createdAt?.let(Instant::parseOrNull)?.toEpochMilliseconds() ?: return@mapNotNull null
            val durationMs = (video.durationSeconds ?: return@mapNotNull null) * 1000L
            val endedAt = startedAt + durationMs
            if (endedAt > nowMs - STILL_RECORDING_GUARD_MS || endedAt < nowMs - windowMs) null else video to endedAt
        }
        .groupBy { (video, _) -> video.channelId }
        .values
        .map { entries -> entries.maxBy { (_, endedAt) -> endedAt } }
        .sortedByDescending { (_, endedAt) -> endedAt }
        .take(limit)
        .map { (video, _) -> video }
}
