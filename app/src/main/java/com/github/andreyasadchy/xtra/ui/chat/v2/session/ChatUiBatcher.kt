package com.github.andreyasadchy.xtra.ui.chat.v2.session

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** Coalesces complete truth snapshots to at most one UI publication per frame. */
class ChatUiBatcher<T>(
    private val versions: Flow<Long>,
    private val snapshot: suspend () -> T,
    private val versionOf: (T) -> Long,
    private val frameMs: Long = 16L,
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
                    delay(frameMs)
                    val current = snapshot()
                    materializedVersion = versionOf(current)
                    emit(current)
                }
            } finally {
                versionJob.cancel()
                dirty.close()
            }
        }
    }
}
