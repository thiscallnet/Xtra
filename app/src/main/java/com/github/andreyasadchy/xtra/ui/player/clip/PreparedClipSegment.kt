package com.github.andreyasadchy.xtra.ui.player.clip

/** Local names and timing metadata for one already materialized HLS segment. */
data class PreparedClipSegment(
    val mediaSequence: Long,
    val durationUs: Long,
    val discontinuitySequence: Int,
    val segmentFile: String,
    val initFile: String?,
    val keyFile: String?,
    val encryptionIv: String?,
)
