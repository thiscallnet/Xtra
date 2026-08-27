package com.github.andreyasadchy.xtra.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps the current process's active feed snapshot separate from persistence.
 * Room remains the bootstrap/offline store; live UI collectors observe this
 * snapshot so a persistence write does not itself create a UI invalidation.
 */
internal class ProcessLocalFeedSnapshot<T> {
    private data class Snapshot<T>(
        val loaded: Boolean = false,
        val items: List<T> = emptyList(),
    )

    private val snapshots = ConcurrentHashMap<String, MutableStateFlow<Snapshot<T>>>()
    private val loadLocks = ConcurrentHashMap<String, Mutex>()
    private val revisions = ConcurrentHashMap<String, AtomicLong>()

    fun flow(
        key: String,
        limit: Int,
        load: suspend () -> List<T>,
    ): Flow<List<T>> = flow {
        val state = snapshots.computeIfAbsent(key) { MutableStateFlow(Snapshot()) }
        val revision = revisions.computeIfAbsent(key) { AtomicLong() }
        if (!state.value.loaded) {
            loadLocks.computeIfAbsent(key) { Mutex() }.withLock {
                if (!state.value.loaded) {
                    val revisionAtStart = revision.get()
                    val items = load()
                    // A refresh can publish while the bootstrap query is in
                    // flight. Never overwrite that newer process-local state
                    // with the older Room result.
                    if (!state.value.loaded && revision.get() == revisionAtStart) {
                        state.value = Snapshot(loaded = true, items = items.toList())
                    }
                }
            }
        }
        emitAll(state.map { snapshot -> snapshot.items.take(limit) })
    }

    fun publish(key: String, items: List<T>) {
        val state = snapshots[key] ?: return
        revisions.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
        state.value = Snapshot(loaded = true, items = items.toList())
    }

    fun clear(key: String) {
        snapshots[key]?.let { state ->
            revisions.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
            state.value = Snapshot(loaded = true)
        }
    }
}
