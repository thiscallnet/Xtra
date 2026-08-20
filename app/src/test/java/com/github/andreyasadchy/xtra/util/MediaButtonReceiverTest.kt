package com.github.andreyasadchy.xtra.util

import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.VideoHistory
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.ui.player.PlaybackPersistence
import com.github.andreyasadchy.xtra.ui.player.PlaybackPersistenceStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MediaButtonReceiverTest {

    private lateinit var scope: CoroutineScope
    private lateinit var store: SlowPlaybackPersistenceStore
    private lateinit var persistence: PlaybackPersistence

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = SlowPlaybackPersistenceStore()
        persistence = PlaybackPersistence(store, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun lookupWaitsForQueuedWriteWithoutBlockingTheReceiverEntryPath() = runBlocking {
        val state = PlaybackState(videoId = "A")
        persistence.savePlaybackState(state)
        store.writeStarted.await()

        var playbackStarted = false
        var finished = false
        var observedState: PlaybackState? = null
        val lookupJob = launchMediaButtonPlayback(
            scope = scope,
            playbackStateLookup = {
                persistence.getPlaybackStatesAndWait().also {
                    observedState = it.singleOrNull()
                }
            },
            onPlaybackAvailable = { playbackStarted = true },
            onFinished = { finished = true },
        )

        assertFalse(lookupJob.isCompleted)
        assertFalse(playbackStarted)

        store.releaseWrite.complete(Unit)
        withTimeout(1_000) { lookupJob.join() }

        assertSame(state, observedState)
        assertTrue(playbackStarted)
        assertTrue(finished)
    }

    private class SlowPlaybackPersistenceStore : PlaybackPersistenceStore {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        private val states = mutableListOf<PlaybackState>()

        override suspend fun getPlaybackStates(): List<PlaybackState> = states.toList()

        override suspend fun savePlaybackStates(items: List<PlaybackState>) {
            writeStarted.complete(Unit)
            releaseWrite.await()
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
