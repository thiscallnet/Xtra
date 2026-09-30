package com.github.andreyasadchy.xtra.player.hls

import com.github.andreyasadchy.xtra.util.m3u8.TwitchVaftDetector
import java.util.Locale
import kotlin.math.round

data class TwitchPlaylistAdaptation(
    val playlistText: String,
    val diagnostics: TwitchHlsPlaylistDiagnostics,
)

data class TwitchHlsPlaylistDiagnostics(
    val declaredTargetDurationMs: Long? = null,
    val averageSegmentDurationMs: Long? = null,
    val twitchPrefetchCount: Int = 0,
    val twitchPrefetchDetected: Boolean = false,
    val twitchPrefetchTranslated: Boolean = false,
    val twitchPrefetchActive: Boolean = false,
    val twitchPrefetchSuppressed: Boolean = false,
    val standardLowLatencyTagsPresent: Boolean = false,
    val hasExtXMap: Boolean = false,
    val effectiveReloadTargetDurationMs: Long? = null,
    val partTargetDurationMs: Long? = null,
    val container: String? = null,
    val parseIndex: Int = 0,
    val rawHasEndTag: Boolean = false,
    val adaptedHasEndTag: Boolean = false,
    val parsedBeforeInterstitialRestoreHasEndTag: Boolean? = null,
    val restoredInterstitialCount: Int = 0,
    val parsedHasEndTag: Boolean? = null,
    val parsedHasIndependentSegments: Boolean? = null,
    val parsedHasProgramDateTime: Boolean? = null,
    val parsedPreciseStart: Boolean? = null,
    val parsedPlaylistType: Int? = null,
    val mediaSequence: Long? = null,
    val playlistDurationMs: Long? = null,
    val segmentCount: Int? = null,
)

/**
 * Keeps Twitch's private prefetch extension outside the Media3 parser and translates it only when
 * low-latency playback is requested. The stock parser remains responsible for all HLS semantics.
 */
object TwitchLowLatencyPlaylistAdapter {

    private const val EMPTY_ASSET_URI_PLACEHOLDER = "xtra-empty-asset"
    private const val PREFETCH_TAG = "#EXT-X-TWITCH-PREFETCH:"
    private const val EXTINF_PREFIX = "#EXTINF:"
    private const val DISCONTINUITY_TAG = "#EXT-X-DISCONTINUITY"
    private const val ENDLIST_TAG = "#EXT-X-ENDLIST"
    private const val VOD_PLAYLIST_TYPE = "#EXT-X-PLAYLIST-TYPE:VOD"
    private const val STANDARD_INTERSTITIAL_CLASS = "com.apple.hls.interstitial"

    private val targetDurationPattern = Regex("^#EXT-X-TARGETDURATION:(\\d+)\\b")
    private val extInfPattern = Regex("^#EXTINF:([0-9]+(?:\\.[0-9]+)?)(?:,.*|$)")
    private val idPattern = Regex("(?:^|[:,])ID=\"([^\"]*)\"")
    private val classPattern = Regex("(?:^|[:,])CLASS=\"([^\"]*)\"")
    private val classAttributePattern = Regex("(?:^|,)CLASS=\"[^\"]*\"")
    private val twitchVaftAttributePattern = Regex("(?:^|,)X-TV-TWITCH-AD-[A-Z0-9-]+=")
    private val startDatePattern = Regex("(?:^|,)START-DATE=\"[^\"]+\"")
    private val assetUriPattern = Regex("(?:^|,)X-ASSET-URI=")
    private val assetListPattern = Regex("(?:^|,)X-ASSET-LIST=")

    fun adapt(
        raw: String,
        enabled: Boolean,
        suppressTranslation: Boolean = false,
    ): TwitchPlaylistAdaptation {
        val rawLines = splitLines(raw)
        val lines = rawLines.map(::normalizeLine)
        val normalizedText = if (lines == rawLines) raw else lines.joinToString("\n")

        val targetDurationMs = lines.firstNotNullOfOrNull { line ->
            targetDurationPattern.matchEntire(line)?.groupValues?.get(1)?.toLongOrNull()?.times(1000L)
        }
        val durations = lines.mapNotNull { line ->
            extInfPattern.matchEntire(line)?.groupValues?.get(1)?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }
        }
        val recentDurations = durations.takeLast(6)
        val averageDurationMs = recentDurations.averageOrNull()?.times(1000.0)?.toLong()
        val prefetchLines = lines.filter { it.startsWith(PREFETCH_TAG) }
        val prefetchUris = prefetchLines.map { it.substring(PREFETCH_TAG.length).trim() }
        val standardLowLatency = lines.any { line ->
            line.startsWith("#EXT-X-PART:") || line.startsWith("#EXT-X-PRELOAD-HINT:")
        }
        val hasMap = lines.any { it.startsWith("#EXT-X-MAP:") }
        val isVod = lines.any { it.equals(VOD_PLAYLIST_TYPE, ignoreCase = true) } ||
            lines.any { it.equals(ENDLIST_TAG, ignoreCase = true) }
        val discontinuityBeforePrefetch = hasDiscontinuityBeforePrefetch(lines)
        val rawVaftBoundary = lines.any(::isTwitchVaftDateRange) ||
            lastCommittedSegmentTitle(lines)?.let { TwitchVaftDetector.isVaftTitle(it) } == true
        val prefetchDetected = prefetchLines.isNotEmpty()
        val suppressed = prefetchDetected &&
            (suppressTranslation || rawVaftBoundary || discontinuityBeforePrefetch)
        val estimatedDuration = estimateUpcomingDuration(recentDurations)
        val canTranslate = enabled &&
            !isVod &&
            prefetchDetected &&
            prefetchUris.all(String::isNotBlank) &&
            !standardLowLatency &&
            !suppressed
        val effectiveTargetDurationSeconds = if (canTranslate) {
            effectiveTwitchTargetDurationSeconds(
                declaredSeconds = targetDurationMs?.div(1_000L)?.toInt(),
                committedDurations = recentDurations,
            )
        } else {
            null
        }

        val playlistText = if (canTranslate) {
            translatePrefetch(
                lines,
                prefetchUris,
                estimatedDuration,
                requireNotNull(effectiveTargetDurationSeconds),
            )
        } else {
            normalizedText
        }
        val translated = canTranslate
        val container = detectContainer(lines)

        return TwitchPlaylistAdaptation(
            playlistText = playlistText,
            diagnostics = TwitchHlsPlaylistDiagnostics(
                declaredTargetDurationMs = targetDurationMs,
                averageSegmentDurationMs = averageDurationMs,
                twitchPrefetchCount = prefetchLines.size,
                twitchPrefetchDetected = prefetchDetected,
                twitchPrefetchTranslated = translated,
                twitchPrefetchSuppressed = suppressed,
                standardLowLatencyTagsPresent = standardLowLatency,
                hasExtXMap = hasMap,
                effectiveReloadTargetDurationMs = effectiveTargetDurationSeconds?.times(1_000L),
                container = container,
            ),
        )
    }

    internal fun estimateUpcomingDuration(extInfDurations: List<Double>): Double {
        val recent = extInfDurations
            .filter { it.isFinite() && it > 0.0 }
            .takeLast(6)

        return recent.averageOrNull()?.coerceIn(0.25, 10.0) ?: 2.0
    }

    internal fun effectiveTwitchTargetDurationSeconds(
        declaredSeconds: Int?,
        committedDurations: List<Double>,
    ): Int {
        val observed = committedDurations
            .filter { it.isFinite() && it > 0.0 }
            .takeLast(6)
        if (observed.isEmpty()) {
            return minOf(declaredSeconds ?: 2, 2)
        }

        val observedTarget = observed.maxOf {
            round(it).toInt().coerceAtLeast(1)
        }
        return declaredSeconds?.let { minOf(it, observedTarget) } ?: observedTarget
    }

    /**
     * Media3 1.11's parser does not match an empty quoted asset URI. Keep the public adaptation
     * faithful to Twitch's marker, but provide a private parse-only value for the compatibility
     * fallback that restores the resulting interstitial with Uri.EMPTY.
     */
    internal fun parserCompatibleWithEmptyAssetUris(playlistText: String): String =
        playlistText.replace(
            "X-ASSET-URI=\"\"",
            "X-ASSET-URI=\"$EMPTY_ASSET_URI_PLACEHOLDER\"",
        )

    internal fun isEmptyAssetUriPlaceholder(uri: String?): Boolean =
        uri == EMPTY_ASSET_URI_PLACEHOLDER

    private fun translatePrefetch(
        lines: List<String>,
        prefetchUris: List<String>,
        duration: Double,
        effectiveTargetDurationSeconds: Int,
    ): String {
        val formattedDuration = formatSeconds(duration)
        val output = ArrayList<String>(lines.size + 2)
        var prefetchAdded = false
        lines.forEach { line ->
            if (targetDurationPattern.matches(line)) {
                output += "#EXT-X-TARGETDURATION:$effectiveTargetDurationSeconds"
                return@forEach
            }
            if (!line.startsWith(PREFETCH_TAG)) {
                output += line
                return@forEach
            }
            if (!prefetchAdded) {
                // Prefetch URLs are unfinished segments, not committed EXTINF entries.
                // Publishing two as completed segments advances Media3 beyond the next
                // snapshot when Twitch temporarily removes prefetch (e.g. at a VAFT).
                // Only the immediate next sequence can be represented as a trailing part;
                // the following prefetch belongs to a different, unpublished sequence.
                val uri = normalizeSegmentUri(prefetchUris.first())
                output += "#EXT-X-PART-INF:PART-TARGET=$formattedDuration"
                output += "#EXT-X-PART:DURATION=$formattedDuration,URI=\"$uri\",INDEPENDENT=YES"
                prefetchAdded = true
            }
        }
        return output.joinToString("\n")
    }

    private fun normalizeLine(line: String): String {
        val trimmed = line.trim()
        if (trimmed.startsWith("#EXT-X-DATERANGE:")) {
            return normalizeTwitchVaftDateRange(trimmed)
        }
        if (!trimmed.startsWith("#")) {
            return normalizeSegmentUri(trimmed)
        }
        return trimmed
    }

    private fun normalizeSegmentUri(uri: String): String = uri.replace("-unmuted", "-muted")

    private fun normalizeTwitchVaftDateRange(line: String): String {
        if (!isTwitchVaftDateRange(line)) return line

        val attributes = line.substringAfter(':')
        val currentClass = classPattern.find(attributes)?.groupValues?.get(1)
        var normalizedAttributes = if (currentClass == null) {
            "$attributes,CLASS=\"$STANDARD_INTERSTITIAL_CLASS\""
        } else if (currentClass == STANDARD_INTERSTITIAL_CLASS) {
            attributes
        } else {
            attributes.replace(classAttributePattern) {
                ",CLASS=\"$STANDARD_INTERSTITIAL_CLASS\""
            }.removePrefix(",")
        }
        if (currentClass != null && currentClass != STANDARD_INTERSTITIAL_CLASS &&
            !twitchVaftAttributePattern.containsMatchIn(normalizedAttributes)
        ) {
            normalizedAttributes += ",X-TV-TWITCH-AD-CLASS=\"$currentClass\""
        }
        if (startDatePattern.containsMatchIn(normalizedAttributes) &&
            !assetUriPattern.containsMatchIn(normalizedAttributes) &&
            !assetListPattern.containsMatchIn(normalizedAttributes)
        ) {
            normalizedAttributes += ",X-ASSET-URI=\"\""
        }
        return "#EXT-X-DATERANGE:$normalizedAttributes"
    }

    private fun isTwitchVaftDateRange(line: String): Boolean {
        if (!line.startsWith("#EXT-X-DATERANGE:")) return false
        val id = idPattern.find(line)?.groupValues?.get(1).orEmpty()
        val className = classPattern.find(line)?.groupValues?.get(1).orEmpty()
        return TwitchVaftDetector.isTwitchVaftDateRange(
            id = id,
            rangeClass = className,
            hasVaftAttribute = twitchVaftAttributePattern.containsMatchIn(line),
        )
    }

    private fun lastCommittedSegmentTitle(lines: List<String>): String? =
        lines.asSequence()
            .takeWhile { !it.startsWith(PREFETCH_TAG) }
            .filter { it.startsWith(EXTINF_PREFIX) }
            .map { it.substringAfter(',', "").trim() }
            .lastOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun hasDiscontinuityBeforePrefetch(lines: List<String>): Boolean {
        var pendingSegment = false
        var committedSegmentSeen = false
        var discontinuityAfterCommittedSegment = false

        for (line in lines) {
            when {
                line.startsWith(EXTINF_PREFIX) -> pendingSegment = true
                line == DISCONTINUITY_TAG -> {
                    if (committedSegmentSeen) discontinuityAfterCommittedSegment = true
                }
                line.startsWith(PREFETCH_TAG) -> {
                    if (discontinuityAfterCommittedSegment) return true
                }
                pendingSegment && !line.startsWith("#") -> {
                    committedSegmentSeen = true
                    discontinuityAfterCommittedSegment = false
                    pendingSegment = false
                }
            }
        }
        return false
    }

    private fun detectContainer(lines: List<String>): String? {
        if (lines.any { it.startsWith("#EXT-X-MAP:") }) return "fMP4/CMAF"
        val mediaUris = lines.filter { line ->
            !line.startsWith("#") && line.isNotBlank()
        }
        return if (mediaUris.isNotEmpty() && mediaUris.all(::looksLikeTsUri)) {
            "MPEG-TS"
        } else null
    }

    private fun looksLikeTsUri(uri: String): Boolean {
        val path = uri.substringBefore('?').substringBefore('#')
        return path.endsWith(".ts", ignoreCase = true)
    }

    private fun splitLines(raw: String): List<String> =
        raw.replace("\r\n", "\n").replace('\r', '\n').split('\n').map(String::trim)

    private fun formatSeconds(value: Double): String =
        String.format(Locale.US, "%.3f", value)
            .trimEnd('0')
            .trimEnd('.')

    private fun List<Double>.averageOrNull(): Double? =
        if (isEmpty()) null else average()
}
