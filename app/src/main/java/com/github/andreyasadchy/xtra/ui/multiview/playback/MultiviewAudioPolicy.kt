package com.github.andreyasadchy.xtra.ui.multiview.playback

/**
 * Keeps VAFT-suppressed slots silent regardless of which stream is selected for audio.
 * This is deliberately pure so audio routing regressions can be tested without ExoPlayer.
 */
object MultiviewAudioPolicy {
    fun volumeFor(
        identity: String,
        audioVolumes: Map<String, Float>,
        hiddenForVaft: Boolean,
        fallbackVolume: Float = 0f,
    ): Float {
        return if (hiddenForVaft) {
            0f
        } else {
            (audioVolumes[identity] ?: fallbackVolume).coerceIn(0f, 1f)
        }
    }

    /** Compatibility overload for the original single-active-stream policy. */
    fun volumeFor(
        identity: String,
        activeIdentity: String?,
        hiddenForVaft: Boolean,
        activeVolume: Float,
    ): Float {
        return if (!hiddenForVaft && identity == activeIdentity) activeVolume else 0f
    }
}
