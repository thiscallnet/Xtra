package com.github.andreyasadchy.xtra.repository.streamfeed

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.db.AppDatabase
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.datasource.StreamFeedCursor
import com.github.andreyasadchy.xtra.repository.datasource.StreamFeedPage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamFeedCacheTest {

    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun refreshAndAppendKeepFreshPrefixAndStaleTailPositionsUnique() = runBlocking {
        val cache = StreamFeedCache(database)
        val feedKey = StreamFeedKey("top:cache-layout")

        cache.replaceAfterRefresh(
            feedKey,
            StreamFeedPage(
                streams("a", "b", "c", "d", "e", "f", "g", "h", "i"),
                StreamFeedCursor("gql", "old-page-2"),
            ),
            nowMs = 1L,
            preserveTail = false,
            pruneStaleOnEnd = true,
        )
        cache.replaceAfterRefresh(
            feedKey,
            StreamFeedPage(
                streams("a", "b", "x"),
                StreamFeedCursor("helix", "fresh-page-2"),
            ),
            nowMs = 2L,
            preserveTail = true,
            pruneStaleOnEnd = true,
        )

        assertLayout(
            feedKey,
            expectedKeys = listOf("a", "b", "x", "c", "d", "e", "f", "g", "h", "i"),
            activeCount = 3,
        )

        cache.appendPage(
            feedKey,
            StreamFeedPage(
                streams("d", "y", "f"),
                StreamFeedCursor("helix", "fresh-page-3"),
            ),
            nowMs = 3L,
            pruneStaleOnEnd = true,
        )

        assertLayout(
            feedKey,
            expectedKeys = listOf("a", "b", "x", "d", "y", "f", "c", "e", "g", "h", "i"),
            activeCount = 6,
        )
        assertEquals("helix", database.streamFeedDao().state(feedKey.value)?.nextCursorApi)

        cache.appendPage(
            feedKey,
            StreamFeedPage(emptyList(), nextCursor = null),
            nowMs = 4L,
            pruneStaleOnEnd = true,
        )

        assertLayout(
            feedKey,
            expectedKeys = listOf("a", "b", "x", "d", "y", "f"),
            activeCount = 6,
        )
    }

    @Test
    fun automaticRefreshEofDefersStalePruningUntilExplicitFinalization() = runBlocking {
        val cache = StreamFeedCache(database)
        val feedKey = StreamFeedKey("top:cache-automatic-eof")
        seedOldFeed(cache, feedKey)

        cache.replaceAfterRefresh(
            feedKey,
            StreamFeedPage(streams("fresh-0", "fresh-1"), nextCursor = null),
            nowMs = 2L,
            preserveTail = true,
            pruneStaleOnEnd = false,
        )

        assertTrue(database.streamFeedDao().itemsForFeed(feedKey.value).any { it.itemKey == "channel:old-80" })
        assertContiguousLayout(feedKey, activeCount = 2)
        assertEquals(null, database.streamFeedDao().state(feedKey.value)?.nextCursor)

        cache.pruneStaleGeneration(feedKey)

        val rows = database.streamFeedDao().itemsForFeed(feedKey.value)
        assertEquals(listOf("channel:fresh-0", "channel:fresh-1"), rows.map { it.itemKey })
        assertFalse(rows.any { it.itemKey == "channel:old-80" })
        assertContiguousLayout(feedKey, activeCount = 2)
    }

    @Test
    fun speculativeEofDefersStalePruningUntilRealAppendFinalization() = runBlocking {
        val cache = StreamFeedCache(database)
        val feedKey = StreamFeedKey("top:cache-speculative-eof")
        seedOldFeed(cache, feedKey)

        cache.replaceAfterRefresh(
            feedKey,
            StreamFeedPage(
                streams("fresh-0", "fresh-1", "fresh-2"),
                StreamFeedCursor("gql", "fresh-page-2"),
            ),
            nowMs = 2L,
            preserveTail = true,
            pruneStaleOnEnd = false,
        )
        cache.appendPage(
            feedKey,
            StreamFeedPage(streams("fresh-3", "fresh-4"), nextCursor = null),
            nowMs = 3L,
            pruneStaleOnEnd = false,
        )

        val rowsBeforeFinalization = database.streamFeedDao().itemsForFeed(feedKey.value)
        assertTrue(rowsBeforeFinalization.any { it.itemKey == "channel:old-80" })
        assertContiguousLayout(feedKey, activeCount = 5)
        assertEquals(null, database.streamFeedDao().state(feedKey.value)?.nextCursor)

        cache.pruneStaleGeneration(feedKey)

        val rowsAfterFinalization = database.streamFeedDao().itemsForFeed(feedKey.value)
        assertEquals(5, rowsAfterFinalization.size)
        assertFalse(rowsAfterFinalization.any { it.itemKey == "channel:old-80" })
        assertContiguousLayout(feedKey, activeCount = 5)
    }

    private suspend fun seedOldFeed(cache: StreamFeedCache, feedKey: StreamFeedKey) {
        cache.replaceAfterRefresh(
            feedKey,
            StreamFeedPage(
                (0 until 90).map { Stream(channelId = "old-$it") },
                StreamFeedCursor("gql", "old-page-2"),
            ),
            nowMs = 1L,
            preserveTail = false,
            pruneStaleOnEnd = true,
        )
    }

    private fun assertLayout(feedKey: StreamFeedKey, expectedKeys: List<String>, activeCount: Int) {
        val rows = database.streamFeedDao().itemsForFeed(feedKey.value)
        assertEquals(expectedKeys.map { "channel:$it" }, rows.map { it.itemKey })
        assertEquals(rows.indices.toList(), rows.map { it.position })
        assertEquals(rows.size, rows.map { it.position }.distinct().size)
        assertTrue(rows.take(activeCount).all { it.generation == rows.first().generation })
        assertTrue(rows.drop(activeCount).all { it.generation < rows.first().generation })
    }

    private fun assertContiguousLayout(feedKey: StreamFeedKey, activeCount: Int) {
        val rows = database.streamFeedDao().itemsForFeed(feedKey.value)
        assertEquals(rows.indices.toList(), rows.map { it.position })
        assertEquals(rows.size, rows.map { it.position }.distinct().size)
        assertTrue(rows.take(activeCount).all { it.generation == rows.first().generation })
        assertTrue(rows.drop(activeCount).all { it.generation < rows.first().generation })
    }

    private fun streams(vararg ids: String): List<Stream> {
        return ids.map { Stream(channelId = it) }
    }
}
