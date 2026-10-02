package com.github.andreyasadchy.xtra.ui.player

import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.ui.common.diagnosticToken

internal data class StreamQualityCatalogIdentity(
    val mediaId: String,
    val sourceUri: String,
    val isPrimary: Boolean,
    val catalogUri: String?,
)

internal fun qualityCatalogRowsToken(qualities: List<VideoQuality>?): String {
    if (qualities.isNullOrEmpty()) return "none"
    val rows = qualities.joinToString("\u0000") { quality ->
        listOf(
            quality.name.orEmpty(),
            quality.codecs.orEmpty(),
            quality.bitrate?.toString() ?: "none",
            quality.frameRate?.toString() ?: "none",
            diagnosticToken(quality.url),
        ).joinToString("\u0001")
    }
    return diagnosticToken(rows)
}
