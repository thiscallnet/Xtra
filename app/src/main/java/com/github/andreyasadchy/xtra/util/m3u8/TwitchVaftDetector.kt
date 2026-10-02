package com.github.andreyasadchy.xtra.util.m3u8

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import com.github.andreyasadchy.xtra.BuildConfig
import kotlin.time.Instant

data class VaftBoundaryObservation(
    val basis: String,
    val markerKey: String,
    val relativeStartTimeUs: Long?,
    val epochStartTimeUs: Long?,
    val relativeEndTimeUs: Long? = null,
    val epochEndTimeUs: Long? = null,
    val sourceStartTimeUs: Long? = null,
    val sourceEndTimeUs: Long? = null,
    val sourceMediaSequenceStart: Long? = null,
    val sourceMediaSequenceEnd: Long? = null,
)

/**
 * Detects the VAFT markers Twitch currently exposes in live HLS playlists.
 *
 * Keep this separate from the player so all playback implementations make the
 * same decision when a playlist rolls over to a VAFT window.
 */
@androidx.media3.common.util.UnstableApi
object TwitchVaftDetector {

    private val vaftTitleMarkers = listOf("Amazon", "Adform", "DCM")

    fun requiresVaft(playlist: HlsMediaPlaylist): Boolean {
        val segment = playlist.segments.lastOrNull() ?: return false
        val segmentStartTime = playlist.startTimeUs + segment.relativeStartTimeUs
        return isVaftTitle(segment.title)
                || playlist.interstitials.any { interstitial ->
            val startTime = interstitial.startDateUnixUs
            val endTime = interstitial.endDateUnixUs.takeIf { it != C.TIME_UNSET }
                ?: interstitial.durationUs.takeIf { it != C.TIME_UNSET }?.let { startTime + it }
                ?: interstitial.plannedDurationUs.takeIf { it != C.TIME_UNSET }?.let { startTime + it }
            isTwitchVaftDateRange(interstitial)
                    && isActiveRange(segmentStartTime, startTime.takeIf { it != C.TIME_UNSET }, endTime)
        }
    }

    /** Returns every source-time boundary still represented by this playlist. */
    fun visibleBoundaries(playlist: HlsMediaPlaylist): List<VaftBoundaryObservation> {
        val hasEpoch = playlist.hasProgramDateTime && playlist.startTimeUs != C.TIME_UNSET
        val ranges = playlist.interstitials.mapNotNull { interstitial ->
            if (!isTwitchVaftDateRange(interstitial)) return@mapNotNull null
            val startUs = interstitial.startDateUnixUs.takeIf { it != C.TIME_UNSET }
                ?: return@mapNotNull null
            val endUs = interstitial.endDateUnixUs.takeIf { it != C.TIME_UNSET }
                ?: interstitial.durationUs.takeIf { it != C.TIME_UNSET }?.let { startUs + it }
                ?: interstitial.plannedDurationUs.takeIf { it != C.TIME_UNSET }?.let { startUs + it }
            VaftBoundaryObservation(
                basis = "date_range",
                markerKey = "range:${interstitial.id}",
                relativeStartTimeUs = if (hasEpoch) startUs - playlist.startTimeUs else null,
                epochStartTimeUs = startUs,
                relativeEndTimeUs = endUs?.takeIf { hasEpoch }?.minus(playlist.startTimeUs),
                epochEndTimeUs = endUs,
                sourceStartTimeUs = startUs.takeIf { hasEpoch },
                sourceEndTimeUs = endUs?.takeIf { hasEpoch },
            )
        }
        val segments = playlist.segments.mapIndexedNotNull { index, segment ->
            if (!isVaftTitle(segment.title)) return@mapIndexedNotNull null
            val startUs = segment.relativeStartTimeUs
            val endUs = startUs + segment.durationUs
            VaftBoundaryObservation(
                basis = "segment_title",
                markerKey = "segment:${playlist.mediaSequence + index}",
                relativeStartTimeUs = startUs,
                epochStartTimeUs = if (hasEpoch) playlist.startTimeUs + startUs else null,
                relativeEndTimeUs = endUs,
                epochEndTimeUs = if (hasEpoch) playlist.startTimeUs + endUs else null,
                sourceStartTimeUs = playlist.startTimeUs
                    .takeIf { it != C.TIME_UNSET }
                    ?.plus(startUs),
                sourceEndTimeUs = playlist.startTimeUs
                    .takeIf { it != C.TIME_UNSET }
                    ?.plus(endUs),
                sourceMediaSequenceStart = playlist.mediaSequence + index,
                sourceMediaSequenceEnd = playlist.mediaSequence + index + 1,
            )
        }
        return (ranges + segments).sortedWith(
            compareBy<VaftBoundaryObservation> { it.epochStartTimeUs ?: Long.MAX_VALUE }
                .thenBy { it.relativeStartTimeUs ?: Long.MAX_VALUE }
                .thenBy { if (it.basis == "date_range") 0 else 1 },
        )
    }

    /** Finds a marked boundary visible anywhere in this playlist for diagnostics. */
    fun firstVisibleBoundary(
        playlist: HlsMediaPlaylist,
        afterPositionUs: Long? = null,
    ): VaftBoundaryObservation? {
        val segmentBoundary = playlist.segments.mapIndexedNotNull { index, segment ->
            if (!isVaftTitle(segment.title)) return@mapIndexedNotNull null
            val relativeStartTimeUs = segment.relativeStartTimeUs
            VaftBoundaryObservation(
                basis = "segment_title",
                markerKey = "segment:${playlist.mediaSequence + index}",
                relativeStartTimeUs = relativeStartTimeUs,
                epochStartTimeUs = if (playlist.hasProgramDateTime && playlist.startTimeUs != C.TIME_UNSET) {
                    playlist.startTimeUs + relativeStartTimeUs
                } else {
                    null
                },
                sourceStartTimeUs = playlist.startTimeUs
                    .takeIf { it != C.TIME_UNSET }
                    ?.plus(relativeStartTimeUs),
                sourceMediaSequenceStart = playlist.mediaSequence + index,
                sourceMediaSequenceEnd = playlist.mediaSequence + index + 1,
            )
        }

        val rangeBoundaries = playlist.interstitials.mapNotNull { interstitial ->
            val isVaftRange = isTwitchVaftDateRange(interstitial)
            val epochStartTimeUs = interstitial.startDateUnixUs.takeIf { it != C.TIME_UNSET }
            if (!isVaftRange || epochStartTimeUs == null) return@mapNotNull null
            val epochEndTimeUs = interstitial.endDateUnixUs.takeIf { it != C.TIME_UNSET }
                ?: interstitial.durationUs.takeIf { it != C.TIME_UNSET }?.let { epochStartTimeUs + it }
                ?: interstitial.plannedDurationUs.takeIf { it != C.TIME_UNSET }?.let { epochStartTimeUs + it }
            val hasEpoch = playlist.hasProgramDateTime && playlist.startTimeUs != C.TIME_UNSET
            VaftBoundaryObservation(
                basis = "date_range",
                markerKey = "range:${interstitial.id}",
                relativeStartTimeUs = if (hasEpoch) {
                    epochStartTimeUs - playlist.startTimeUs
                } else {
                    null
                },
                epochStartTimeUs = epochStartTimeUs,
                relativeEndTimeUs = epochEndTimeUs?.takeIf { hasEpoch }?.minus(playlist.startTimeUs),
                epochEndTimeUs = epochEndTimeUs,
                sourceStartTimeUs = epochStartTimeUs.takeIf { hasEpoch },
                sourceEndTimeUs = epochEndTimeUs?.takeIf { hasEpoch },
            )
        }

        val boundaries = segmentBoundary + rangeBoundaries
        if (afterPositionUs != null) {
            return boundaries
                .filter { it.relativeStartTimeUs != null && it.relativeStartTimeUs > afterPositionUs }
                .minByOrNull { it.relativeStartTimeUs!! }
        }
        return boundaries
            .filter { it.relativeStartTimeUs != null }
            .minByOrNull { it.relativeStartTimeUs!! }
            ?: boundaries.firstOrNull { it.epochStartTimeUs != null }
    }

    /** Identifies the earliest marker currently inside its declared VAFT window. */
    fun activeBoundary(playlist: HlsMediaPlaylist): VaftBoundaryObservation? {
        val segment = playlist.segments.lastOrNull() ?: return null
        val segmentStartTimeUs = playlist.startTimeUs + segment.relativeStartTimeUs
        val activeRange = playlist.interstitials.mapNotNull { interstitial ->
            val startTimeUs = interstitial.startDateUnixUs.takeIf { it != C.TIME_UNSET } ?: return@mapNotNull null
            val endTimeUs = interstitial.endDateUnixUs.takeIf { it != C.TIME_UNSET }
                ?: interstitial.durationUs.takeIf { it != C.TIME_UNSET }?.let { startTimeUs + it }
                ?: interstitial.plannedDurationUs.takeIf { it != C.TIME_UNSET }?.let { startTimeUs + it }
            if (!isTwitchVaftDateRange(interstitial) ||
                !isActiveRange(segmentStartTimeUs, startTimeUs, endTimeUs)
            ) return@mapNotNull null
            VaftBoundaryObservation(
                basis = "date_range",
                markerKey = "range:${interstitial.id}",
                relativeStartTimeUs = if (playlist.hasProgramDateTime && playlist.startTimeUs != C.TIME_UNSET) {
                    startTimeUs - playlist.startTimeUs
                } else {
                    null
                },
                epochStartTimeUs = startTimeUs,
                epochEndTimeUs = endTimeUs,
                sourceStartTimeUs = startTimeUs.takeIf {
                    playlist.hasProgramDateTime && playlist.startTimeUs != C.TIME_UNSET
                },
                sourceEndTimeUs = endTimeUs?.takeIf {
                    playlist.hasProgramDateTime && playlist.startTimeUs != C.TIME_UNSET
                },
            )
        }.minByOrNull { it.epochStartTimeUs ?: Long.MAX_VALUE }
        if (activeRange != null) return activeRange
        if (!isVaftTitle(segment.title)) return null
        return VaftBoundaryObservation(
            basis = "segment_title",
            markerKey = "segment:${playlist.mediaSequence + playlist.segments.lastIndex}",
            relativeStartTimeUs = segment.relativeStartTimeUs,
            epochStartTimeUs = if (playlist.hasProgramDateTime && playlist.startTimeUs != C.TIME_UNSET) {
                playlist.startTimeUs + segment.relativeStartTimeUs
            } else {
                null
            },
            sourceStartTimeUs = playlist.startTimeUs
                .takeIf { it != C.TIME_UNSET }
                ?.plus(segment.relativeStartTimeUs),
            sourceEndTimeUs = playlist.startTimeUs
                .takeIf { it != C.TIME_UNSET }
                ?.plus(segment.relativeStartTimeUs + segment.durationUs),
            sourceMediaSequenceStart = playlist.mediaSequence + playlist.segments.lastIndex,
            sourceMediaSequenceEnd = playlist.mediaSequence + playlist.segments.size,
        )
    }

    /** Returns the remaining duration of the active marked window when declared. */
    fun activeVaftRangeRemainingMs(playlist: HlsMediaPlaylist): Long? {
        val segment = playlist.segments.lastOrNull() ?: return null
        val segmentStartTime = playlist.startTimeUs + segment.relativeStartTimeUs
        return playlist.interstitials.asSequence()
            .filter { interstitial ->
                isTwitchVaftDateRange(interstitial)
            }
            .mapNotNull { interstitial ->
                val startTime = interstitial.startDateUnixUs.takeIf { it != C.TIME_UNSET }
                    ?: return@mapNotNull null
                val endTime = interstitial.endDateUnixUs.takeIf { it != C.TIME_UNSET }
                    ?: interstitial.durationUs.takeIf { it != C.TIME_UNSET }?.let { startTime + it }
                    ?: interstitial.plannedDurationUs.takeIf { it != C.TIME_UNSET }?.let { startTime + it }
                    ?: return@mapNotNull null
                (endTime - segmentStartTime).takeIf { isActiveRange(segmentStartTime, startTime, endTime) }
            }
            .maxOrNull()
            ?.div(1_000L)
    }

    fun requiresVaft(playlist: MediaPlaylist): Boolean {
        val segment = playlist.segments.lastOrNull() ?: return false
        if (segment.title?.let(::isVaftTitle) == true) {
            return true
        }
        val segmentStartTime = segment.programDateTime
            ?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }
            ?: return false
        return playlist.dateRanges.any { dateRange ->
            if (!isTwitchVaftDateRange(dateRange.id, dateRange.rangeClass, dateRange.vaftMarker)) {
                return@any false
            }
            val startTime = Instant.parseOrNull(dateRange.startDate)?.toEpochMilliseconds()
                ?: return@any false
            val endTime = dateRange.endDate
                ?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }
                ?: dateRange.duration?.let { startTime + (it * 1000f).toLong() }
                ?: dateRange.plannedDuration?.let { startTime + (it * 1000f).toLong() }
            isActiveRange(segmentStartTime, startTime, endTime)
        }
    }

    /** Returns the remaining duration of the active marked window when declared. */
    fun activeVaftRangeRemainingMs(playlist: MediaPlaylist): Long? {
        val segment = playlist.segments.lastOrNull() ?: return null
        val segmentStartTime = segment.programDateTime
            ?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }
            ?: return null
        return playlist.dateRanges.asSequence()
            .filter { isTwitchVaftDateRange(it.id, it.rangeClass, it.vaftMarker) }
            .mapNotNull { dateRange ->
                val startTime = Instant.parseOrNull(dateRange.startDate)?.toEpochMilliseconds()
                    ?: return@mapNotNull null
                val endTime = dateRange.endDate
                    ?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }
                    ?: dateRange.duration?.let { startTime + (it * 1000f).toLong() }
                    ?: dateRange.plannedDuration?.let { startTime + (it * 1000f).toLong() }
                    ?: return@mapNotNull null
                (endTime - segmentStartTime).takeIf { isActiveRange(segmentStartTime, startTime, endTime) }
            }
            .maxOrNull()
    }

    internal fun isActiveRange(segmentStart: Long, start: Long?, end: Long?): Boolean =
        start != null && segmentStart >= start && (end == null || segmentStart < end)

    internal fun isVaftTitle(title: String): Boolean =
        vaftTitleMarkers.any { title.contains(it, ignoreCase = true) }

    internal fun isTwitchVaftDateRange(
        id: String,
        rangeClass: String?,
        hasVaftAttribute: Boolean = false,
    // Bare twitch-trigger ranges also occur on normal live segments. Only their
    // explicit VAFT attributes (or another VAFT marker) identify a VAFT window.
    ): Boolean = hasVaftAttribute ||
        id.startsWith("stitched-ad", ignoreCase = true) ||
        rangeClass?.startsWith("twitch-stitched", ignoreCase = true) == true ||
        rangeClass.equals("twitch-maf-ad", ignoreCase = true)

    private fun isTwitchVaftDateRange(interstitial: HlsMediaPlaylist.Interstitial): Boolean =
        isTwitchVaftDateRange(
            id = interstitial.id,
            rangeClass = interstitial.clientDefinedAttributes
                .firstOrNull { it.name == "CLASS" }
                ?.textValue,
            hasVaftAttribute = interstitial.clientDefinedAttributes.any {
                it.name.startsWith("X-TV-TWITCH-AD-")
            },
        )

    internal fun isTwitchVaftDateRangeForEvidence(interstitial: HlsMediaPlaylist.Interstitial): Boolean =
        isTwitchVaftDateRange(interstitial)

}

internal enum class VaftSegmentEvidenceKind {
    STITCHED,
    LIVE,
    OTHER_NON_LIVE,
    UNKNOWN,
}

internal data class VaftEvidenceRange(
    val key: String,
    val basis: String,
    val startTimeUs: Long,
    val endTimeUs: Long?,
)

internal object VaftSegmentEvidenceRecorder {

    private data class Counts(
        var stitched: Int = 0,
        var live: Int = 0,
        var otherNonLive: Int = 0,
        var unknown: Int = 0,
    ) {
        val total: Int get() = stitched + live + otherNonLive + unknown

        fun add(kind: VaftSegmentEvidenceKind) {
            when (kind) {
                VaftSegmentEvidenceKind.STITCHED -> stitched++
                VaftSegmentEvidenceKind.LIVE -> live++
                VaftSegmentEvidenceKind.OTHER_NON_LIVE -> otherNonLive++
                VaftSegmentEvidenceKind.UNKNOWN -> unknown++
            }
        }

        fun summary(): String = when {
            total == 0 || unknown == total -> "unknown"
            stitched == total -> "stitched_only"
            live == total -> "live_only"
            otherNonLive == total -> "other_non_live_only"
            else -> "mixed"
        }
    }

    private data class RangeCounts(
        val basis: String,
        val seenSegments: MutableSet<SeenSegment> = HashSet(),
        val counts: Counts = Counts(),
        var missingRefreshes: Int = 0,
    )

    private data class SeenSegment(
        val sourceIdentity: String,
        val sequence: Long,
    )

    private val ranges = LinkedHashMap<String, RangeCounts>()
    private val controlSegments = HashSet<Long>()
    private val controlCounts = Counts()
    private var controlStartedAtMs = 0L
    private var controlSourceIdentity: String? = null
    private var controlPreviousFirstSequence: Long? = null

    @Synchronized
    fun resetSession() {
        if (!BuildConfig.DEBUG) return
        ranges.clear()
        clearControl()
        controlSourceIdentity = null
    }

    @Synchronized
    fun record(playlist: HlsMediaPlaylist, sourceIdentity: String) {
        if (!BuildConfig.DEBUG) return

        if (controlSourceIdentity != sourceIdentity) {
            clearControl()
            controlSourceIdentity = sourceIdentity
        }

        val activeRanges = activeRanges(playlist)
        val activeKeys = activeRanges.mapTo(HashSet()) { it.key }
        val hasProgramDateTime = playlist.hasProgramDateTime && playlist.startTimeUs != C.TIME_UNSET &&
            playlist.segments.lastOrNull()?.relativeStartTimeUs != C.TIME_UNSET

        activeRanges.forEach { range ->
            val state = ranges[range.key] ?: run {
                RangeCounts(range.basis).also { ranges[range.key] = it }
            }
            state.missingRefreshes = 0
            playlist.segments.forEachIndexed { index, segment ->
                if (segment.relativeStartTimeUs == C.TIME_UNSET || segment.durationUs <= 0L) return@forEachIndexed
                val segmentStart = if (hasProgramDateTime) {
                    playlist.startTimeUs + segment.relativeStartTimeUs
                } else {
                    segment.relativeStartTimeUs
                }
                val segmentEnd = segmentStart + segment.durationUs
                if (segmentEnd <= range.startTimeUs || (range.endTimeUs != null && segmentStart >= range.endTimeUs)) {
                    return@forEachIndexed
                }
                val seenSegment = SeenSegment(sourceIdentity, playlist.mediaSequence + index)
                if (state.seenSegments.add(seenSegment)) {
                    state.counts.add(classify(segment.title))
                }
            }
        }

        val completed = ranges.entries.iterator()
        while (completed.hasNext()) {
            val entry = completed.next()
            if (entry.key in activeKeys) continue
            entry.value.missingRefreshes++
            if (entry.value.missingRefreshes >= 3) {
                emitRange(entry.value)
                completed.remove()
            }
        }
        while (ranges.size > 32) {
            val oldest = ranges.entries.iterator()
            if (!oldest.hasNext()) break
            emitRange(oldest.next().value)
            oldest.remove()
        }

        val currentSegmentMarked = playlist.segments.lastOrNull()?.title?.let(TwitchVaftDetector::isVaftTitle) == true
        val hasUncorrelatedRange = !hasProgramDateTime && playlist.interstitials.any {
            TwitchVaftDetector.isTwitchVaftDateRangeForEvidence(it)
        }
        if (playlist.segments.isNotEmpty() && activeRanges.isEmpty() && !currentSegmentMarked && !hasUncorrelatedRange) {
            recordControl(playlist)
        } else {
            emitControlIfReady(force = false)
            clearControl()
        }
    }

    private fun activeRanges(playlist: HlsMediaPlaylist): List<VaftEvidenceRange> {
        val last = playlist.segments.lastOrNull() ?: return emptyList()
        val hasProgramDateTime = playlist.hasProgramDateTime && playlist.startTimeUs != C.TIME_UNSET &&
            last.relativeStartTimeUs != C.TIME_UNSET
        val timelinePosition = if (hasProgramDateTime) playlist.startTimeUs + last.relativeStartTimeUs else C.TIME_UNSET
        val dateRanges = if (hasProgramDateTime) playlist.interstitials.mapNotNull { interstitial ->
            val start = interstitial.startDateUnixUs.takeIf { it != C.TIME_UNSET } ?: return@mapNotNull null
            val end = interstitial.endDateUnixUs.takeIf { it != C.TIME_UNSET }
                ?: interstitial.durationUs.takeIf { it != C.TIME_UNSET }?.let { start + it }
                ?: interstitial.plannedDurationUs.takeIf { it != C.TIME_UNSET }?.let { start + it }
            if (!TwitchVaftDetector.isTwitchVaftDateRangeForEvidence(interstitial) ||
                !TwitchVaftDetector.isActiveRange(timelinePosition, start, end)
            ) return@mapNotNull null
            VaftEvidenceRange(
                key = "range:${interstitial.id}:$start",
                basis = "date_range",
                startTimeUs = start,
                endTimeUs = end,
            )
        } else {
            emptyList()
        }
        val titleRange = if (last.relativeStartTimeUs != C.TIME_UNSET &&
            TwitchVaftDetector.isVaftTitle(last.title.orEmpty())
        ) {
            val start = if (hasProgramDateTime) {
                playlist.startTimeUs + last.relativeStartTimeUs
            } else {
                last.relativeStartTimeUs
            }
            val end = start + last.durationUs
            listOf(
                VaftEvidenceRange(
                    key = "segment:${playlist.mediaSequence + playlist.segments.lastIndex}:$start",
                    basis = "segment_title",
                    startTimeUs = start,
                    endTimeUs = end,
                ),
            )
        } else {
            emptyList()
        }
        return dateRanges + titleRange
    }

    private fun classify(title: String?): VaftSegmentEvidenceKind = when {
        title.isNullOrBlank() -> VaftSegmentEvidenceKind.UNKNOWN
        title.contains("stitched", ignoreCase = true) -> VaftSegmentEvidenceKind.STITCHED
        title.trim().equals("live", ignoreCase = true) -> VaftSegmentEvidenceKind.LIVE
        else -> VaftSegmentEvidenceKind.OTHER_NON_LIVE
    }

    private fun recordControl(playlist: HlsMediaPlaylist) {
        val now = SystemClock.elapsedRealtime()
        val firstSequence = playlist.mediaSequence
        if (controlPreviousFirstSequence?.let { firstSequence < it } == true) clearControl()
        controlPreviousFirstSequence = firstSequence
        controlSegments.removeAll { it < firstSequence }

        val hasProgramDateTime = playlist.hasProgramDateTime && playlist.startTimeUs != C.TIME_UNSET
        val cleanSegments = playlist.segments.mapIndexedNotNull { index, segment ->
            if (segment.relativeStartTimeUs == C.TIME_UNSET || segment.durationUs <= 0L) return@mapIndexedNotNull null
            val title = segment.title
            if (TwitchVaftDetector.isVaftTitle(title) || title.contains("stitched", ignoreCase = true)) {
                return@mapIndexedNotNull null
            }
            val segmentStart = if (hasProgramDateTime) {
                playlist.startTimeUs + segment.relativeStartTimeUs
            } else {
                segment.relativeStartTimeUs
            }
            val segmentEnd = segmentStart + segment.durationUs
            val overlapsKnownRange = hasProgramDateTime && playlist.interstitials.any { interstitial ->
                if (!TwitchVaftDetector.isTwitchVaftDateRangeForEvidence(interstitial)) return@any false
                val start = interstitial.startDateUnixUs.takeIf { it != C.TIME_UNSET } ?: return@any false
                val end = interstitial.endDateUnixUs.takeIf { it != C.TIME_UNSET }
                    ?: interstitial.durationUs.takeIf { it != C.TIME_UNSET }?.let { start + it }
                    ?: interstitial.plannedDurationUs.takeIf { it != C.TIME_UNSET }?.let { start + it }
                segmentEnd > start && (end == null || segmentStart < end)
            }
            if (overlapsKnownRange) return@mapIndexedNotNull null
            (playlist.mediaSequence + index) to segment
        }
        if (cleanSegments.isEmpty()) {
            emitControlIfReady(force = false)
            clearControl()
            return
        }
        if (controlStartedAtMs == 0L) controlStartedAtMs = now
        cleanSegments.forEach { (sequence, segment) ->
            if (controlSegments.add(sequence)) controlCounts.add(classify(segment.title))
        }
        if (now - controlStartedAtMs >= 60_000L) emitControlIfReady(force = true)
    }

    private fun emitControlIfReady(force: Boolean) {
        if (controlStartedAtMs == 0L || controlCounts.total == 0) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - controlStartedAtMs < 60_000L) return
        emitSummary("control", controlCounts, controlCounts.summary())
        controlCounts.stitched = 0
        controlCounts.live = 0
        controlCounts.otherNonLive = 0
        controlCounts.unknown = 0
        controlStartedAtMs = now
    }

    private fun clearControl() {
        controlSegments.clear()
        controlCounts.stitched = 0
        controlCounts.live = 0
        controlCounts.otherNonLive = 0
        controlCounts.unknown = 0
        controlStartedAtMs = 0L
        controlPreviousFirstSequence = null
    }

    private fun emitRange(state: RangeCounts) {
        emitSummary(
            sample = "range",
            counts = state.counts,
            evidence = state.counts.summary(),
            basis = state.basis,
        )
    }

    private fun emitSummary(
        sample: String,
        counts: Counts,
        evidence: String,
        basis: String? = null,
    ) {
        Log.d(
            "XtraVaftEvidence",
            "sample=$sample${basis?.let { " basis=$it" }.orEmpty()} " +
                "evidence=$evidence " +
                "segments=${counts.total} stitched=${counts.stitched} live=${counts.live} " +
                "otherNonLive=${counts.otherNonLive} unknown=${counts.unknown}",
        )
    }
}
