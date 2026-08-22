package com.github.andreyasadchy.xtra.repository.preload

/**
 * Prevents a media operation queued for an obsolete viewport/lifecycle state
 * from applying after a newer clear or configuration invalidation.
 */
internal class StreamMediaPreloadOperationGate {
    private val lock = Any()
    private var epoch = 0L

    fun begin(): Long = synchronized(lock) {
        ++epoch
    }

    fun invalidate() = synchronized(lock) {
        ++epoch
    }

    fun runIfCurrent(operationEpoch: Long, operation: () -> Unit): Boolean = synchronized(lock) {
        if (epoch != operationEpoch) return@synchronized false
        operation()
        true
    }
}
