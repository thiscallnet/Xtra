package com.github.andreyasadchy.xtra.ui.following.overview

import com.github.andreyasadchy.xtra.model.VideoHistory
import com.github.andreyasadchy.xtra.model.gql.schedule.StreamScheduleResponse
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
    fun partialUpcomingScheduleFailureStillShowsSuccessfulSchedules() {
        val result = combineAvailableChannelItems(
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

        assertEquals(listOf("channel:segment"), result?.map { it.id })
    }

    @Test
    fun partialUpcomingRefreshKeepsCachedSchedulesForFailedChannels() {
        val result = mergeUpcomingStreams(
            channelIds = listOf("empty", "failed"),
            results = listOf(
                ChannelItemsResult.Success(emptyList()),
                ChannelItemsResult.Failure,
            ),
            cachedStreams = listOf(
                UpcomingStream(
                    id = "failed:cached",
                    channelId = "failed",
                    channelLogin = "failed",
                    channelName = "Failed",
                    channelImageURL = null,
                    title = "Cached stream",
                    gameName = null,
                    startTimeMillis = 2_000,
                    endTimeMillis = null,
                    isRecurring = false,
                ),
                UpcomingStream(
                    id = "failed:expired",
                    channelId = "failed",
                    channelLogin = "failed",
                    channelName = "Failed",
                    channelImageURL = null,
                    title = "Expired stream",
                    gameName = null,
                    startTimeMillis = 500,
                    endTimeMillis = null,
                    isRecurring = false,
                ),
            ),
            nowMs = 1_000,
        )

        assertEquals(listOf("failed:cached"), result?.map { it.id })
    }

    @Test
    fun upcomingMergeKeepsOnlyTheNextStreamPerChannel() {
        val result = mergeUpcomingStreams(
            channelIds = listOf("channel"),
            results = listOf(
                ChannelItemsResult.Success(
                    listOf(
                        UpcomingStream(
                            id = "channel:later",
                            channelId = "channel",
                            channelLogin = "channel",
                            channelName = "Channel",
                            channelImageURL = null,
                            title = "Later",
                            gameName = null,
                            startTimeMillis = 3_000,
                            endTimeMillis = null,
                            isRecurring = false,
                        ),
                        UpcomingStream(
                            id = "channel:next",
                            channelId = "channel",
                            channelLogin = "channel",
                            channelName = "Channel",
                            channelImageURL = null,
                            title = "Next",
                            gameName = null,
                            startTimeMillis = 2_000,
                            endTimeMillis = null,
                            isRecurring = false,
                        ),
                    ),
                ),
            ),
            cachedStreams = emptyList(),
            nowMs = 1_000,
        )

        assertEquals(listOf("channel:next"), result?.map { it.id })
    }

    @Test
    fun nextWeekNextSegmentIsKeptWhenCurrentWeekHasNoSegments() {
        val nextWeekSegment = StreamScheduleResponse.Segment(
            id = "next-week",
            startAt = "2026-09-01T19:30:00Z",
        )

        val result = selectUpcomingScheduleSegment(
            schedule = StreamScheduleResponse.Schedule(
                segments = emptyList(),
                nextSegment = nextWeekSegment,
            ),
            nowMs = 1_000,
        )

        assertEquals("next-week", result?.id)
    }

    @Test
    fun allUpcomingScheduleFailuresRemainARefreshFailure() {
        assertNull(
            combineAvailableChannelItems<UpcomingStream>(
                listOf(ChannelItemsResult.Failure, ChannelItemsResult.Failure),
            ),
        )
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
