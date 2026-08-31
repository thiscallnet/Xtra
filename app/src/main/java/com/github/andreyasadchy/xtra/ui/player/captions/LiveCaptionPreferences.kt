package com.github.andreyasadchy.xtra.ui.player.captions

import android.content.SharedPreferences
import com.github.andreyasadchy.xtra.util.C

private const val DEFAULT_PARTIAL_INTERVAL_MS = 2_000
private const val MIN_PARTIAL_INTERVAL_MS = 200
private const val MAX_PARTIAL_INTERVAL_MS = 2_000

/**
 * ListPreference stores its value as a String, while older caption code wrote
 * an Int directly. Read both representations so an upgrade cannot crash ASR.
 */
internal fun SharedPreferences.liveCaptionPartialIntervalMs(): Int =
    parseLiveCaptionPartialInterval(all[C.PLAYER_LIVE_CAPTION_PARTIAL_INTERVAL_MS])

internal fun parseLiveCaptionPartialInterval(value: Any?): Int =
    when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: DEFAULT_PARTIAL_INTERVAL_MS
        else -> DEFAULT_PARTIAL_INTERVAL_MS
    }.coerceIn(MIN_PARTIAL_INTERVAL_MS, MAX_PARTIAL_INTERVAL_MS)
