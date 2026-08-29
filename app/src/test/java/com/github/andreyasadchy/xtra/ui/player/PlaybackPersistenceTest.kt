package com.github.andreyasadchy.xtra.ui.player

import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.VideoHistory
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.VideoQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
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

    @Test
    fun validSerializedPlaybackStateRestoresItsQualityData() {
        val json = Json
        val quality = VideoQuality("720p60", bitrate = 1_000, url = "https://example.test/720")
        val qualities = json.encodeToString(listOf(quality))

        assertEquals(listOf(quality.name), decodePlaybackQualities(json, qualities)?.map { it.name })
        assertEquals(quality.name, decodePlaybackQuality(json, json.encodeToString(quality))?.name)
        assertEquals(quality.name, selectRestoredQuality(listOf(quality), quality)?.name)
    }

    @Test
    fun malformedSerializedPlaybackStateFallsBackToDefaultQualityData() {
        val json = Json

        assertEquals(null, decodePlaybackQualities(json, "not-json"))
        assertEquals(null, decodePlaybackQuality(json, "not-json"))
        assertEquals(null, decodePlaybackQuality(json, "{\"name\":7}"))
        val state = PlaybackState(previousQuality = "not-json")
        assertEquals(null, decodePlaybackQuality(json, state.previousQuality))
        assertEquals(null, selectRestoredQuality(null, VideoQuality("720p60")))
        assertEquals(null, selectRestoredQuality(listOf(VideoQuality("720p30")), VideoQuality("720p60")))
    }

    @Test
    fun consumingBadStateDoesNotPoisonTheFollowingStartup() = runBlocking {
        persistence.savePlaybackState(PlaybackState(qualities = "not-json", quality = "not-json"))
        val badState = persistence.takePlaybackState()
        assertEquals(null, decodePlaybackQualities(Json, badState?.qualities))
        assertEquals(emptyList<PlaybackState>(), persistence.getPlaybackStatesAndWait())

        val nextState = PlaybackState(videoId = "next", qualities = "not-json")
        persistence.savePlaybackState(nextState)

        assertSame(nextState, persistence.takePlaybackState())
        assertEquals(emptyList<PlaybackState>(), persistence.getPlaybackStatesAndWait())
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
