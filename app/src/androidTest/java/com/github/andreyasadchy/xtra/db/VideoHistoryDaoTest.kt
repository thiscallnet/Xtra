package com.github.andreyasadchy.xtra.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.model.VideoHistory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoHistoryDaoTest {

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
    fun metadataUpsertPreservesProgressAndContinueWatchingThresholds() = runBlocking {
        val dao = database.videoHistory()
        dao.upsertMetadata(history(id = 1, position = 0, updatedAt = 1, title = "Original title"))
        dao.updatePosition(1, 60_000, 10)
        dao.upsertMetadata(history(id = 1, position = 0, updatedAt = 2, title = null, durationSeconds = null))
        dao.upsertMetadata(history(id = 2, position = 29_999, updatedAt = 20))
        dao.upsertMetadata(history(id = 3, position = 30_000, updatedAt = 30))
        dao.upsertMetadata(history(id = 4, position = 95_000, durationSeconds = 100, updatedAt = 40))

        val items = dao.getContinueWatching(10).first()

        assertEquals(listOf(3L, 1L), items.map { it.id })
        assertEquals(60_000, items.single { it.id == 1L }.position)
        assertEquals("Original title", items.single { it.id == 1L }.title)
        assertTrue(items.none { it.id == 4L })
    }

    private fun history(
        id: Long,
        position: Long,
        updatedAt: Long,
        title: String? = "Video $id",
        durationSeconds: Int? = null,
    ) = VideoHistory(
        id = id,
        position = position,
        durationSeconds = durationSeconds,
        channelId = "channel-$id",
        channelLogin = "channel-$id",
        channelName = "Channel $id",
        channelImageURL = null,
        title = title,
        thumbnailURL = null,
        gameId = null,
        gameSlug = null,
        gameName = null,
        createdAt = null,
        updatedAt = updatedAt,
    )
}
