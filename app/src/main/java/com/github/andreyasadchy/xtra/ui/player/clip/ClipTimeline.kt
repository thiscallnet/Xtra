package com.github.andreyasadchy.xtra.ui.player.clip

/** Keeps the frozen editor timeline exact until values cross the slider's millisecond API. */
internal object ClipTimeline {
    fun boundariesFromDurationsUs(durationsUs: IntArray): LongArray {
        require(durationsUs.isNotEmpty())

        val result = LongArray(durationsUs.size + 1)
        var totalUs = 0L
        for (index in durationsUs.indices) {
            val durationUs = durationsUs[index].toLong()
            require(durationUs > 0L)
            require(totalUs <= Long.MAX_VALUE - durationUs)
            totalUs += durationUs
            result[index + 1] = totalUs
        }
        return result
    }

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
        return boundaryIndexUs((positionMs.toDouble() * 1_000.0).toLong(), boundariesUs)
    }

    fun boundaryIndexUs(positionUs: Long, boundariesUs: LongArray): Int {
        require(boundariesUs.isNotEmpty())
        val result = boundariesUs.binarySearch(positionUs)
        if (result >= 0) return result

        val insertion = -result - 1
        if (insertion <= 0) return 0
        if (insertion >= boundariesUs.size) return boundariesUs.lastIndex

        val before = boundariesUs[insertion - 1]
        val after = boundariesUs[insertion]
        return if (positionUs - before <= after - positionUs) {
            insertion - 1
        } else {
            insertion
        }
    }
}
