package com.github.andreyasadchy.xtra.util

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.github.andreyasadchy.xtra.BuildConfig
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/** Debug-only diagnostics for stalls that are long enough to delay input. */
internal object MainLooperStallWatchdog {
    private const val TAG = "XtraMainStall"
    private const val CHECK_INTERVAL_MS = 100L
    private const val REPORT_INTERVAL_MS = 1_000L
    private const val REPORT_AFTER_MS = 32L
    private const val STACK_AFTER_MS = 200L
    private const val DUMP_ALL_THREADS_AFTER_MS = 1_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadScheduledExecutor(
        ThreadFactory { runnable ->
            Thread(runnable, "xtra-main-stall-watchdog").apply { isDaemon = true }
        },
    )

    @Volatile
    private var lastHeartbeatMs = SystemClock.uptimeMillis()

    @Volatile
    private var lastReportMs = 0L
    private val stallBuckets = LongArray(7)
    private var worstStallMs = 0L

    fun start() {
        if (!BuildConfig.DEBUG) return
        executor.scheduleAtFixedRate(
            {
                val now = SystemClock.uptimeMillis()
                mainHandler.post { lastHeartbeatMs = SystemClock.uptimeMillis() }
                val stallMs = now - lastHeartbeatMs
                if (stallMs < REPORT_AFTER_MS || now - lastReportMs < REPORT_INTERVAL_MS) return@scheduleAtFixedRate
                recordStall(stallMs)
                lastReportMs = now
                report(stallMs)
            },
            CHECK_INTERVAL_MS,
            CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun report(stallMs: Long) {
        val mainThread = Looper.getMainLooper().thread
        val message = buildString {
            append("MAIN STALL sample=")
            append(stallMs)
            append(" ms worst=")
            append(worstStallMs)
            append("ms buckets=")
            append(stallBuckets.joinToString(","))
            if (stallMs >= STACK_AFTER_MS) {
                append("\nmain:\n")
                append(mainThread.stackTrace.joinToString("\n") { "    at $it" })
            }
            if (stallMs >= DUMP_ALL_THREADS_AFTER_MS) {
                append("\nall threads:\n")
                Thread.getAllStackTraces().forEach { (thread, stack) ->
                    if (thread === mainThread) return@forEach
                    append(thread.name)
                    append(" [")
                    append(thread.state)
                    append("]:\n")
                    append(stack.joinToString("\n") { "    at $it" })
                    append('\n')
                }
            }
        }
        Log.w(TAG, message)
    }

    private fun recordStall(stallMs: Long) {
        val bucket = when {
            stallMs <= 32L -> 0
            stallMs <= 50L -> 1
            stallMs <= 100L -> 2
            stallMs <= 250L -> 3
            stallMs <= 500L -> 4
            stallMs <= 1_000L -> 5
            else -> 6
        }
        stallBuckets[bucket]++
        worstStallMs = maxOf(worstStallMs, stallMs)
    }
}
