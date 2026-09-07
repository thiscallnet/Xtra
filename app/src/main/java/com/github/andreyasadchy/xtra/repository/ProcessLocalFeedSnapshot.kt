package com.github.andreyasadchy.xtra.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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

    private class Entry<T> {
        val state = MutableStateFlow(Snapshot<T>())
        val loadLock = Mutex()
        val revision = AtomicLong()
        val collectors = AtomicInteger()
        var evictionPending = false
    }

    private val entries = ConcurrentHashMap<String, Entry<T>>()

    fun flow(
        key: String,
        limit: Int,
        load: suspend () -> List<T>,
    ): Flow<List<T>> = flow {
        val entry = acquire(key)
        try {
            if (!entry.state.value.loaded) {
                entry.loadLock.withLock {
                    if (!entry.state.value.loaded) {
                        val revisionAtStart = entry.revision.get()
                        val items = load()
                        // A refresh or eviction can publish while the bootstrap query is in
                        // flight. Never overwrite that newer process-local state with Room data.
                        synchronized(entry) {
                            if (
                                !entry.state.value.loaded &&
                                !entry.evictionPending &&
                                entry.revision.get() == revisionAtStart
                            ) {
                                entry.state.value = Snapshot(loaded = true, items = items.toList())
                            }
                        }
                    }
                }
            }
            emitAll(entry.state.map { snapshot -> snapshot.items.take(limit) })
        } finally {
            release(key, entry)
        }
    }

    fun publish(key: String, items: List<T>) {
        while (true) {
            val entry = entries.computeIfAbsent(key) { Entry() }
            synchronized(entry) {
                if (entries[key] !== entry) continue
                if (entry.evictionPending && entry.collectors.get() == 0) {
                    entries.remove(key, entry)
                    continue
                }
                entry.revision.incrementAndGet()
                entry.state.value = Snapshot(loaded = true, items = items.toList())
                return
            }
        }
    }

    /** Returns the full in-process snapshot without applying a UI limit. */
    fun current(key: String): List<T>? {
        val entry = entries[key] ?: return null
        return synchronized(entry) {
            if (entries[key] !== entry) return@synchronized null
            entry.state.value.takeIf { it.loaded }?.items
        }
    }

    /** Removes all process-local state for a feed after durable cleanup. */
    fun evict(key: String) {
        val entry = entries[key] ?: return
        synchronized(entry) {
            if (entries[key] !== entry) return
            entry.revision.incrementAndGet()
            entry.evictionPending = true
            entry.state.value = Snapshot(loaded = true)
            if (entry.collectors.get() == 0) {
                entries.remove(key, entry)
            }
        }
    }

    private fun acquire(key: String): Entry<T> {
        while (true) {
            val entry = entries.computeIfAbsent(key) { Entry() }
            synchronized(entry) {
                if (entries[key] !== entry) continue
                if (!entry.evictionPending || entry.collectors.get() > 0) {
                    entry.collectors.incrementAndGet()
                    return entry
                }
            }
        }
    }

    private fun release(key: String, entry: Entry<T>) {
        synchronized(entry) {
            val remaining = entry.collectors.decrementAndGet()
            if (remaining == 0 && entry.evictionPending) {
                entries.remove(key, entry)
            }
        }
    }
}
