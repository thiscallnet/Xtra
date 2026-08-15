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
}
