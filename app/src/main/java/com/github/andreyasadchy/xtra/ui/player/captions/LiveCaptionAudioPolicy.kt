package com.github.andreyasadchy.xtra.ui.player.captions

/** Audio processors can only receive decoded PCM, never passthrough frames. */
internal enum class LiveCaptionAudioOutputMode { PCM, DIRECT }

internal fun liveCaptionOutputMode(captionsEnabled: Boolean): LiveCaptionAudioOutputMode =
    if (captionsEnabled) LiveCaptionAudioOutputMode.PCM else LiveCaptionAudioOutputMode.DIRECT

internal fun liveCaptionsRequirePcm(captionsEnabled: Boolean): Boolean = captionsEnabled
