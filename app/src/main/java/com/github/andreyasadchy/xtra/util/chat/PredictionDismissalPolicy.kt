package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.model.chat.Prediction

/**
 * Decides when a final prediction result stops being shown.
 *
 * Final results stay visible for the viewer-configured display duration so
 * viewers can see the outcome, then the presentation is cleared. The delay
 * is anchored to [Prediction.endedAt] so a duplicate final event cannot extend
 * the display window; a final whose grace period already elapsed clears
 * immediately. A [PredictionState.RESULT_DISPLAY_NEVER_MILLIS] duration
 * disables automatic dismissal entirely.
 */
internal object PredictionDismissalPolicy {
    /**
     * Parses the raw `chat_predictions_result_duration` preference value
     * (seconds, or `"never"`) into milliseconds. Unknown values fall back to
     * [PredictionState.RESULT_DISPLAY_GRACE_MILLIS].
     */
    fun graceMillis(raw: String?): Long {
        if (raw?.trim().equals("never", ignoreCase = true)) {
            return PredictionState.RESULT_DISPLAY_NEVER_MILLIS
        }
        return raw?.trim()?.toLongOrNull()
            ?.takeIf { it in 1..86_400 }
            ?.let { it * 1_000L }
            ?: PredictionState.RESULT_DISPLAY_GRACE_MILLIS
    }

    /**
     * Returns the bounded freshness window used when loading a result into a
     * new session. "Never" only disables dismissal after presentation; it does
     * not make historical results eligible forever.
     */
    fun eligibilityMillis(displayDurationMillis: Long): Long =
        displayDurationMillis.takeUnless(::isNever) ?: PredictionState.RESULT_DISPLAY_GRACE_MILLIS

    fun isNever(graceMillis: Long): Boolean =
        graceMillis == PredictionState.RESULT_DISPLAY_NEVER_MILLIS

    /**
     * Remaining display time for a final result, or `null` when automatic
     * dismissal is disabled.
     */
    fun dismissalDelayMillis(
        endedAt: Long?,
        now: Long = System.currentTimeMillis(),
        graceMillis: Long = PredictionState.RESULT_DISPLAY_GRACE_MILLIS,
    ): Long? {
        if (isNever(graceMillis)) return null
        return if (endedAt != null) {
            (graceMillis - (now - endedAt)).coerceAtLeast(0L)
        } else {
            graceMillis
        }
    }

    fun shouldDismiss(current: Prediction?, predictionId: String?): Boolean =
        !predictionId.isNullOrBlank() &&
            current?.id == predictionId &&
            PredictionState.isFinal(current)
}
