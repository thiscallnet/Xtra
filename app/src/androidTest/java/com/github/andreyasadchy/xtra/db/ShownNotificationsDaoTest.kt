package com.github.andreyasadchy.xtra.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.model.ShownNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShownNotificationsDaoTest {

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
    fun concurrentClaimsForTheSameStreamIdOnlyInsertOnce() = runBlocking {
        val results = coroutineScope {
            (1..2).map {
                async(Dispatchers.Default) {
                    database.shownNotifications().insert(
                        ShownNotification(
                            channelId = "A",
                            streamId = "123",
                            startedAt = 1L,
                        )
                    )
                }
            }.awaitAll()
        }

        assertEquals(1, results.count { it != -1L })
        assertEquals(1, database.shownNotifications().getAll().size)
    }
}
