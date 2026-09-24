package com.github.andreyasadchy.xtra.ui.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.chunk.MediaChunk
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.ForwardingTrackSelection
import androidx.media3.exoplayer.trackselection.TrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.github.andreyasadchy.xtra.BuildConfig
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.floor

/** The requested quality, kept independent from the format currently being loaded. */
data class DesiredHlsQuality(
    val name: String,
    val bitrate: Int? = null,
    val codecs: String? = null,
) {
    val isAuto: Boolean
        get() = name.equals(BasePlaybackService.AUTO_QUALITY, ignoreCase = true)

    private val dimensions: Pair<Int, Int>?
        get() = QUALITY_DIMENSIONS.find(name)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { height ->
                height to (match.groupValues[2].toIntOrNull() ?: 30)
            }
        }

    @OptIn(UnstableApi::class)
    fun matches(format: Format): Boolean {
        val labelMatches = format.label.equals(name, ignoreCase = true)
        val bitrateMatches = bitrate == null || format.bitrate <= 0 || format.bitrate <= bitrate
        val codecsMatch = videoCodecsMatch(codecs, format.codecs)
        val variantMatches = bitrateMatches && codecsMatch
        if (labelMatches && variantMatches) return true

        val (height, fps) = dimensions ?: return name.equals(BasePlaybackService.SOURCE_QUALITY, ignoreCase = true) &&
            variantMatches
        return format.height == height && floor(format.frameRate).toInt() <= fps && variantMatches
    }

    @OptIn(UnstableApi::class)
    private fun videoCodecsMatch(desiredCodecs: String?, formatCodecs: String?): Boolean {
        if (desiredCodecs.isNullOrBlank() || formatCodecs.isNullOrBlank()) return true

        val desiredVideoCodecs = videoCodecTokens(desiredCodecs)
        val formatVideoCodecs = videoCodecTokens(formatCodecs)
        if (desiredVideoCodecs.isEmpty() || formatVideoCodecs.isEmpty()) return true

        return desiredVideoCodecs.any(formatVideoCodecs::contains)
    }

    @OptIn(UnstableApi::class)
    private fun videoCodecTokens(codecs: String): Set<String> =
        codecs.split(',')
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filter { MimeTypes.getTrackTypeOfCodec(it) == C.TRACK_TYPE_VIDEO }
            .map(String::lowercase)
            .toSet()

    companion object {
        private val QUALITY_DIMENSIONS = Regex("(\\d+)p(\\d+)?", RegexOption.IGNORE_CASE)
    }
}

/** Thread-safe quality intent shared by the player service and its playback-thread selections. */
class SmoothHlsQualityPolicy {
    private val desired = AtomicReference(DesiredHlsQuality(BasePlaybackService.AUTO_QUALITY))

    fun set(name: String?, bitrate: Int? = null, codecs: String? = null) {
        desired.set(
            DesiredHlsQuality(
                name = name?.takeIf { it.isNotBlank() } ?: BasePlaybackService.AUTO_QUALITY,
                bitrate = bitrate,
                codecs = codecs,
            ),
        )
    }

    fun snapshot(): DesiredHlsQuality = desired.get()
}

/**
 * Keeps Media3's adaptive selection object installed while allowing Xtra to choose the next
 * rendition. Retaining that object lets HLS keep consuming buffered chunks across a quality
 * request instead of treating it as a new primary track selection and seeking the sample queue.
 */
class SmoothHlsTrackSelectionFactory(
    private val qualityPolicy: SmoothHlsQualityPolicy,
) : ExoTrackSelection.Factory {
    private val adaptiveFactory = AdaptiveTrackSelection.Factory()

    override fun createTrackSelections(
        definitions: Array<ExoTrackSelection.Definition?>,
        bandwidthMeter: BandwidthMeter,
        mediaPeriodId: MediaSource.MediaPeriodId,
        timeline: Timeline,
    ): Array<ExoTrackSelection?> {
        val selections = adaptiveFactory.createTrackSelections(
            definitions,
            bandwidthMeter,
            mediaPeriodId,
            timeline,
        )
        return selections.mapIndexed { index, selection ->
            val definition = definitions[index]
            if (selection is AdaptiveTrackSelection && definition?.let(::containsVideoFormat) == true) {
                SmoothHlsTrackSelection(selection, qualityPolicy)
            } else {
                selection
            }
        }.toTypedArray()
    }

    private fun containsVideoFormat(definition: ExoTrackSelection.Definition): Boolean =
        definition.tracks.any { definition.group.getFormat(it).height > 0 }
}

private class SmoothHlsTrackSelection(
    private val adaptiveSelection: AdaptiveTrackSelection,
    private val qualityPolicy: SmoothHlsQualityPolicy,
) : ForwardingTrackSelection(adaptiveSelection) {
    private var effectiveIndex = adaptiveSelection.selectedIndex
    private var effectiveReason = adaptiveSelection.selectionReason
    private var lastLoggedDesired: DesiredHlsQuality? = null

    override fun updateSelectedTrack(
        playbackPositionUs: Long,
        bufferedDurationUs: Long,
        availableDurationUs: Long,
        queue: List<MediaChunk>,
        mediaChunkIterators: Array<out MediaChunkIterator>,
    ) {
        adaptiveSelection.updateSelectedTrack(
            playbackPositionUs,
            bufferedDurationUs,
            availableDurationUs,
            queue,
            mediaChunkIterators,
        )

        val autoIndex = adaptiveSelection.selectedIndex
        val desired = qualityPolicy.snapshot()
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val manualIndex = if (desired.isAuto) null else {
            (0 until length())
                .filter { index -> !isTrackExcluded(index, nowMs) && desired.matches(getFormat(index)) }
                .maxWithOrNull(
                    compareBy<Int> { getFormat(it).height }
                        .thenBy { getFormat(it).frameRate }
                        .thenBy { getFormat(it).bitrate },
                )
        }

        effectiveIndex = manualIndex ?: autoIndex
        effectiveReason = if (manualIndex != null) C.SELECTION_REASON_MANUAL
        else adaptiveSelection.selectionReason

        val desiredChanged = desired != lastLoggedDesired
        lastLoggedDesired = desired
        if (desiredChanged && !desired.isAuto && (BuildConfig.DEBUG || BuildConfig.PERF_DIAGNOSTICS)) {
            val candidates = (0 until length()).joinToString(prefix = "[", postfix = "]") { index ->
                val format = getFormat(index)
                val excluded = isTrackExcluded(index, nowMs)
                "${format.label ?: "?"}:${format.width}x${format.height}@${format.frameRate}fps/" +
                    "${format.bitrate}bps/${format.codecs ?: "?"}/excluded=$excluded"
            }
            val requestedMatches = (0 until length()).any { desired.matches(getFormat(it)) }
            val decision = when {
                manualIndex != null -> "manual"
                requestedMatches -> "adaptive-fallback-all-matches-excluded"
                else -> "adaptive-fallback-no-match"
            }
            Log.i(
                QUALITY_SELECTION_TAG,
                "desired=${desired.name} codecs=${desired.codecs ?: "?"} " +
                    "bitrate=${desired.bitrate ?: -1} candidates=$candidates " +
                    "auto=${describeFormat(getFormat(autoIndex))} " +
                    "manual=${manualIndex?.let { describeFormat(getFormat(it)) } ?: "none"} " +
                    "effective=${describeFormat(getFormat(effectiveIndex))} decision=$decision",
            )
        }
    }

    override fun getSelectedIndex(): Int = effectiveIndex

    override fun getSelectedIndexInTrackGroup(): Int = getIndexInTrackGroup(effectiveIndex)

    override fun getSelectedFormat(): Format = getFormat(effectiveIndex)

    override fun getSelectionReason(): Int = effectiveReason

    override fun getSelectionData(): Any? = adaptiveSelection.selectionData

    override fun evaluateQueueSize(
        playbackPositionUs: Long,
        queue: List<MediaChunk>,
    ): Int = queue.size

    private fun describeFormat(format: Format): String =
        "${format.label ?: "?"}:${format.width}x${format.height}@${format.frameRate}fps/" +
            "${format.bitrate}bps/${format.codecs ?: "?"}"

    private companion object {
        const val QUALITY_SELECTION_TAG = "SmoothHlsQuality"
    }
}
