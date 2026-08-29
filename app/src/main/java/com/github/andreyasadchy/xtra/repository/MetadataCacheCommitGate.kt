package com.github.andreyasadchy.xtra.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class MetadataCacheCommitGate {
    private val mutex = Mutex()
    private var mutationGeneration = 0L

    suspend fun generationAtStart(): Long = mutex.withLock { mutationGeneration }

    suspend fun commitFetch(
        generationAtStart: Long,
        commit: suspend () -> Unit,
    ): Boolean = mutex.withLock {
        if (generationAtStart != mutationGeneration) return@withLock false
        commit()
        true
    }

    suspend fun commitMutation(commit: suspend () -> Unit) {
        mutex.withLock {
            mutationGeneration++
            commit()
        }
    }
}
