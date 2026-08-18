package com.github.andreyasadchy.xtra.ui.player.clip

import androidx.media3.common.C

/** A media resource that can be materialized without retaining its bytes in memory. */
data class ClipResourceRef(
    val uri: String,
    val byteRangeOffset: Long = 0L,
    val byteRangeLength: Long = C.LENGTH_UNSET.toLong(),
)

/**
 * The metadata needed to download and reproduce one complete HLS media segment.
 *
 * This object deliberately contains no media data. The live journal can therefore remain
 * enabled for the whole playback session without causing extra network traffic or disk writes.
 */
data class ClipSegmentRef(
    val generation: Long,
    val renditionId: String,
    val mediaSequence: Long,
    val absoluteUri: String,
    val durationUs: Long,
    val absoluteStartUs: Long,
    val relativeStartUs: Long,
    val discontinuitySequence: Int,
    val byteRangeOffset: Long,
    val byteRangeLength: Long,
    val initSegment: ClipResourceRef?,
    val encryptionKeyUri: String?,
    val encryptionIv: String?,
    val drmInitDataPresent: Boolean,
    val hasGap: Boolean,
) {
    val resource: ClipResourceRef
        get() = ClipResourceRef(absoluteUri, byteRangeOffset, byteRangeLength)
}
