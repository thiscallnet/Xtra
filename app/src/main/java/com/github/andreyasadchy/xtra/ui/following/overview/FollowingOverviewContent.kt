package com.github.andreyasadchy.xtra.ui.following.overview

import com.github.andreyasadchy.xtra.model.VideoHistory
import com.github.andreyasadchy.xtra.model.gql.schedule.StreamScheduleResponse
import com.github.andreyasadchy.xtra.model.ui.UpcomingStream
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

internal fun <T> combineAvailableChannelItems(results: List<ChannelItemsResult<T>>): List<T>? {
    // One transient schedule failure must not hide schedules returned by other channels.
    val successfulItems = results.mapNotNull { result ->
        (result as? ChannelItemsResult.Success<T>)?.items
    }
    return successfulItems.takeIf { it.isNotEmpty() }?.flatten()
}

internal fun selectUpcomingScheduleSegment(
    schedule: StreamScheduleResponse.Schedule?,
    nowMs: Long,
): StreamScheduleResponse.Segment? = schedule?.nextSegment?.takeIf { segment ->
    segment.isCancelled != true &&
            segment.cancelledUntil.isNullOrBlank() &&
            segment.startAt?.let(Instant::parseOrNull)?.toEpochMilliseconds()?.let { it > nowMs } == true
}

internal fun mergeUpcomingStreams(
    channelIds: List<String>,
    results: List<ChannelItemsResult<UpcomingStream>>,
    cachedStreams: List<UpcomingStream>,
    nowMs: Long,
): List<UpcomingStream>? {
    val freshStreams = combineAvailableChannelItems(results) ?: return null
    val failedChannelIds = channelIds.zip(results)
        .mapNotNull { (channelId, result) -> channelId.takeIf { result is ChannelItemsResult.Failure } }
        .toSet()
    val cachedFailedStreams = cachedStreams.filter { stream ->
        stream.channelId in failedChannelIds && stream.startTimeMillis > nowMs
    }
    return (freshStreams + cachedFailedStreams)
        .sortedBy(UpcomingStream::startTimeMillis)
        .distinctBy { it.channelId ?: it.id }
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
