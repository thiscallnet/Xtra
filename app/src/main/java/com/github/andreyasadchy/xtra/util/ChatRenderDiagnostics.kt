package com.github.andreyasadchy.xtra.util

import com.github.andreyasadchy.xtra.BuildConfig
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal data class ChatRenderDiagnosticsSnapshot(
    val binds: Long,
    val draws: Long,
    val animationInvalidations: Long,
    val animationStarts: Long,
    val animationStops: Long,
    val activeAnimations: Int,
) {
    override fun toString(): String =
        "binds=$binds draws=$draws animationInvalidations=$animationInvalidations " +
            "animationStarts=$animationStarts animationStops=$animationStops " +
            "activeAnimations=$activeAnimations"
}

/** Per-report chat rendering counters for performance builds. */
internal object ChatRenderDiagnostics {
    private val binds = AtomicLong()
    private val draws = AtomicLong()
    private val animationInvalidations = AtomicLong()
    private val animationStarts = AtomicLong()
    private val animationStops = AtomicLong()
    private val activeAnimations = AtomicInteger()

    fun recordBind() {
        if (BuildConfig.PERF_DIAGNOSTICS) binds.incrementAndGet()
    }

    fun recordDraw() {
        if (BuildConfig.PERF_DIAGNOSTICS) draws.incrementAndGet()
    }

    fun recordAnimationInvalidation() {
        if (BuildConfig.PERF_DIAGNOSTICS) animationInvalidations.incrementAndGet()
    }

    fun recordAnimationStarted() {
        if (BuildConfig.PERF_DIAGNOSTICS) {
            animationStarts.incrementAndGet()
            activeAnimations.incrementAndGet()
        }
    }

    fun recordAnimationStopped() {
        if (BuildConfig.PERF_DIAGNOSTICS) {
            animationStops.incrementAndGet()
            if (activeAnimations.get() > 0) activeAnimations.decrementAndGet()
        }
    }

    fun snapshotAndReset(): ChatRenderDiagnosticsSnapshot = ChatRenderDiagnosticsSnapshot(
        binds = binds.getAndSet(0),
        draws = draws.getAndSet(0),
        animationInvalidations = animationInvalidations.getAndSet(0),
        animationStarts = animationStarts.getAndSet(0),
        animationStops = animationStops.getAndSet(0),
        activeAnimations = activeAnimations.get(),
    )
}
