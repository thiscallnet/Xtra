package com.github.andreyasadchy.xtra.ui.player.clip

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist

/**
 * Keeps a small rolling journal of complete HLS segment metadata.
 *
 * The manager never opens a URL and never writes a file. A generation is advanced whenever the
 * service starts a new live media source, which prevents concatenating stream replacements or ad
 * avoidance sources with the old timeline.
 */
@OptIn(UnstableApi::class)
class LiveClipBufferManager(
    retentionUs: Long = DEFAULT_RETENTION_US,
) {
    private val lock = Any()
    private val history = mutableListOf<ClipSegmentRef>()
    private var retentionUs = retentionUs.coerceAtLeast(MIN_CLIP_BUFFER_US)
    private var generation = 0L
    private var renditionId: String? = null

    /** Changes how far back the rolling segment journal keeps data. */
    fun setRetentionUs(value: Long) = synchronized(lock) {
        retentionUs = value.coerceAtLeast(MIN_CLIP_BUFFER_US)
        trimHistory()
    }

    fun startNewGeneration(): Long = synchronized(lock) {
        generation += 1L
        history.clear()
        renditionId = null
        generation
    }

    fun reset() = synchronized(lock) {
        generation += 1L
        history.clear()
        renditionId = null
    }

    fun currentGeneration(): Long = synchronized(lock) { generation }

    /** Captures all complete segments present in the latest parsed Media3 manifest. */
    fun capture(manifest: HlsManifest, expectedGeneration: Long = currentGeneration()): Boolean {
        val playlist = manifest.mediaPlaylist
        // The signed query string can change while the underlying rendition stays the same.
        // Treat only the playlist path as rendition identity; source changes still advance the
        // generation in PlaybackService.
        val currentRenditionId = playlist.baseUri
            .substringBefore('#')
            .substringBefore('?')
        synchronized(lock) {
            if (expectedGeneration != generation || playlist.segments.isEmpty()) {
                return false
            }
            if (renditionId != currentRenditionId) {
                history.clear()
                renditionId = currentRenditionId
            }

            val existingSequences = history.asSequence()
                .filter { it.generation == generation && it.renditionId == currentRenditionId }
                .map { it.mediaSequence }
                .toHashSet()
            playlist.segments.forEachIndexed { index, segment ->
                val mediaSequence = playlist.mediaSequence + index
                if (mediaSequence in existingSequences || segment.durationUs <= 0L) {
                    return@forEachIndexed
                }
                history += segment.toRef(
                    playlist = playlist,
                    generation = generation,
                    renditionId = currentRenditionId,
                    mediaSequence = mediaSequence,
                )
            }
            history.sortBy { it.mediaSequence }
            trimHistory()
            return true
        }
    }

    /**
     * Freezes the newest contiguous run, walking backwards from the latest non-gap segment.
     * The returned range may be shorter than the requested window when the stream just started
     * or a source/rendition/discontinuity boundary was recently crossed.
     */
    fun snapshot(maxDurationUs: Long = DEFAULT_CLIP_DURATION_US): ClipSnapshot? = synchronized(lock) {
        val currentRenditionId = renditionId ?: return@synchronized null
        val candidates = history.filter {
            it.generation == generation && it.renditionId == currentRenditionId
        }
        if (candidates.isEmpty()) return@synchronized null

        var latestIndex = candidates.lastIndex
        while (latestIndex >= 0 && candidates[latestIndex].hasGap) {
            latestIndex -= 1
        }
        if (latestIndex < 0) return@synchronized null

        val selected = selectContiguousLatest(candidates.subList(0, latestIndex + 1), maxDurationUs)
            ?: return@synchronized null
        if (selected.isEmpty()) return@synchronized null
        ClipSnapshot(generation, currentRenditionId, selected.toList())
    }

    fun status(maxDurationUs: Long = DEFAULT_CLIP_DURATION_US): Status = synchronized(lock) {
        val currentRenditionId = renditionId
        val segments = history.filter {
            it.generation == generation && it.renditionId == currentRenditionId
        }
        statusFor(segments, maxDurationUs)
    }

    internal fun statusFor(
        segments: List<ClipSegmentRef>,
        maxDurationUs: Long = DEFAULT_CLIP_DURATION_US,
    ): Status {
        val readySegments = selectContiguousLatest(segments, maxDurationUs).orEmpty()
        val readyDurationUs = readySegments.sumOf { it.durationUs }
        val drm = readySegments.any { it.drmInitDataPresent }
        return Status(
            durationUs = readyDurationUs,
            segmentCount = readySegments.size,
            drmProtected = drm,
            available = readyDurationUs >= MIN_CLIP_BUFFER_US && !drm,
        )
    }

    data class Status(
        val durationUs: Long,
        val segmentCount: Int,
        val drmProtected: Boolean,
        val available: Boolean,
    )

    private fun HlsMediaPlaylist.Segment.toRef(
        playlist: HlsMediaPlaylist,
        generation: Long,
        renditionId: String,
        mediaSequence: Long,
    ): ClipSegmentRef {
        val absoluteStartUs = if (playlist.startTimeUs != C.TIME_UNSET) {
            playlist.startTimeUs + relativeStartTimeUs
        } else {
            relativeStartTimeUs
        }
        val init = initializationSegment?.let {
            ClipResourceRef(
                uri = UriUtil.resolve(playlist.baseUri, it.url),
                byteRangeOffset = it.byteRangeOffset,
                byteRangeLength = it.byteRangeLength,
            )
        }
        return ClipSegmentRef(
            generation = generation,
            renditionId = renditionId,
            mediaSequence = mediaSequence,
            absoluteUri = UriUtil.resolve(playlist.baseUri, url),
            durationUs = durationUs,
            absoluteStartUs = absoluteStartUs,
            relativeStartUs = relativeStartTimeUs,
            discontinuitySequence = playlist.discontinuitySequence + relativeDiscontinuitySequence,
            byteRangeOffset = byteRangeOffset,
            byteRangeLength = byteRangeLength,
            initSegment = init,
            encryptionKeyUri = fullSegmentEncryptionKeyUri?.let { UriUtil.resolve(playlist.baseUri, it) },
            encryptionIv = encryptionIV,
            drmInitDataPresent = drmInitData != null || playlist.protectionSchemes != null,
            hasGap = hasGapTag,
        )
    }

    companion object {
        const val MIN_CLIP_DURATION_SECONDS = 10
        const val DEFAULT_CLIP_DURATION_SECONDS = 30
        const val MAX_CLIP_DURATION_SECONDS = 120
        const val MIN_CLIP_BUFFER_US = MIN_CLIP_DURATION_SECONDS * 1_000_000L
        const val DEFAULT_CLIP_DURATION_US = DEFAULT_CLIP_DURATION_SECONDS * 1_000_000L
        const val RETENTION_MARGIN_US = 15_000_000L
        const val DEFAULT_RETENTION_US = MAX_CLIP_DURATION_SECONDS * 1_000_000L + RETENTION_MARGIN_US

        /** Selects complete, contiguous segments ending at the latest usable segment. */
        internal fun selectContiguousLatest(
            candidates: List<ClipSegmentRef>,
            maxDurationUs: Long,
        ): List<ClipSegmentRef>? {
            if (candidates.isEmpty()) return null
            val latest = candidates.lastOrNull { !it.hasGap } ?: return null
            val latestIndex = candidates.indexOf(latest)
            val selected = mutableListOf<ClipSegmentRef>()
            var durationUs = 0L
            var expectedSequence = latest.mediaSequence
            val continuity = latest.discontinuitySequence
            for (index in latestIndex downTo 0) {
                val segment = candidates[index]
                if (segment.hasGap ||
                    segment.discontinuitySequence != continuity ||
                    segment.mediaSequence != expectedSequence
                ) {
                    break
                }
                if (selected.isNotEmpty() && durationUs + segment.durationUs > maxDurationUs) {
                    break
                }
                selected.add(0, segment)
                durationUs += segment.durationUs
                expectedSequence--
                if (durationUs >= maxDurationUs) break
            }
            return selected.takeIf { it.isNotEmpty() }
        }
    }

    private fun trimHistory() {
        while (history.sumOf { it.durationUs } > retentionUs && history.size > 1) {
            history.removeAt(0)
        }
    }
}
