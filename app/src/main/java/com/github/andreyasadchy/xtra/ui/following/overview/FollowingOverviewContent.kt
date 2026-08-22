package com.github.andreyasadchy.xtra.ui.following.overview

import com.github.andreyasadchy.xtra.model.VideoHistory
import com.github.andreyasadchy.xtra.model.ui.Video
import kotlin.time.Instant

internal fun mergeContinueWatching(
    localHistory: List<VideoHistory>,
    recentVideos: List<Video>,
    limit: Int,
): List<VideoHistory> {
    val localIds = localHistory.mapTo(hashSetOf()) { it.id }
    val remoteItems = recentVideos.mapNotNull { video ->
        val id = video.id?.toLongOrNull() ?: return@mapNotNull null
        if (id in localIds) return@mapNotNull null
        VideoHistory(
            id = id,
            position = 0,
            durationSeconds = video.durationSeconds,
            channelId = video.channelId,
            channelLogin = video.channelLogin,
            channelName = video.channelName,
            channelImageURL = video.channelImageURL,
            title = video.title,
            thumbnailURL = video.thumbnailURL,
            gameId = video.gameId,
            gameSlug = video.gameSlug,
            gameName = video.gameName,
            createdAt = video.createdAt,
            updatedAt = 0,
        )
    }
    return (localHistory + remoteItems).take(limit)
}

internal fun mergeRecentVideos(
    remoteVideos: List<Video>,
    localVideos: List<Video>,
    limit: Int,
): List<Video> {
    return (remoteVideos + localVideos)
        .distinctBy { it.id }
        .sortedByDescending { it.createdAt?.let(Instant::parseOrNull)?.toEpochMilliseconds() ?: 0L }
        .take(limit)
}
