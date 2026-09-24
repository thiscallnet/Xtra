package com.github.andreyasadchy.xtra.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.chunk.MediaChunk
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.ForwardingTrackSelection
import androidx.media3.exoplayer.trackselection.TrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
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

    fun matches(format: Format): Boolean {
        val labelMatches = format.label.equals(name, ignoreCase = true)
        val bitrateMatches = bitrate == null || format.bitrate <= 0 || format.bitrate <= bitrate
        val codecsMatch = codecs.isNullOrBlank() || format.codecs.isNullOrBlank() ||
            format.codecs.equals(codecs, ignoreCase = true)
        val variantMatches = bitrateMatches && codecsMatch
        if (labelMatches && variantMatches) return true

        val (height, fps) = dimensions ?: return name.equals(BasePlaybackService.SOURCE_QUALITY, ignoreCase = true) &&
            variantMatches
        return format.height == height && floor(format.frameRate).toInt() <= fps && variantMatches
    }

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
}
