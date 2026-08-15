package com.github.andreyasadchy.xtra.repository.streamfeed

import androidx.paging.AsyncPagingDataDiffer
import androidx.paging.PagingConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.db.AppDatabase
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.datasource.StreamFeedCursor
import com.github.andreyasadchy.xtra.repository.datasource.StreamFeedPage
import com.github.andreyasadchy.xtra.repository.datasource.StreamFeedPageLoader
import com.github.andreyasadchy.xtra.util.C
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamFeedPagingIntegrationTest {

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
    fun retainedTailGetsOneFreshPageWithoutPagingWalkingTheWholeStaleTail() = runBlocking {
        val cache = StreamFeedCache(database)
        val feedKey = StreamFeedKey("top:paging-tail")
        cache.replaceAfterRefresh(
            feedKey,
            StreamFeedPage(
                (0 until 90).map { Stream(channelId = "old-$it") },
                StreamFeedCursor(C.GQL, "old-page-4"),
            ),
            nowMs = 1L,
            preserveTail = false,
            pruneStaleOnEnd = true,
        )

        val cursors = mutableListOf<StreamFeedCursor?>()
        val secondPageLoaded = kotlinx.coroutines.CompletableDeferred<Unit>()
        val loader = object : StreamFeedPageLoader {
            override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
                cursors += cursor
                return if (cursor == null) {
                    StreamFeedPage(
                        (0 until 30).map { Stream(channelId = "fresh-$it") },
                        StreamFeedCursor(C.GQL, "fresh-page-2"),
                    )
                } else {
                    assertEquals(StreamFeedCursor(C.GQL, "fresh-page-2"), cursor)
                    secondPageLoaded.complete(Unit)
                    StreamFeedPage(
                        (30 until 60).map { Stream(channelId = "fresh-$it") },
                        nextCursor = null,
                    )
                }
            }
        }
        val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = StreamFeedRefreshCoordinator(
            cache = cache,
            scope = coordinatorScope,
            wallClockMs = { 1_000_000L },
            elapsedRealtimeMs = { 1_000L },
            debugLoggingEnabled = false,
        )
        val pager = StreamFeedPager(cache, coordinator)
        val differ = AsyncPagingDataDiffer(
            diffCallback = object : DiffUtil.ItemCallback<Stream>() {
                override fun areItemsTheSame(oldItem: Stream, newItem: Stream): Boolean {
                    return oldItem.channelId == newItem.channelId
                }

                override fun areContentsTheSame(oldItem: Stream, newItem: Stream): Boolean {
                    return oldItem == newItem
                }
            },
            updateCallback = NoOpListUpdateCallback,
            mainDispatcher = Dispatchers.Main,
            workerDispatcher = Dispatchers.Default,
        )
        val collectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collectionJob = collectionScope.launch {
            pager.flow(
                spec = StreamFeedSpec(feedKey, loader),
                config = PagingConfig(pageSize = 30, initialLoadSize = 30, prefetchDistance = 3),
            ).collectLatest { differ.submitData(it) }
        }

        withTimeout(5_000L) {
            while (differ.itemCount == 0) delay(20L)
            differ.getItem(0)
            secondPageLoaded.await()
        }
        delay(100L)

        assertEquals(
            listOf(null, StreamFeedCursor(C.GQL, "fresh-page-2")),
            cursors,
        )
        assertTrue(differ.itemCount >= 90)
        assertTrue(database.streamFeedDao().itemsForFeed(feedKey.value).any { it.itemKey == "channel:old-80" })
        assertEquals(null, database.streamFeedDao().state(feedKey.value)?.nextCursor)

        collectionJob.cancel()
        collectionScope.cancel()
        coordinatorScope.cancel()
    }

    private object NoOpListUpdateCallback : ListUpdateCallback {
        override fun onInserted(position: Int, count: Int) = Unit
        override fun onRemoved(position: Int, count: Int) = Unit
        override fun onMoved(fromPosition: Int, toPosition: Int) = Unit
        override fun onChanged(position: Int, count: Int, payload: Any?) = Unit
    }
}
