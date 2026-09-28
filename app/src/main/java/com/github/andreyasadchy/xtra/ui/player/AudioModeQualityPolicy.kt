package com.github.andreyasadchy.xtra.ui.player

import com.github.andreyasadchy.xtra.model.VideoQuality

internal fun resolveAudioModeRestoreQuality(
    previousQuality: VideoQuality?,
    qualities: List<VideoQuality>?,
): VideoQuality? {
    return previousQuality
        ?.takeUnless {
            it.name == PlaybackContract.AUDIO_ONLY_QUALITY ||
                it.name == PlaybackContract.CHAT_ONLY_QUALITY
        }
        ?: qualities?.firstOrNull { it.name == PlaybackContract.AUTO_QUALITY }
        ?: qualities?.firstOrNull {
            it.name != PlaybackContract.AUDIO_ONLY_QUALITY &&
                it.name != PlaybackContract.CHAT_ONLY_QUALITY
        }
}
