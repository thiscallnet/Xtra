package com.github.andreyasadchy.xtra.ui.player

import android.util.Log
import com.github.andreyasadchy.xtra.XtraModule
import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.VideoHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Serializes playback persistence away from player and service callbacks.
 *
 * All playback implementations share the same writer so a position update
 * cannot overtake a playback-state update when services are being switched.
 */
class PlaybackPersistence internal constructor(
    private val store: PlaybackPersistenceStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    constructor(xtraModule: XtraModule) : this(
        store = object : PlaybackPersistenceStore {
            override suspend fun getPlaybackStates() = xtraModule.playerRepository.getPlaybackStates()

            override suspend fun savePlaybackStates(items: List<PlaybackState>) {
                xtraModule.playerRepository.savePlaybackStates(items)
            }

            override suspend fun deletePlaybackStates() {
                xtraModule.playerRepository.deletePlaybackStates()
            }

            override suspend fun saveVideoPosition(position: VideoPosition) {
                xtraModule.playerRepository.saveVideoPosition(position)
            }

            override suspend fun saveVideoHistory(item: VideoHistory) {
                xtraModule.playerRepository.saveVideoHistory(item)
            }

            override suspend fun saveVideoHistoryPosition(id: Long, position: Long) {
                xtraModule.playerRepository.saveVideoHistoryPosition(id, position)
            }

            override suspend fun saveOfflineVideoPosition(videoId: Int, position: Long) {
                xtraModule.offlineVideosRepository.updatePosition(videoId, position)
            }
        },
    )

    private val operations = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (operation in operations) {
                try {
                    operation()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Playback persistence failed", e)
                }
            }
        }
    }

    fun saveVideoPosition(position: VideoPosition) {
        enqueue {
            store.saveVideoPosition(position)
        }
    }

    fun saveOfflineVideoPosition(videoId: Int, position: Long) {
        enqueue {
            store.saveOfflineVideoPosition(videoId, position)
        }
    }

    fun saveVideoHistory(item: VideoHistory) {
        enqueue {
            store.saveVideoHistory(item)
        }
    }

    fun saveVideoHistoryPosition(id: Long, position: Long) {
        enqueue {
            store.saveVideoHistoryPosition(id, position)
        }
    }

    fun savePlaybackState(state: PlaybackState) {
        enqueue {
            store.savePlaybackStates(listOf(state))
        }
    }

    fun deletePlaybackStates() {
        enqueue {
            store.deletePlaybackStates()
        }
    }

    suspend fun saveVideoPositionAndWait(position: VideoPosition) {
        enqueueAndWait {
            store.saveVideoPosition(position)
        }
    }

    suspend fun getPlaybackStatesAndWait(): List<PlaybackState> {
        return enqueueAndWaitForResult {
            store.getPlaybackStates()
        }
    }

    suspend fun takePlaybackState(): PlaybackState? {
        return enqueueAndWaitForResult {
            val savedState = store.getPlaybackStates().firstOrNull()
            store.deletePlaybackStates()
            savedState
        }
    }

    /**
     * Waits until every operation queued before this call has completed.
     *
     * This is intentionally separate from the normal fire-and-forget methods:
     * service teardown uses it to keep the process alive until final position
     * and cleanup writes have reached the database.
     */
    suspend fun flush() {
        enqueueAndWait { }
    }

    private fun enqueue(operation: suspend () -> Unit) {
        if (operations.trySend(operation).isFailure) {
            Log.w(TAG, "Unable to queue playback persistence operation")
        }
    }

    private suspend fun enqueueAndWait(operation: suspend () -> Unit) {
        enqueueAndWaitForResult {
            operation()
        }
    }

    private suspend fun <T> enqueueAndWaitForResult(operation: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        operations.send {
            try {
                result.complete(operation())
            } catch (e: CancellationException) {
                result.cancel(e)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Playback persistence failed", e)
                result.completeExceptionally(e)
            }
        }
        return result.await()
    }

    private companion object {
        const val TAG = "PlaybackPersistence"
    }
}

internal interface PlaybackPersistenceStore {
    suspend fun getPlaybackStates(): List<PlaybackState>
    suspend fun savePlaybackStates(items: List<PlaybackState>)
    suspend fun deletePlaybackStates()
    suspend fun saveVideoPosition(position: VideoPosition)
    suspend fun saveVideoHistory(item: VideoHistory)
    suspend fun saveVideoHistoryPosition(id: Long, position: Long)
    suspend fun saveOfflineVideoPosition(videoId: Int, position: Long)
}
