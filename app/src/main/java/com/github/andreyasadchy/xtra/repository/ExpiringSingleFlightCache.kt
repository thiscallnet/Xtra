package com.github.andreyasadchy.xtra.repository

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Keeps successful values briefly and shares an in-flight load per key.
 * The loader starts after the bookkeeping lock is released.
 */
internal class ExpiringSingleFlightCache<K, V>(
    private val ttlMillis: Long,
    private val scope: CoroutineScope,
    private val loadSemaphore: Semaphore? = null,
    private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() },
    private val maxEntries: Int = 128,
) {
    private val mutex = Mutex()
    private val entries = LinkedHashMap<K, Entry<V>>(16, .75f, true)
    private val inFlight = mutableMapOf<K, Flight<V>>()

    init {
        require(ttlMillis > 0L) { "Cache TTL must be positive" }
        require(maxEntries > 0) { "Cache size must be positive" }
    }

    suspend fun get(
        key: K,
        force: Boolean = false,
        loader: suspend () -> V?,
    ): V? {
        val lookup = mutex.withLock {
            val now = nowMillis()
            entries.entries.removeAll { now - it.value.createdAtMillis !in 0 until ttlMillis }
            entries[key]?.takeIf {
                !force && now - it.createdAtMillis >= 0L && now - it.createdAtMillis < ttlMillis
            }?.let { entry ->
                return@withLock Lookup(
                    result = CompletableDeferred<V?>().apply { complete(entry.value) },
                    job = null,
                )
            }
            inFlight[key]?.let { flight ->
                return@withLock Lookup(flight.result, job = null)
            }

            val result = CompletableDeferred<V?>()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    // Keep the permit while the loader suspends, including during network I/O
                    // that switches to another dispatcher.
                    val value = if (loadSemaphore != null) loadSemaphore.withPermit { loader() } else loader()
                    val ownsFlight = mutex.withLock {
                        if (inFlight[key]?.result !== result) {
                            false
                        } else {
                            if (value != null) {
                                entries[key] = Entry(nowMillis(), value)
                                while (entries.size > maxEntries) {
                                    entries.entries.iterator().apply { next(); remove() }
                                }
                            }
                            inFlight.remove(key)
                            true
                        }
                    }
                    if (ownsFlight) result.complete(value)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    val ownsFlight = withContext(NonCancellable) {
                        mutex.withLock {
                            if (inFlight[key]?.result !== result) {
                                false
                            } else {
                                inFlight.remove(key)
                                true
                            }
                        }
                    }
                    if (ownsFlight) result.completeExceptionally(error)
                }
            }
            job.invokeOnCompletion { error ->
                if (error != null) result.completeExceptionally(error)
            }
            inFlight[key] = Flight(result, job)
            Lookup(result, job)
        }

        lookup.job?.start()
        return lookup.result.await()
    }

    suspend fun clear() {
        val flights = mutex.withLock {
            entries.clear()
            inFlight.values.toList().also { inFlight.clear() }
        }
        flights.forEach { flight ->
            flight.job.cancel()
            flight.result.cancel()
        }
    }

    private data class Entry<V>(
        val createdAtMillis: Long,
        val value: V,
    )

    private data class Flight<V>(
        val result: CompletableDeferred<V?>,
        val job: Job,
    )

    private data class Lookup<V>(
        val result: CompletableDeferred<V?>,
        val job: Job?,
    )
}
