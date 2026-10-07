package com.github.andreyasadchy.xtra.ui.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.VideoHistory
import com.github.andreyasadchy.xtra.model.VideoPosition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackPersistenceRecoveryTest {
    @Test
    fun failedWriteRetainsOtherPositionsAndNewerUpdatesWithoutSpinning() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val failed = CompletableDeferred<Unit>()
        val releaseFailure = CompletableDeferred<Unit>()
        val saved = mutableMapOf<Long, Long>()
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        var holdReads = false
        var attempts = 0
        val store = object : PlaybackPersistenceStore {
            override suspend fun getPlaybackStates(): List<PlaybackState> {
                if (holdReads) {
                    readStarted.complete(Unit)
                    releaseRead.await()
                }
                return emptyList()
            }
            override suspend fun savePlaybackStates(items: List<PlaybackState>) = Unit
            override suspend fun deletePlaybackStates() = Unit
            override suspend fun saveVideoHistory(item: VideoHistory) = Unit
            override suspend fun saveVideoHistoryPosition(id: Long, position: Long) = Unit
            override suspend fun saveOfflineVideoPosition(videoId: Int, position: Long) = Unit
            override suspend fun saveVideoPosition(position: VideoPosition) {
                attempts++
                if (attempts == 1) {
                    failed.complete(Unit)
                    releaseFailure.await()
                    throw IllegalStateException("Injected temporary database failure")
                }
                saved[position.id] = position.position
            }
        }
        try {
            val writer = PlaybackPersistence(store, scope)
            writer.saveVideoPosition(VideoPosition(1L, 10L))
            withTimeout(5_000L) { failed.await() }
            writer.saveVideoPosition(VideoPosition(2L, 20L))
            writer.saveVideoPosition(VideoPosition(1L, 30L))
            releaseFailure.complete(Unit)
            delay(200L)
            assertEquals("Retry must back off", 1, attempts)
            withTimeout(5_000L) { writer.flush() }
            assertEquals(mapOf(1L to 30L, 2L to 20L), saved)
            writer.saveVideoPosition(VideoPosition(1L, 40L))
            withTimeout(5_000L) { writer.flush() }
            assertEquals(40L, saved[1L])
            holdReads = true
            val read = async(start = CoroutineStart.UNDISPATCHED) { writer.getPlaybackStatesAndWait() }
            withTimeout(5_000L) { readStarted.await() }
            writer.saveVideoPosition(VideoPosition(1L, 45L))
            val explicitSave = async(start = CoroutineStart.UNDISPATCHED) {
                writer.saveVideoPositionAndWait(VideoPosition(1L, 50L))
            }
            writer.saveVideoPosition(VideoPosition(1L, 60L))
            releaseRead.complete(Unit)
            withTimeout(5_000L) {
                read.await()
                explicitSave.await()
                writer.flush()
            }
            assertEquals("A queued explicit save must retain newer updates", 60L, saved[1L])
        } finally {
            scope.cancel()
        }
    }
}
