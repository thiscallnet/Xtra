package com.github.andreyasadchy.xtra.util

import android.os.SystemClock
import android.os.Trace
import com.github.andreyasadchy.xtra.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Named spans and counters exported alongside the system traces in perf builds. */
internal object PerfTraceDiagnostics {
    private class Stats {
        val count = AtomicLong()
        val totalNanos = AtomicLong()
        val maxNanos = AtomicLong()
    }

    private val stats = ConcurrentHashMap<String, Stats>()

    fun <T> section(name: String, block: () -> T): T {
        if (!BuildConfig.PERF_DIAGNOSTICS) return block()
        Trace.beginSection(name)
        val startedAt = SystemClock.elapsedRealtimeNanos()
        return try {
            block()
        } finally {
            val durationNanos = SystemClock.elapsedRealtimeNanos() - startedAt
            Trace.endSection()
            recordDuration(name, durationNanos)
        }
    }

    fun recordCount(name: String) {
        if (!BuildConfig.PERF_DIAGNOSTICS) return
        stats.getOrPut(name, ::Stats).count.incrementAndGet()
    }

    fun recordDuration(name: String, durationNanos: Long) {
        if (!BuildConfig.PERF_DIAGNOSTICS) return
        val entry = stats.getOrPut(name, ::Stats)
        entry.count.incrementAndGet()
        entry.totalNanos.addAndGet(durationNanos)
        entry.maxNanos.accumulateAndGet(durationNanos) { current, value -> maxOf(current, value) }
    }

    fun snapshotAndReset(): String = stats.entries
        .sortedBy { it.key }
        .joinToString(separator = ";") { (name, entry) ->
            val count = entry.count.getAndSet(0)
            val totalNanos = entry.totalNanos.getAndSet(0)
            val maxNanos = entry.maxNanos.getAndSet(0)
            "$name(count=$count,totalMs=${totalNanos / 1_000_000.0},maxMs=${maxNanos / 1_000_000.0})"
        }
        .ifEmpty { "none" }
}
