package com.github.andreyasadchy.xtra.util

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import com.github.andreyasadchy.xtra.BuildConfig

/** Low-overhead frame timing buckets for the release-equivalent perf build. */
internal object PerfFrameMetricsDiagnostics {
    private const val TAG = "XtraFrameMetrics"
    private const val REPORT_INTERVAL_MS = 5_000L
    private val handler = Handler(Looper.getMainLooper())
    private val buckets = LongArray(7)
    private var frameCount = 0L
    private var worstFrameMs = 0L
    private var lastReportMs = 0L
    private var attachedWindow: Window? = null

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val durationMs = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000L
        val bucket = when {
            durationMs <= 16L -> 0
            durationMs <= 32L -> 1
            durationMs <= 50L -> 2
            durationMs <= 100L -> 3
            durationMs <= 250L -> 4
            durationMs <= 500L -> 5
            else -> 6
        }
        buckets[bucket]++
        frameCount++
        worstFrameMs = maxOf(worstFrameMs, durationMs)
        val now = SystemClock.uptimeMillis()
        if (now - lastReportMs >= REPORT_INTERVAL_MS) {
            lastReportMs = now
            Log.i(
                TAG,
                "frames=$frameCount worstMs=$worstFrameMs buckets=${buckets.joinToString(",")}",
            )
            Log.i(TAG, "keystorePrefs=${KeystorePreferenceDiagnostics.snapshot()}")
        }
    }

    fun attach(activity: Activity) {
        if (!BuildConfig.PERF_DIAGNOSTICS || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (attachedWindow === activity.window) return
        detach()
        attachedWindow = activity.window
        activity.window.addOnFrameMetricsAvailableListener(listener, handler)
    }

    fun detach() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            attachedWindow?.removeOnFrameMetricsAvailableListener(listener)
        }
        attachedWindow = null
    }
}
