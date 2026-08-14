package com.github.andreyasadchy.xtra.ui.multiview.playback

/**
 * Keeps ad-suppressed slots silent regardless of which stream is selected for audio.
 * This is deliberately pure so audio routing regressions can be tested without ExoPlayer.
 */
object MultiviewAudioPolicy {
    fun volumeFor(
        identity: String,
        audioVolumes: Map<String, Float>,
        hiddenForAd: Boolean,
        fallbackVolume: Float = 0f,
    ): Float {
        return if (hiddenForAd) {
            0f
        } else {
            (audioVolumes[identity] ?: fallbackVolume).coerceIn(0f, 1f)
        }
    }

    /** Compatibility overload for the original single-active-stream policy. */
    fun volumeFor(
        identity: String,
        activeIdentity: String?,
        hiddenForAd: Boolean,
        activeVolume: Float,
    ): Float {
        return if (!hiddenForAd && identity == activeIdentity) activeVolume else 0f
    }
}
