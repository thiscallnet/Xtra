package com.github.andreyasadchy.xtra.ui.player.clip

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist

/** Converts Media3's complete HLS segment metadata into the clip model. */
@OptIn(UnstableApi::class)
internal object HlsClipSnapshotMapper {
    fun fromManifest(
        manifest: HlsManifest,
        generation: Long,
    ): ClipSnapshot {
        val playlist = manifest.mediaPlaylist
        val renditionId = playlist.baseUri
            .substringBefore('#')
            .substringBefore('?')
        val refs = playlist.segments.mapIndexedNotNull { index, segment ->
            if (segment.durationUs <= 0L) {
                null
            } else {
                segment.toClipSegmentRef(
                    playlist = playlist,
                    generation = generation,
                    renditionId = renditionId,
                    mediaSequence = playlist.mediaSequence + index,
                )
            }
        }
        return ClipSnapshot(
            generation = generation,
            renditionId = renditionId,
            segments = refs,
        )
    }

    private fun HlsMediaPlaylist.Segment.toClipSegmentRef(
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
            encryptionKeyUri = fullSegmentEncryptionKeyUri?.let {
                UriUtil.resolve(playlist.baseUri, it)
            },
            encryptionIv = encryptionIV,
            drmInitDataPresent = drmInitData != null || playlist.protectionSchemes != null,
            hasGap = hasGapTag,
        )
    }
}
