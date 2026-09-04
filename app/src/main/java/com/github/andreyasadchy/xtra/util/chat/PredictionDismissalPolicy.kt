package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.model.chat.Prediction

/**
 * Decides when a final prediction result stops being shown.
 *
 * Final results stay visible for [PredictionState.RESULT_DISPLAY_GRACE_MILLIS]
 * so viewers can see the outcome, then the presentation is cleared. The delay
 * is anchored to [Prediction.endedAt] so a duplicate final event cannot extend
 * the display window; a final whose grace period already elapsed clears
 * immediately.
 */
internal object PredictionDismissalPolicy {
    fun dismissalDelayMillis(endedAt: Long?, now: Long = System.currentTimeMillis()): Long =
        if (endedAt != null) {
            (PredictionState.RESULT_DISPLAY_GRACE_MILLIS - (now - endedAt)).coerceAtLeast(0L)
        } else {
            PredictionState.RESULT_DISPLAY_GRACE_MILLIS
        }

    fun shouldDismiss(current: Prediction?, predictionId: String?): Boolean =
        !predictionId.isNullOrBlank() &&
            current?.id == predictionId &&
            PredictionState.isFinal(current)
}
