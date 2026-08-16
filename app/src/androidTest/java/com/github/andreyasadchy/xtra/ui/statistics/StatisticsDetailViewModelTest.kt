package com.github.andreyasadchy.xtra.ui.statistics

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.db.AppDatabase
import com.github.andreyasadchy.xtra.model.stats.ViewingInterval
import com.github.andreyasadchy.xtra.model.stats.ViewingSession
import com.github.andreyasadchy.xtra.repository.ViewingStatsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatisticsDetailViewModelTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun bucketDetailLoadsItsSnapshotOnInitialization() = runBlocking {
        val start = 1_720_000_000_000L
        val end = start + HOUR
        val sessionId = database.viewingStats().insertSession(
            ViewingSession(
                channelId = "channel-bucket",
                channelLogin = "channel-bucket",
                channelName = "Bucket channel",
                channelImage = null,
                contentType = "live",
                contentId = "stream-bucket",
                startedAt = start,
                endedAt = end,
                watchedMs = HOUR,
                lastCheckpointAt = end,
            ),
        )
        database.viewingStats().insertInterval(
            ViewingInterval(
                sessionId = sessionId,
                channelId = "channel-bucket",
                channelLogin = "channel-bucket",
                channelName = "Bucket channel",
                channelImage = null,
                categoryId = null,
                categoryName = null,
                categoryImage = null,
                contentType = "live",
                contentId = "stream-bucket",
                startAt = start,
                endAt = end,
                watchedMs = HOUR,
                lastCheckpointAt = end,
            ),
        )

        val viewModel = StatisticsDetailViewModel(
            repository = ViewingStatsRepository(database.viewingStats()),
            type = StatisticsDetailViewModel.TYPE_BUCKET,
            channelId = null,
            categoryKey = null,
            title = "Bucket",
            bucketFrom = start,
            bucketTo = end,
        )

        withTimeout(5_000L) {
            while (viewModel.uiState.value.isLoading) {
                delay(20L)
            }
        }

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(HOUR, viewModel.uiState.value.snapshot?.totalWatchMs)
    }

    private companion object {
        const val HOUR = 60 * 60 * 1_000L
    }
}
