package com.github.andreyasadchy.xtra.ui.player

import com.github.andreyasadchy.xtra.model.VideoQuality

/**
 * Converts raw HLS variants into the quality choices displayed by the player.
 * The service and persistence layer keep the raw list; these synthetic
 * entries belong only to the UI.
 */
internal fun normalizePlaybackQualities(rawQualities: List<VideoQuality>): List<VideoQuality> {
    val sortedQualities = rawQualities
        .filterNot {
            it.name == BasePlaybackService.AUTO_QUALITY ||
                it.name == BasePlaybackService.AUDIO_ONLY_QUALITY ||
                it.name == BasePlaybackService.CHAT_ONLY_QUALITY
        }
        .asSequence()
        .sortedByDescending { it.bitrate }
        .sortedByDescending {
            it.name?.substringAfter("p", "")?.takeWhile { character -> character.isDigit() }?.toIntOrNull()
        }
        .sortedByDescending {
            it.name?.substringBefore("p", "")?.takeWhile { character -> character.isDigit() }?.toIntOrNull()
        }
        .toMutableList()

    val source = sortedQualities.find { it.name?.equals("source", ignoreCase = true) == true }
    val audio = sortedQualities.find { it.name?.startsWith("audio", ignoreCase = true) == true }

    return sortedQualities.apply {
        add(0, VideoQuality(BasePlaybackService.AUTO_QUALITY))
        source?.let {
            remove(it)
            add(1, VideoQuality(BasePlaybackService.SOURCE_QUALITY, it.codecs, it.bitrate, it.url))
        }
        audio?.let(::remove)
        add(VideoQuality(BasePlaybackService.AUDIO_ONLY_QUALITY, audio?.codecs, audio?.bitrate, audio?.url))
    }
}
