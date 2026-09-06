package com.github.andreyasadchy.xtra.ui.player

import java.util.Locale
import kotlin.math.roundToLong

data class PlaybackVideoViewMetrics(
    val viewportWidth: Int? = null,
    val viewportHeight: Int? = null,
    val renderSurface: String? = null,
)

internal data class VideoInfoRow(
    val name: String,
    val value: String,
)

private fun MutableList<VideoInfoRow>.add(row: Pair<String, String>) {
    add(VideoInfoRow(row.first, row.second))
}

internal fun formatVideoResolution(width: Int?, height: Int?): String {
    return if (width != null && width > 0 && height != null && height > 0) {
        "$width × $height"
    } else {
        UNKNOWN_VALUE
    }
}

internal fun formatVideoFrameRate(frameRate: Float?): String {
    if (frameRate == null || !frameRate.isFinite() || frameRate <= 0f) {
        return UNKNOWN_VALUE
    }
    return "${formatDecimal(frameRate.toDouble(), 2)} fps"
}

internal fun formatBitrate(bitsPerSecond: Long?): String {
    if (bitsPerSecond == null || bitsPerSecond <= 0L) {
        return UNKNOWN_VALUE
    }
    return if (bitsPerSecond >= 10_000_000L) {
        "${formatDecimal(bitsPerSecond / 1_000_000.0, 1)} Mbps"
    } else {
        "${(bitsPerSecond / 1_000.0).roundToLong()} Kbps"
    }
}

internal fun firstPositiveBitrate(vararg bitrates: Int): Int? =
    bitrates.firstOrNull { it > 0 }

internal fun formatDurationMs(durationMs: Long?): String {
    if (durationMs == null || durationMs < 0L || durationMs == androidx.media3.common.C.TIME_UNSET) {
        return UNKNOWN_VALUE
    }
    return "${formatDecimal(durationMs / 1_000.0, 1)} s"
}

internal fun videoInfoRows(
    info: PlaybackVideoInfo,
    viewMetrics: PlaybackVideoViewMetrics,
): List<VideoInfoRow> = buildList {
    add("Download resolution" to formatVideoResolution(info.selectedVideoWidth, info.selectedVideoHeight))
    add("Render resolution" to formatVideoResolution(info.renderedVideoWidth, info.renderedVideoHeight))
    add("Viewport resolution" to formatVideoResolution(viewMetrics.viewportWidth, viewMetrics.viewportHeight))
    add("Download bitrate" to formatBitrate(info.videoBitrate?.toLong()))
    add("Bandwidth estimate" to formatBitrate(info.bandwidthEstimateBitsPerSecond))
    add("FPS" to formatVideoFrameRate(info.videoFrameRate))
    add("Skipped frames" to info.droppedVideoFrames.toString())
    add("Buffer size" to formatDurationMs(info.bufferMs))
    add("Latency to broadcaster" to formatDurationMs(info.liveOffsetMs))

    val codecs = listOfNotNull(info.videoCodec, info.audioCodec)
        .filter { it.isNotBlank() }
        .joinToString(", ")
    add("Codecs" to codecs.ifBlank { UNKNOWN_VALUE })
    add("Protocol" to info.contentProtocol.orUnknown())
    add("Latency mode" to latencyMode(info))
    add("Render surface" to viewMetrics.renderSurface.orUnknown())

    val decoder = info.videoDecoderName?.takeIf { it.isNotBlank() }?.let { name ->
        when (info.videoDecoderHardwareAccelerated) {
            true -> "$name (HW)"
            false -> "$name (SW)"
            null -> name
        }
    }
    add("Video decoder" to decoder.orUnknown())
    add("Network backend" to info.networkBackend.orUnknown())
    add("HTTP transport" to info.negotiatedProtocol.orUnknown())
    add("Container" to info.hlsContainer.orUnknown())
    add("Media3 version" to info.media3Version.orUnknown())
    add("HLS target duration" to formatDurationMs(info.declaredTargetDurationMs))
    add("Effective reload target" to formatDurationMs(info.effectiveReloadTargetDurationMs))
    add("Average segment duration" to formatDurationMs(info.averageSegmentDurationMs))
    add("Part target duration" to formatDurationMs(info.partTargetDurationMs))
    add("Twitch prefetch" to twitchPrefetchState(info))
    add("Manifest requests" to info.manifestLoadCount.toString())
    add("Media chunk requests" to info.mediaLoadCount.toString())
}

internal fun latencyMode(info: PlaybackVideoInfo): String {
    if (!info.isLiveContent) return UNKNOWN_VALUE
    return when {
        info.lowLatencyRequested && info.twitchPrefetchActive == true ->
            "Low latency (Twitch prefetch active)"
        info.lowLatencyRequested -> "Low latency"
        else -> "Normal"
    }
}

internal fun twitchPrefetchState(info: PlaybackVideoInfo): String = when {
    info.twitchPrefetchSuppressed == true -> "Suppressed at discontinuity/ad boundary"
    info.twitchPrefetchActive == true -> "Active"
    info.twitchPrefetchPresent == true -> "Present"
    info.twitchPrefetchPresent == false -> "Not present"
    else -> UNKNOWN_VALUE
}

internal fun PlaybackVideoInfo.toSanitizedText(
    viewMetrics: PlaybackVideoViewMetrics,
): String = videoInfoRows(this, viewMetrics).joinToString("\n") { row ->
    "${row.name}: ${sanitizeVideoInfoValue(row.value)}"
}

internal fun sanitizeVideoInfoValue(value: String): String {
    return value
        .replace(SENSITIVE_URL_PATTERN, "<redacted>")
        .replace(SENSITIVE_PARAMETER_PATTERN, "<redacted>")
}

private fun String?.orUnknown(): String = this?.takeIf { it.isNotBlank() } ?: UNKNOWN_VALUE

private fun formatDecimal(value: Double, maxFractionDigits: Int): String {
    return String.format(Locale.US, "%.${maxFractionDigits}f", value)
        .trimEnd('0')
        .trimEnd('.')
}

private const val UNKNOWN_VALUE = "—"
private val SENSITIVE_URL_PATTERN = Regex("(?i)\\b(?:https?|wss?)://\\S+")
private val SENSITIVE_PARAMETER_PATTERN = Regex(
    "(?i)\\b(?:access[_-]?token|token|sig|cookie|authorization)\\s*[:=]\\s*[^\\s,;]+",
)
