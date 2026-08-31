package com.github.andreyasadchy.xtra.ui.player.captions

import java.util.Locale
import kotlin.math.roundToInt

internal const val DEFAULT_CAPTION_TEXT_OFFSET_MS = 0
internal const val MAX_CAPTION_TEXT_OFFSET_MS = 2_000

internal fun parseCaptionTextOffsetMs(value: String?): Int {
    val seconds = value?.trim()?.toDoubleOrNull() ?: return DEFAULT_CAPTION_TEXT_OFFSET_MS
    if (!seconds.isFinite()) return DEFAULT_CAPTION_TEXT_OFFSET_MS
    return (seconds * 1_000.0)
        .roundToInt()
        .coerceIn(-MAX_CAPTION_TEXT_OFFSET_MS, MAX_CAPTION_TEXT_OFFSET_MS)
}

internal fun formatCaptionTextOffset(value: String?): String =
    String.format(
        Locale.US,
        "%.2f s",
        parseCaptionTextOffsetMs(value) / 1_000.0,
    )
