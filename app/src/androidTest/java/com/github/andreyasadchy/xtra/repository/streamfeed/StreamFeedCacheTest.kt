package com.github.andreyasadchy.xtra.repository.streamfeed

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.db.AppDatabase
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.datasource.StreamFeedPage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
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
    fun successfulRefreshReplacesTheCompleteSnapshot() = runBlocking {
        val cache = StreamFeedCache(database)
        val feedKey = StreamFeedKey("top:cache-layout")

        cache.replaceAfterRefresh(feedKey, StreamFeedPage(streams("a", "b", "c"), null), 1L)
        cache.replaceAfterRefresh(feedKey, StreamFeedPage(streams("a", "c", "d"), null), 2L)

        val rows = database.streamFeedDao().itemsForFeed(feedKey.value)
        assertEquals(listOf("channel:a", "channel:c", "channel:d"), rows.map { it.itemKey })
        assertEquals(rows.indices.toList(), rows.map { it.position })
        assertEquals(3, database.streamFeedDao().activeItemCount(feedKey.value))
    }

    @Test
    fun successfulEmptyRefreshRemovesEveryPreviouslyCachedStream() = runBlocking {
        val cache = StreamFeedCache(database)
        val feedKey = StreamFeedKey("top:empty")

        cache.replaceAfterRefresh(feedKey, StreamFeedPage(streams("ended"), null), 1L)
        cache.replaceAfterRefresh(feedKey, StreamFeedPage(emptyList(), null), 2L)

        assertTrue(database.streamFeedDao().itemsForFeed(feedKey.value).isEmpty())
        assertEquals(0, database.streamFeedDao().activeItemCount(feedKey.value))
    }

    @Test
    fun paginationAppendsOnlyToTheCurrentRefreshGeneration() = runBlocking {
        val cache = StreamFeedCache(database)
        val feedKey = StreamFeedKey("top:pagination")

        cache.replaceAfterRefresh(feedKey, StreamFeedPage(streams("a", "b"), "cursor".toCursor()), 1L)
        cache.appendPage(feedKey, StreamFeedPage(streams("c"), null), 2L)

        val rows = database.streamFeedDao().itemsForFeed(feedKey.value)
        assertEquals(listOf("channel:a", "channel:b", "channel:c"), rows.map { it.itemKey })
        assertTrue(rows.all { it.generation == rows.first().generation })
    }

    @Test
    fun failedRefreshRetainsTheLastSuccessfulSnapshot() = runBlocking {
        val cache = StreamFeedCache(database)
        val feedKey = StreamFeedKey("top:failure")
        cache.replaceAfterRefresh(feedKey, StreamFeedPage(streams("still-live"), null), 1L)

        cache.recordFailure(feedKey, nowMs = 2L, failureBackoffUntil = 3L, rateLimitUntil = null)

        val rows = database.streamFeedDao().itemsForFeed(feedKey.value)
        assertEquals(listOf("channel:still-live"), rows.map { it.itemKey })
        assertFalse(rows.isEmpty())
    }

    @Test
    fun processLocalSnapshotReplacesRemovedStreamsAfterSuccessfulRefresh() = runBlocking {
        val cache = StreamFeedCache(database)
        val feedKey = StreamFeedKey("top:process-local")
        cache.replaceAfterRefresh(feedKey, StreamFeedPage(streams("a", "b", "c"), null), 1L)

        val emissions = mutableListOf<List<String>>()
        val initialEmission = CompletableDeferred<Unit>()
        val collector = launch {
            cache.activeItemsFlow(feedKey, Int.MAX_VALUE).take(2).collect { snapshot ->
                emissions += snapshot.mapNotNull(Stream::channelId)
                if (emissions.size == 1) initialEmission.complete(Unit)
            }
        }
        initialEmission.await()
        cache.replaceAfterRefresh(feedKey, StreamFeedPage(streams("a", "c", "d"), null), 2L)
        collector.join()

        assertEquals(listOf("a", "b", "c"), emissions[0])
        assertEquals(listOf("a", "c", "d"), emissions[1])
    }

    @Test
    fun processLocalSnapshotRetainsDataAfterFailure() = runBlocking {
        val cache = StreamFeedCache(database)
        val feedKey = StreamFeedKey("top:process-local-failure")
        cache.replaceAfterRefresh(feedKey, StreamFeedPage(streams("a"), null), 1L)
        cache.recordFailure(feedKey, nowMs = 2L, failureBackoffUntil = 3L, rateLimitUntil = null)

        assertEquals(listOf("a"), cache.activeItemsFlow(feedKey, Int.MAX_VALUE).first().mapNotNull(Stream::channelId))
    }

    private fun String.toCursor() = com.github.andreyasadchy.xtra.repository.datasource.StreamFeedCursor("gql", this)

    private fun streams(vararg ids: String): List<Stream> = ids.map { Stream(channelId = it) }
}
