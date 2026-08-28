package com.github.andreyasadchy.xtra.ui.player.clip

import androidx.media3.common.C
import java.util.Locale
import kotlin.math.roundToLong

internal object ClipSizeEstimator {
    fun estimateBytes(
        selectedDurationUs: Long,
        segments: List<ClipSegmentRef>,
        startIndex: Int,
        endIndexExclusive: Int,
        bitrateBitsPerSecond: Int?,
    ): Long? {
        if (startIndex < 0 || endIndexExclusive <= startIndex || endIndexExclusive > segments.size) {
            return null
        }
        var exactBytes = 0L
        var allRangesKnown = true
        for (index in startIndex until endIndexExclusive) {
            val byteRangeLength = segments[index].byteRangeLength
            if (byteRangeLength == C.LENGTH_UNSET.toLong()) {
                allRangesKnown = false
                break
            }
            exactBytes += byteRangeLength.coerceAtLeast(0L)
        }
        if (allRangesKnown) {
            return exactBytes
        }
        val bitrate = bitrateBitsPerSecond?.takeIf { it > 0 } ?: return null
        return (selectedDurationUs.toDouble() / 1_000_000.0 * bitrate.toDouble() / 8.0).roundToLong()
    }

    fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) {
            "${value.toLong()} ${units[unit]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unit])
        }
    }
}
