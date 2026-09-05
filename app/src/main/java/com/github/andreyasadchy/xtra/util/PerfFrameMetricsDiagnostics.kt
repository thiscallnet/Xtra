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

    private val listener = Window.OnFrameMetricsAvailableListener { window, metrics, _ ->
        val durationNs = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        val refreshRateHz = window.decorView.display?.refreshRate?.takeIf { it > 1f } ?: 60f
        val frameBudgetNs = (1_000_000_000.0 / refreshRateHz).toLong()
        val durationMs = durationNs / 1_000_000L
        val bucket = when {
            durationNs <= frameBudgetNs -> 0
            durationNs <= frameBudgetNs * 3L / 2L -> 1
            durationNs <= frameBudgetNs * 2L -> 2
            durationNs <= frameBudgetNs * 3L -> 3
            durationNs <= frameBudgetNs * 6L -> 4
            durationNs <= frameBudgetNs * 12L -> 5
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
                "frames=$frameCount refreshHz=$refreshRateHz budgetMs=${frameBudgetNs / 1_000_000.0} " +
                    "worstMs=$worstFrameMs budgetBuckets=${buckets.joinToString(",")}",
            )
            Log.i(TAG, "chatRender=${ChatRenderDiagnostics.snapshotAndReset()}")
            Log.i(TAG, "keystorePrefs=${KeystorePreferenceDiagnostics.snapshot()}")
            buckets.fill(0)
            frameCount = 0L
            worstFrameMs = 0L
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
