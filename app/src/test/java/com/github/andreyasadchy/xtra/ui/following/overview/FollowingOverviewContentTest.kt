package com.github.andreyasadchy.xtra.ui.following.overview

import com.github.andreyasadchy.xtra.model.VideoHistory
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.model.ui.UpcomingStream
import com.github.andreyasadchy.xtra.repository.TwitchApiException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowingOverviewContentTest {

    @Test
    fun recentFollowedVideosFillAnEmptyContinueWatchingShelf() {
        val result = mergeContinueWatching(
            localHistory = emptyList(),
            recentVideos = listOf(Video(id = "123", title = "Recent archive")),
            limit = 20,
        )

        assertEquals(listOf(123L), result.map { it.id })
        assertEquals("Recent archive", result.single().title)
    }

    @Test
    fun localResumeItemsStayAheadOfRecentArchives() {
        val local = VideoHistory(
            id = 1,
            position = 60_000,
            durationSeconds = 120,
            channelId = null,
            channelLogin = null,
            channelName = "Local channel",
            channelImageURL = null,
            title = "Resume me",
            thumbnailURL = null,
            gameId = null,
            gameSlug = null,
            gameName = null,
            createdAt = null,
            updatedAt = 1,
        )

        val result = mergeContinueWatching(
            localHistory = listOf(local),
            recentVideos = listOf(
                Video(id = "1", title = "Duplicate"),
                Video(id = "2", title = "New archive"),
            ),
            limit = 20,
        )

        assertEquals(listOf(1L, 2L), result.map { it.id })
        assertEquals(60_000, result.first().position)
    }

    @Test
    fun localArchivesAreMergedAndSortedWithRemoteArchives() {
        val result = mergeRecentVideos(
            remoteVideos = listOf(
                Video(id = "1", title = "Remote archive", createdAt = "2026-08-20T10:00:00Z"),
            ),
            localVideos = listOf(
                Video(id = "2", title = "Local archive", createdAt = "2026-08-21T10:00:00Z"),
            ),
            limit = 20,
        )

        assertEquals(listOf("2", "1"), result.map { it.id })
    }

    @Test
    fun graphqlVideosWithFailedLocalSupplementAreNotAuthoritative() {
        val result = mergeRecentVideosWithSupplement(
            remoteVideos = listOf(Video(id = "remote", title = "GraphQL video")),
            localVideos = null,
            limit = 20,
        )

        assertNull(result)
    }

    @Test
    fun authenticatedFollowLookupFailureDoesNotUseLocalFallback() {
        assertNull(
            fallbackToLocalChannels(
                remoteLookupAttempted = true,
                localChannels = listOf("local-channel"),
            ),
        )
    }

    @Test
    fun localOnlyFollowModeUsesLocalFallback() {
        assertEquals(
            listOf("local-channel"),
            fallbackToLocalChannels(
                remoteLookupAttempted = false,
                localChannels = listOf("local-channel"),
            ),
        )
    }

    @Test
    fun localOnlyModeWithNoFollowsIsAuthoritativeEmpty() {
        assertEquals(
            emptyList<String>(),
            fallbackToLocalChannels(
                remoteLookupAttempted = false,
                localChannels = emptyList<String>(),
            ),
        )
    }

    @Test
    fun partialVideoChannelFailureIsNotAnAuthoritativeBatch() {
        val result = combineChannelItems(
            listOf(
                ChannelItemsResult.Success(listOf(Video(id = "fresh"))),
                ChannelItemsResult.Failure,
            ),
        )

        assertNull(result)
    }

    @Test
    fun partialUpcomingScheduleFailureIsNotAnAuthoritativeBatch() {
        val result = combineChannelItems(
            listOf(
                ChannelItemsResult.Success(
                    listOf(
                        UpcomingStream(
                            id = "channel:segment",
                            channelId = "channel",
                            channelLogin = "channel",
                            channelName = "Channel",
                            channelImageURL = null,
                            title = "Stream",
                            gameName = null,
                            startTimeMillis = 2,
                            endTimeMillis = null,
                            isRecurring = false,
                        ),
                    ),
                ),
                ChannelItemsResult.Failure,
            ),
        )

        assertNull(result)
    }

    @Test
    fun completeChannelFailureIsNotAnAuthoritativeBatch() {
        assertNull(
            combineChannelItems<Video>(
                listOf(ChannelItemsResult.Failure, ChannelItemsResult.Failure),
            ),
        )
    }

    @Test
    fun schedule404IsAnAuthoritativeEmptyResult() = runBlocking {
        val result = loadChannelItems(
            request = { throw TwitchApiException(404, null, message = "No schedule") },
            notFoundItems = emptyList<UpcomingStream>(),
        )

        assertTrue(result is ChannelItemsResult.Success)
        assertEquals(emptyList<UpcomingStream>(), (result as ChannelItemsResult.Success).items)
    }

    @Test
    fun non404ScheduleFailureRemainsARefreshFailure() = runBlocking {
        val result = loadChannelItems<UpcomingStream>(
            request = { throw TwitchApiException(429, null, message = "Rate limited") },
            notFoundItems = emptyList(),
        )

        assertEquals(ChannelItemsResult.Failure, result)
    }
}
