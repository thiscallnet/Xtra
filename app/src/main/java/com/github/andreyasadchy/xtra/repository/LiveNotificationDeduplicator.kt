package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.ShownNotificationsDao
import com.github.andreyasadchy.xtra.model.ShownNotification
import com.github.andreyasadchy.xtra.model.ui.Stream
import kotlin.time.Instant

/**
 * Decides which live streams should become notifications and persists the decision.
 *
 * This deliberately sits between fetching and notification delivery so the decision can be
 * exercised without constructing the Android service or Twitch clients.
 */
internal class LiveNotificationDeduplicator(
    private val shownNotificationsDao: ShownNotificationsDao,
) {

    suspend fun processStreams(streams: List<Stream>): List<Stream> {
        val liveStreams = streams.distinctBy { it.channelId ?: it.id }
        return liveStreams.mapNotNull { stream ->
            val channelId = stream.channelId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // Live stream loaders reject incomplete payloads before this point. A Twitch stream
            // ID is required because channel/start time cannot safely identify a broadcast.
            val streamId = stream.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val startedAt = stream.startedAtMillis() ?: return@mapNotNull null
            val inserted = shownNotificationsDao.insert(
                ShownNotification(
                    channelId = channelId,
                    streamId = streamId,
                    startedAt = startedAt,
                )
            )
            if (inserted == -1L) {
                return@mapNotNull null
            }

            // Databases upgraded from the old channel/start-time schema cannot recover Twitch's
            // stream ID. Migrate an exact legacy match without generating a duplicate alert.
            shownNotificationsDao.getByStreamId(ShownNotification.legacyStreamId(channelId, startedAt))?.let {
                shownNotificationsDao.deleteList(listOf(it))
                return@mapNotNull null
            }
            stream
        }
    }

    private fun Stream.startedAtMillis(): Long? = createdAt
        ?.takeIf { it.isNotBlank() }
        ?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }
        ?.takeIf { it > 0 }
}
