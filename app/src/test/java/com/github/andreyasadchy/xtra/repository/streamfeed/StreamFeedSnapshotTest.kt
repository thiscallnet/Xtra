package com.github.andreyasadchy.xtra.repository.streamfeed

import com.github.andreyasadchy.xtra.model.ui.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFeedSnapshotTest {

    @Test
    fun refreshRemovesEndedStreamsAddsNewStreamsAndUpdatesOrder() {
        val old = listOf(
            Stream(id = null, channelId = "a", title = "old-a"),
            Stream(id = null, channelId = "b", title = "old-b"),
        )
        val fresh = listOf(
            Stream(id = null, channelId = "b", title = "new-b"),
            Stream(id = null, channelId = "c", title = "new-c"),
        )

        val items = refreshCachedItems("top:test", fresh)

        assertEquals(listOf("channel:b", "channel:c"), items.map { it.itemKey })
        assertEquals(listOf(0, 1), items.map { it.position })
        assertEquals("new-b", items.first().title)
        assertFalse(items.any { it.itemKey == "channel:a" })
        assertTrue(old.any { it.channelId == "a" }) // the old snapshot is untouched until a successful commit
    }

    @Test
    fun appendPreservesExistingChannelPosition() {
        val existing = refreshCachedItems(
            "top:test",
            listOf(Stream(id = null, channelId = "a"), Stream(id = null, channelId = "b")),
        )
        val appended = appendCachedPage(
            "top:test",
            existing,
            listOf(Stream(id = null, channelId = "b", viewerCount = 4), Stream(id = null, channelId = "c")),
        )

        assertEquals(listOf("channel:a", "channel:b", "channel:c"), appended.map { it.itemKey })
        assertEquals(listOf(0, 1, 2), appended.map { it.position })
        assertEquals(4, appended[1].viewerCount)
    }

    @Test
    fun appendReindexesFreshPrefixAndStaleTailAfterOverlapAndChanges() {
        val existing = refreshCachedItems(
            "top:test",
            listOf(
                Stream(channelId = "a"),
                Stream(channelId = "b"),
                Stream(channelId = "c"),
                Stream(channelId = "d"),
                Stream(channelId = "e"),
                Stream(channelId = "f"),
                Stream(channelId = "g"),
                Stream(channelId = "h"),
                Stream(channelId = "i"),
            ),
            generation = 1L,
        )
        val afterRefresh = refreshCachedItemsPreservingTail(
            feedKey = "top:test",
            existing = existing,
            streams = listOf(Stream(channelId = "a"), Stream(channelId = "b"), Stream(channelId = "x")),
            generation = 2L,
        )

        val afterAppend = appendCachedPage(
            feedKey = "top:test",
            existing = afterRefresh,
            streams = listOf(Stream(channelId = "d"), Stream(channelId = "y"), Stream(channelId = "f")),
            generation = 2L,
        )

        assertEquals(
            listOf(
                "channel:a", "channel:b", "channel:x",
                "channel:d", "channel:y", "channel:f",
                "channel:c", "channel:e", "channel:g", "channel:h", "channel:i",
            ),
            afterAppend.map { it.itemKey },
        )
        assertEquals(afterAppend.size, afterAppend.map { it.position }.distinct().size)
        assertEquals(afterAppend.indices.toList(), afterAppend.map { it.position })
        assertTrue(afterAppend.take(6).all { it.generation == 2L })
        assertTrue(afterAppend.drop(6).all { it.generation == 1L })
    }

    @Test
    fun refreshingAnExistingChannelReplacesEveryCachedStreamField() {
        val key = "top:v2"
        val existing = refreshCachedItems(
            feedKey = key,
            streams = listOf(
                Stream(
                    id = "broadcast-1",
                    channelId = "channel-42",
                    channelLogin = "old-login",
                    channelName = "Old name",
                    channelImageURL = "old-avatar",
                    gameId = "game-old",
                    gameSlug = "old-game",
                    gameName = "Old game",
                    title = "Old title",
                    thumbnailURL = "old-thumbnail",
                    createdAt = "old-created",
                    viewerCount = 10,
                    tags = listOf("old-tag"),
                ),
            ),
            generation = 1L,
        )
        val refreshed = refreshCachedItemsPreservingTail(
            feedKey = key,
            existing = existing,
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
    fun staleTailIsBoundToOnePreviouslyVerifiedGeneration() {
        val first = refreshCachedItems(
            "top:bounded-tail",
            listOf(Stream(channelId = "old")),
            generation = 1L,
        )
        val second = refreshCachedItemsPreservingTail(
            "top:bounded-tail",
            first,
            listOf(Stream(channelId = "middle")),
            generation = 2L,
        )
        val third = refreshCachedItemsPreservingTail(
            "top:bounded-tail",
            second,
            listOf(Stream(channelId = "new")),
            generation = 3L,
        )

        assertEquals(
            listOf("channel:new", "channel:middle"),
            third.map { it.itemKey },
        )
        assertTrue(third.all { it.generation >= 2L })
    }
}
