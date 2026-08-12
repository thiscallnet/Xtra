package com.github.andreyasadchy.xtra.ui.multiview.playback

/**
 * Keeps ad-suppressed slots silent regardless of which stream is selected for audio.
 * This is deliberately pure so audio routing regressions can be tested without ExoPlayer.
 */
object MultiviewAudioPolicy {
    fun volumeFor(
        identity: String,
        activeIdentity: String?,
        hiddenForAd: Boolean,
        activeVolume: Float,
    ): Float {
        return if (!hiddenForAd && identity == activeIdentity) activeVolume else 0f
    }
}
