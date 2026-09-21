package com.github.andreyasadchy.xtra.ui.player

/** Infers a sustained buffer decline from playback progress samples. */
internal class LiveBufferHealthTrend {
    private data class Sample(val timeMs: Long, val bufferMs: Long)

    data class Reading(
        val bufferSeconds: Int,
        val liveOffsetSeconds: Int,
        val isDecreasing: Boolean,
    )

    private val samples = ArrayDeque<Sample>()
    private var isDecreasing = false

    fun update(bufferMs: Long?, liveOffsetMs: Long?, nowMs: Long): Reading? {
        if (bufferMs == null || bufferMs < 0L || liveOffsetMs == null) {
            reset()
            return null
        }

        val previous = samples.lastOrNull()
        if (previous == null || nowMs - previous.timeMs >= SAMPLE_INTERVAL_MS) {
            samples.addLast(Sample(nowMs, bufferMs))
        }
        while (samples.size > 1 && nowMs - samples.first().timeMs > TREND_WINDOW_MS) {
            samples.removeFirst()
        }

        val first = samples.firstOrNull()
        val last = samples.lastOrNull()
        if (first != null && last != null && last.timeMs - first.timeMs >= MIN_TREND_DURATION_MS) {
            val changeMs = last.bufferMs - first.bufferMs
            isDecreasing = if (isDecreasing) {
                changeMs <= -TREND_EXIT_THRESHOLD_MS
            } else {
                changeMs <= -TREND_ENTER_THRESHOLD_MS
            }
        }

        return Reading(
            bufferSeconds = ((bufferMs + 500L) / 1_000L).toInt(),
            liveOffsetSeconds = (liveOffsetMs.coerceAtLeast(0L) / 1_000L).toInt(),
            isDecreasing = isDecreasing,
        )
    }

    fun reset() {
        samples.clear()
        isDecreasing = false
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 500L
        const val MIN_TREND_DURATION_MS = 4_000L
        const val TREND_WINDOW_MS = 5_000L
        const val TREND_ENTER_THRESHOLD_MS = 1_500L
        const val TREND_EXIT_THRESHOLD_MS = 500L
    }
}
