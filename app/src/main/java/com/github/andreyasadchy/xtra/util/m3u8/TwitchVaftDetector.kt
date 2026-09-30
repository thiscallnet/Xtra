package com.github.andreyasadchy.xtra.util.m3u8

import androidx.media3.common.C
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import kotlin.time.Instant

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
            isTwitchVaftDateRange(
                        id = interstitial.id,
                        rangeClass = interstitial.clientDefinedAttributes
                            .firstOrNull { it.name == "CLASS" }
                            ?.textValue,
                        hasVaftAttribute = interstitial.clientDefinedAttributes.any {
                            it.name.startsWith("X-TV-TWITCH-AD-")
                        },
                    )
                    && isActiveRange(segmentStartTime, startTime.takeIf { it != C.TIME_UNSET }, endTime)
        }
    }

    /** Returns the remaining duration of the active marked window when declared. */
    fun activeVaftRangeRemainingMs(playlist: HlsMediaPlaylist): Long? {
        val segment = playlist.segments.lastOrNull() ?: return null
        val segmentStartTime = playlist.startTimeUs + segment.relativeStartTimeUs
        return playlist.interstitials.asSequence()
            .filter { interstitial ->
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

}
