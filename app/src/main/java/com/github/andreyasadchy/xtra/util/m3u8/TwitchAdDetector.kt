package com.github.andreyasadchy.xtra.util.m3u8

import androidx.media3.common.C
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import kotlin.time.Instant

/**
 * Detects the ad markers Twitch currently exposes in live HLS playlists.
 *
 * Keep this separate from the player so all playback implementations make the
 * same decision when a playlist rolls over to an ad window.
 */
@androidx.media3.common.util.UnstableApi
object TwitchAdDetector {

    private val adTitleMarkers = listOf("Amazon", "Adform", "DCM")

    fun isAd(playlist: HlsMediaPlaylist): Boolean {
        val segment = playlist.segments.lastOrNull() ?: return false
        val segmentStartTime = playlist.startTimeUs + segment.relativeStartTimeUs
        return isAdTitle(segment.title)
                || playlist.interstitials.any { interstitial ->
            val startTime = interstitial.startDateUnixUs
            val endTime = interstitial.endDateUnixUs.takeIf { it != C.TIME_UNSET }
                ?: interstitial.durationUs.takeIf { it != C.TIME_UNSET }?.let { startTime + it }
                ?: interstitial.plannedDurationUs.takeIf { it != C.TIME_UNSET }?.let { startTime + it }
            isTwitchAdDateRange(
                        id = interstitial.id,
                        rangeClass = interstitial.clientDefinedAttributes
                            .firstOrNull { it.name == "CLASS" }
                            ?.textValue,
                        hasAdAttribute = interstitial.clientDefinedAttributes.any {
                            it.name.startsWith("X-TV-TWITCH-AD-")
                        },
                    )
                    && isActiveRange(segmentStartTime, startTime.takeIf { it != C.TIME_UNSET }, endTime)
        }
    }

    fun isAd(playlist: MediaPlaylist): Boolean {
        val segment = playlist.segments.lastOrNull() ?: return false
        if (segment.title?.let(::isAdTitle) == true) {
            return true
        }
        val segmentStartTime = segment.programDateTime
            ?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }
            ?: return false
        return playlist.dateRanges.any { dateRange ->
            if (!isTwitchAdDateRange(dateRange.id, dateRange.rangeClass, dateRange.ad)) {
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

    internal fun isActiveRange(segmentStart: Long, start: Long?, end: Long?): Boolean =
        start != null && segmentStart >= start && (end == null || segmentStart < end)

    internal fun isAdTitle(title: String): Boolean =
        adTitleMarkers.any { title.contains(it, ignoreCase = true) }

    internal fun isTwitchAdDateRange(
        id: String,
        rangeClass: String?,
        hasAdAttribute: Boolean = false,
    // Bare twitch-trigger ranges also occur on normal live segments. Only their
    // explicit ad attributes (or another ad marker) identify an ad window.
    ): Boolean = hasAdAttribute ||
        id.startsWith("stitched-ad", ignoreCase = true) ||
        rangeClass?.startsWith("twitch-stitched", ignoreCase = true) == true ||
        rangeClass.equals("twitch-maf-ad", ignoreCase = true)

}
