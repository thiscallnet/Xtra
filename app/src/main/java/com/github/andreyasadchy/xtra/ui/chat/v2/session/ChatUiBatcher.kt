package com.github.andreyasadchy.xtra.ui.chat.v2.session

import com.github.andreyasadchy.xtra.util.DEFAULT_CHAT_UI_BATCH_INTERVAL_MS
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Coalesces timeline publications over the configured UI update interval. */
class ChatUiBatcher<T>(
    private val versions: Flow<Long>,
    private val snapshot: suspend () -> T,
    private val versionOf: (T) -> Long,
    private val batchIntervalMs: Long = DEFAULT_CHAT_UI_BATCH_INTERVAL_MS,
    private val batchIntervalMsFlow: StateFlow<Long>? = null,
    private val snapshotAfter: (suspend (Long) -> T)? = null,
) {
    /** Cold and collection-owned. No collector means no version collection or snapshot work. */
    fun flow(): Flow<T> = flow {
        coroutineScope {
            val initial = snapshot()
            emit(initial)
            var materializedVersion = versionOf(initial)
            val dirty = Channel<Long>(Channel.CONFLATED)
            val versionJob = launch {
                // The producer only forwards dirty versions. The consumer is the sole owner
                // of materializedVersion, so this remains race-free on a multithreaded dispatcher.
                versions.collect { version -> dirty.trySend(version) }
            }
            try {
                for (dirtyVersion in dirty) {
                    if (dirtyVersion <= materializedVersion) continue
                    if (batchIntervalMsFlow == null) {
                        val waitMs = batchIntervalMs.coerceAtLeast(0L)
                        if (waitMs > 0L) delay(waitMs)
                    } else {
                        awaitBatchInterval(batchIntervalMsFlow)
                    }
                    val current = snapshotAfter?.invoke(materializedVersion) ?: snapshot()
                    materializedVersion = versionOf(current)
                    emit(current)
                }
            } finally {
                versionJob.cancel()
                dirty.close()
            }
        }
    }

    private suspend fun awaitBatchInterval(intervals: StateFlow<Long>) {
        var intervalMs = intervals.value.coerceAtLeast(0L)
        val startedAtNanos = System.nanoTime()
        while (true) {
            val elapsedMs = ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
            val remainingMs = intervalMs - elapsedMs
            if (remainingMs <= 0L) return

            intervalMs = withTimeoutOrNull(remainingMs) {
                intervals.first { it.coerceAtLeast(0L) != intervalMs }
            }?.coerceAtLeast(0L) ?: return
        }
    }
}
