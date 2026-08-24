package com.github.andreyasadchy.xtra.repository.gamefeed

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.db.AppDatabase
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.repository.datasource.GameFeedCursor
import com.github.andreyasadchy.xtra.repository.datasource.GameFeedPage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameFeedCacheTest {

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
    fun automaticRefreshKeepsRoomRowsAndPagingUsesFreshGeneration() = runBlocking {
        val cache = GameFeedCache(database)
        val feedKey = GameFeedKey.top(null)

        cache.replaceAfterRefresh(
            feedKey,
            GameFeedPage(games("old-a", "old-b"), GameFeedCursor("gql", "old-next")),
            nowMs = 1L,
            preserveTail = false,
            pruneStaleOnEnd = true,
        )
        cache.replaceAfterRefresh(
            feedKey,
            GameFeedPage(games("old-a", "fresh-c"), GameFeedCursor("gql", "fresh-next")),
            nowMs = 2L,
            preserveTail = true,
            pruneStaleOnEnd = false,
        )

        assertEquals(
            listOf("id:old-a", "id:fresh-c", "id:old-b"),
            database.gameFeedDao().itemsForFeed(feedKey.value).map { it.itemKey },
        )
        val result = cache.pagingSource(feedKey).load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 30,
                placeholdersEnabled = false,
            )
        )
        assertTrue(result is PagingSource.LoadResult.Page)
        assertEquals(
            listOf("id:old-a", "id:fresh-c"),
            (result as PagingSource.LoadResult.Page).data.map { it.itemKey },
        )
    }

    private fun games(vararg names: String): List<Game> = names.map { name ->
        Game(id = name, slug = name, name = name)
    }
}
