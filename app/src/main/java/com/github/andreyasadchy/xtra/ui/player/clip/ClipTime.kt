package com.github.andreyasadchy.xtra.ui.player.clip

import java.util.Locale

internal object ClipTime {
    fun parseMs(text: CharSequence): Long? {
        val parts = text.toString().trim().split(':', limit = 3).reversed()
        val seconds = parts.getOrNull(0)
            ?.toLongOrNull()
            ?.takeIf { it in 0L..59L }
            ?: return null
        val minutes = parts.getOrNull(1)?.let {
            it.toLongOrNull()?.takeIf { value -> value in 0L..59L } ?: return null
        } ?: 0L
        val hours = parts.getOrNull(2)?.let {
            it.toLongOrNull()?.takeIf { value -> value >= 0L } ?: return null
        } ?: 0L
        return runCatching {
            Math.addExact(
                Math.addExact(
                    Math.multiplyExact(hours, 3_600_000L),
                    Math.multiplyExact(minutes, 60_000L),
                ),
                Math.multiplyExact(seconds, 1_000L),
            )
        }.getOrNull()
    }

    fun formatMs(milliseconds: Long): String {
        val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
