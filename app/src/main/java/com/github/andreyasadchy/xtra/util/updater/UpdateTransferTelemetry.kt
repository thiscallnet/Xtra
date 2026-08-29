package com.github.andreyasadchy.xtra.util.updater

import kotlin.math.ceil

data class TransferRateSample(
    val bytesPerSecond: Long,
    val stalled: Boolean,
)

/** Estimates the current transfer rate from successive DownloadManager samples. */
class TransferRateEstimator(
    private val smoothingFactor: Double = 0.25,
    private val stallAfterMs: Long = 4_000L,
) {
    private var previousBytes: Long? = null
    private var previousTimeMs: Long? = null
    private var lastProgressTimeMs: Long? = null
    private var smoothedBytesPerSecond = 0.0

    fun reset() {
        previousBytes = null
        previousTimeMs = null
        lastProgressTimeMs = null
        smoothedBytesPerSecond = 0.0
    }

    fun sample(downloadedBytes: Long, nowMs: Long): TransferRateSample {
        val oldBytes = previousBytes
        val oldTime = previousTimeMs
        if (oldBytes == null || oldTime == null || downloadedBytes < oldBytes) {
            previousBytes = downloadedBytes
            previousTimeMs = nowMs
            lastProgressTimeMs = nowMs
            smoothedBytesPerSecond = 0.0
            return TransferRateSample(0L, stalled = false)
        }

        val deltaBytes = downloadedBytes - oldBytes
        val deltaMs = nowMs - oldTime
        if (deltaBytes > 0L) lastProgressTimeMs = nowMs

        // DownloadManager is sampled frequently, but rate estimates need a useful interval.
        if (deltaMs >= 250L) {
            if (deltaBytes > 0L && deltaMs > 0L) {
                val instantaneous = deltaBytes.toDouble() * 1000.0 / deltaMs.toDouble()
                smoothedBytesPerSecond = if (smoothedBytesPerSecond <= 0.0) {
                    instantaneous
                } else {
                    smoothingFactor * instantaneous +
                        (1.0 - smoothingFactor) * smoothedBytesPerSecond
                }
            }
            previousBytes = downloadedBytes
            previousTimeMs = nowMs
        }

        val stalled = lastProgressTimeMs?.let { nowMs - it >= stallAfterMs } ?: false
        return TransferRateSample(
            bytesPerSecond = if (stalled) 0L else smoothedBytesPerSecond.toLong().coerceAtLeast(0L),
            stalled = stalled,
        )
    }
}

internal fun calculateEtaSeconds(
    downloadedBytes: Long,
    totalBytes: Long?,
    bytesPerSecond: Long,
): Long? {
    if (totalBytes == null || totalBytes <= downloadedBytes || bytesPerSecond <= 0L) return null
    return ceil((totalBytes - downloadedBytes).toDouble() / bytesPerSecond.toDouble()).toLong()
}
