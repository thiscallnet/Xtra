package com.github.andreyasadchy.xtra.ui.player

import android.util.Log
import com.github.andreyasadchy.xtra.XtraModule
import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.VideoHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    private val operations = Channel<suspend () -> Unit>(Channel.BUFFERED)
    private val pendingVideoPositions = mutableMapOf<Long, VideoPosition>()
    private val videoPositionLock = Any()
    private var videoPositionOperationQueued = false
    private var videoPositionRetryDelayMs = 0L
    private var videoPositionRetryJob: Job? = null

    init {
        scope.launch {
            for (operation in operations) {
                try {
                    operation()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Playback persistence failed", e)
                } finally {
                    schedulePendingVideoPositions()
                }
            }
        }
    }

    fun saveVideoPosition(position: VideoPosition) {
        synchronized(videoPositionLock) {
            pendingVideoPositions[position.id] = position
        }
        schedulePendingVideoPositions()
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

    suspend fun deletePlaybackStatesAndWait() {
        enqueueAndWait {
            store.deletePlaybackStates()
        }
    }

    suspend fun saveVideoPositionAndWait(position: VideoPosition) {
        // Share the coalesced queue so a later update cannot be removed or overwritten
        // by this call while it waits behind another database operation.
        saveVideoPosition(position)
        flush()
    }

    suspend fun getPlaybackStatesAndWait(): List<PlaybackState> {
        return enqueueAndWaitForResult {
            store.getPlaybackStates()
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
        enqueueAndWait {
            synchronized(videoPositionLock) {
                videoPositionRetryJob?.cancel()
                videoPositionRetryJob = null
            }
            drainPendingVideoPositions()
        }
    }

    private fun enqueue(operation: suspend () -> Unit) {
        if (operations.trySend(operation).isFailure) {
            scope.launch {
                operations.send(operation)
            }
        }
    }

    private fun schedulePendingVideoPositions() {
        val shouldQueue = synchronized(videoPositionLock) {
            if (pendingVideoPositions.isEmpty() || videoPositionOperationQueued) {
                false
            } else {
                videoPositionOperationQueued = true
                true
            }
        }
        if (!shouldQueue) {
            return
        }
        val operation: suspend () -> Unit = {
            drainPendingVideoPositions()
        }
        val retryDelay = synchronized(videoPositionLock) { videoPositionRetryDelayMs }
        if (retryDelay > 0L) {
            val retryJob = synchronized(videoPositionLock) {
                scope.launch(start = CoroutineStart.LAZY) {
                    delay(retryDelay)
                    operations.send(operation)
                }.also { videoPositionRetryJob = it }
            }
            retryJob.start()
        } else {
            enqueue(operation)
        }
    }

    private suspend fun drainPendingVideoPositions() {
        val positions = synchronized(videoPositionLock) {
            pendingVideoPositions.values.toList().also { pendingVideoPositions.clear() }
        }
        var savedCount = 0
        try {
            for (position in positions) {
                store.saveVideoPosition(position)
                savedCount++
            }
            synchronized(videoPositionLock) { videoPositionRetryDelayMs = 0L }
        } catch (error: Exception) {
            synchronized(videoPositionLock) {
                // A newer update received during I/O always wins over the failed batch.
                for (index in savedCount until positions.size) {
                    val position = positions[index]
                    pendingVideoPositions.putIfAbsent(position.id, position)
                }
                videoPositionRetryDelayMs = (videoPositionRetryDelayMs * 2L).coerceIn(1_000L, 30_000L)
            }
            throw error
        } finally {
            synchronized(videoPositionLock) { videoPositionOperationQueued = false }
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
