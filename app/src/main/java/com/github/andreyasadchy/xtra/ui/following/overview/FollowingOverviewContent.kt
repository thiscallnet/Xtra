package com.github.andreyasadchy.xtra.ui.following.overview

import com.github.andreyasadchy.xtra.model.VideoHistory
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.repository.TwitchApiException
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant

internal sealed interface ChannelItemsResult<out T> {
    data class Success<T>(val items: List<T>) : ChannelItemsResult<T>

    data object Failure : ChannelItemsResult<Nothing>
}

internal suspend fun <T> loadChannelItems(
    request: suspend () -> List<T>,
    notFoundItems: List<T>? = null,
): ChannelItemsResult<T> {
    return try {
        ChannelItemsResult.Success(request())
    } catch (e: CancellationException) {
        throw e
    } catch (e: TwitchApiException) {
        if (e.statusCode == 404 && notFoundItems != null) {
            ChannelItemsResult.Success(notFoundItems)
        } else {
            ChannelItemsResult.Failure
        }
    } catch (_: Exception) {
        ChannelItemsResult.Failure
    }
}

internal fun <T> combineChannelItems(results: List<ChannelItemsResult<T>>): List<T>? {
    if (results.any { it === ChannelItemsResult.Failure }) return null
    return results.map { (it as ChannelItemsResult.Success<T>).items }.flatten()
}

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

internal fun mergeRecentVideosWithSupplement(
    remoteVideos: List<Video>,
    localVideos: List<Video>?,
    limit: Int,
): List<Video>? = localVideos?.let { mergeRecentVideos(remoteVideos, it, limit) }

internal fun <T> fallbackToLocalChannels(
    remoteLookupAttempted: Boolean,
    localChannels: List<T>,
): List<T>? = if (remoteLookupAttempted) null else localChannels
