package com.github.andreyasadchy.xtra.ui.following.overview

import com.github.andreyasadchy.xtra.model.VideoHistory
import com.github.andreyasadchy.xtra.model.ui.Video
import org.junit.Assert.assertEquals
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
}
