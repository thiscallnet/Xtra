package com.github.andreyasadchy.xtra.ui.player.clip

import kotlin.math.abs

/** Keeps the frozen editor timeline exact until values cross the slider's millisecond API. */
internal object ClipTimeline {
    fun normalizeBoundaries(boundariesUs: LongArray): LongArray {
        val durationUs = boundariesUs.lastOrNull()?.takeIf { it > 0L } ?: 1_000L
        return boundariesUs
            .filter { it in 0L..durationUs }
            .distinct()
            .sorted()
            .toLongArray()
            .takeIf { it.size >= 2 }
            ?: longArrayOf(0L, durationUs)
    }

    fun boundaryIndex(positionMs: Float, boundariesUs: LongArray): Int {
        require(boundariesUs.isNotEmpty())
        val positionUs = positionMs.toDouble() * 1_000.0
        return boundariesUs.indices.minBy { abs(boundariesUs[it].toDouble() - positionUs) }
    }

    fun boundaryIndexUs(positionUs: Long, boundariesUs: LongArray): Int {
        require(boundariesUs.isNotEmpty())
        return boundariesUs.indices.minBy { abs(boundariesUs[it] - positionUs) }
    }
}
