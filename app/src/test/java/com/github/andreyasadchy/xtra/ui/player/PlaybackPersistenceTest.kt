package com.github.andreyasadchy.xtra.ui.player

import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.VideoHistory
import com.github.andreyasadchy.xtra.model.VideoPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class PlaybackPersistenceTest {

    private lateinit var scope: CoroutineScope
    private lateinit var store: FakePlaybackPersistenceStore
    private lateinit var persistence: PlaybackPersistence

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = FakePlaybackPersistenceStore()
        persistence = PlaybackPersistence(store, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun saveThenReadReturnsTheQueuedState() = runBlocking {
        val state = PlaybackState(videoId = "A")

        persistence.savePlaybackState(state)

        assertSame(state, persistence.getPlaybackStatesAndWait().single())
    }

    @Test
    fun saveThenDeleteThenReadReturnsNoState() = runBlocking {
        persistence.savePlaybackState(PlaybackState(videoId = "A"))
        persistence.deletePlaybackStates()

        assertEquals(emptyList<PlaybackState>(), persistence.getPlaybackStatesAndWait())
    }

    @Test
    fun saveThenTakeReturnsTheStateAndDeletesIt() = runBlocking {
        val state = PlaybackState(videoId = "A")
        persistence.savePlaybackState(state)

        assertSame(state, persistence.takePlaybackState())
        assertEquals(emptyList<PlaybackState>(), persistence.getPlaybackStatesAndWait())
    }

    @Test
    fun flushWaitsForQueuedWrites() = runBlocking {
        persistence.savePlaybackState(PlaybackState(videoId = "A"))

        persistence.flush()

        assertEquals(1, store.states.size)
    }

    private class FakePlaybackPersistenceStore : PlaybackPersistenceStore {
        val states = mutableListOf<PlaybackState>()

        override suspend fun getPlaybackStates(): List<PlaybackState> = states.toList()

        override suspend fun savePlaybackStates(items: List<PlaybackState>) {
            delay(25)
            states += items
        }

        override suspend fun deletePlaybackStates() {
            states.clear()
        }

        override suspend fun saveVideoPosition(position: VideoPosition) = Unit

        override suspend fun saveVideoHistory(item: VideoHistory) = Unit

        override suspend fun saveVideoHistoryPosition(id: Long, position: Long) = Unit

        override suspend fun saveOfflineVideoPosition(videoId: Int, position: Long) = Unit
    }
}
