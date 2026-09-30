package com.github.andreyasadchy.xtra.util.m3u8

import androidx.media3.common.C
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
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

}
