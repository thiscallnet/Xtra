package com.github.andreyasadchy.xtra.ui.player.clip

/** Frozen, contiguous metadata for one local live-clip preparation. */
data class ClipSnapshot(
    val generation: Long,
    val renditionId: String,
    val segments: List<ClipSegmentRef>,
) {
    val durationUs: Long
        get() = segments.sumOf { it.durationUs }

    val firstMediaSequence: Long
        get() = segments.firstOrNull()?.mediaSequence ?: 0L

    val drmInitDataPresent: Boolean
        get() = segments.any { it.drmInitDataPresent }

    /** Boundaries relative to the beginning of the frozen playlist, including 0 and the end. */
    val boundariesUs: LongArray
        get() {
            val result = LongArray(segments.size + 1)
            var positionUs = 0L
            segments.forEachIndexed { index, segment ->
                result[index] = positionUs
                positionUs += segment.durationUs
            }
            result[segments.size] = positionUs
            return result
        }
}
