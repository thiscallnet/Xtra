package com.github.andreyasadchy.xtra.repository.streamfeed

import com.github.andreyasadchy.xtra.model.ui.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFeedSnapshotTest {

    @Test
    fun refreshRemovesEndedStreamsAddsNewStreamsAndUpdatesOrder() {
        val old = refreshCachedItems(
            "top:test",
            listOf(
                Stream(channelId = "a", title = "old-a"),
                Stream(channelId = "b", title = "old-b"),
            ),
            generation = 1L,
        )
        val fresh = listOf(
            Stream(channelId = "b", title = "new-b"),
            Stream(channelId = "c", title = "new-c"),
        )

        val items = refreshCachedItems("top:test", fresh, generation = 2L)

        assertEquals(listOf("channel:b", "channel:c"), items.map { it.itemKey })
        assertEquals(listOf(0, 1), items.map { it.position })
        assertEquals("new-b", items.first().title)
        assertFalse(items.any { it.itemKey == "channel:a" })
        assertTrue(old.any { it.itemKey == "channel:a" })
    }

    @Test
    fun successfulEmptyRefreshProducesAnEmptySnapshot() {
        val items = refreshCachedItems("top:test", emptyList(), generation = 2L)

        assertTrue(items.isEmpty())
    }

    @Test
    fun appendPreservesExistingChannelPositionAndUpdatesPageItems() {
        val existing = refreshCachedItems(
            "top:test",
            listOf(Stream(channelId = "a"), Stream(channelId = "b")),
            generation = 1L,
        )
        val appended = appendCachedPage(
            "top:test",
            existing,
            listOf(Stream(channelId = "b", viewerCount = 4), Stream(channelId = "c")),
            generation = 1L,
        )

        assertEquals(listOf("channel:a", "channel:b", "channel:c"), appended.map { it.itemKey })
        assertEquals(listOf(0, 1, 2), appended.map { it.position })
        assertEquals(4, appended[1].viewerCount)
    }

    @Test
    fun appendOnlyExtendsTheCurrentRefreshGeneration() {
        val existing = refreshCachedItems(
            "top:test",
            listOf(Stream(channelId = "a"), Stream(channelId = "b")),
            generation = 1L,
        ) + refreshCachedItems(
            "top:test",
            listOf(Stream(channelId = "old-tail")),
            generation = 0L,
        )

        val appended = appendCachedPage(
            "top:test",
            existing,
            listOf(Stream(channelId = "c")),
            generation = 1L,
        )

        assertEquals(listOf("channel:a", "channel:b", "channel:c"), appended.map { it.itemKey })
        assertTrue(appended.all { it.generation == 1L })
    }

    @Test
    fun refreshingAnExistingChannelReplacesEveryCachedStreamField() {
        val refreshed = refreshCachedItems(
            feedKey = "top:v2",
            streams = listOf(
                Stream(
                    id = "broadcast-2",
                    channelId = "channel-42",
                    channelLogin = "new-login",
                    channelName = "New name",
                    channelImageURL = "new-avatar",
                    gameId = "game-new",
                    gameSlug = "new-game",
                    gameName = "New game",
                    title = "New title",
                    thumbnailURL = "new-thumbnail",
                    createdAt = "new-created",
                    viewerCount = 20,
                    tags = listOf("new-tag"),
                ),
            ),
            generation = 2L,
        )

        val stream = refreshed.single().toStream()
        assertEquals("broadcast-2", stream.id)
        assertEquals("channel-42", stream.channelId)
        assertEquals("new-login", stream.channelLogin)
        assertEquals("New name", stream.channelName)
        assertEquals("new-avatar", stream.channelImageURL)
        assertEquals("game-new", stream.gameId)
        assertEquals("new-game", stream.gameSlug)
        assertEquals("New game", stream.gameName)
        assertEquals("New title", stream.title)
        assertEquals("new-thumbnail", stream.thumbnailURL)
        assertEquals("new-created", stream.createdAt)
        assertEquals(20, stream.viewerCount)
        assertEquals(listOf("new-tag"), stream.tags)
        assertEquals(2L, stream.thumbnailGeneration)
    }

    @Test
    fun aNewRefreshGenerationCannotRetainThePreviousGeneration() {
        val first = refreshCachedItems(
            "top:test",
            listOf(Stream(channelId = "old")),
            generation = 1L,
        )
        val second = refreshCachedItems(
            "top:test",
            listOf(Stream(channelId = "new")),
            generation = 2L,
        )

        assertEquals(listOf("channel:new"), second.map { it.itemKey })
        assertTrue(first.any { it.itemKey == "channel:old" })
        assertTrue(second.all { it.generation == 2L })
    }
}
