package com.github.andreyasadchy.xtra.ui.player

import com.github.andreyasadchy.xtra.model.VideoQuality
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun playerQualityOptions(catalog: List<VideoQuality>): List<VideoQuality> {
    val audio = catalog.firstOrNull { it.name?.startsWith("audio", ignoreCase = true) == true }
    val videos = catalog.filter {
        it.name != PlaybackContract.AUTO_QUALITY && it.name != PlaybackContract.CHAT_ONLY_QUALITY &&
            it.name?.startsWith("audio", ignoreCase = true) != true
    }.sortedWith(
        compareByDescending<VideoQuality> { it.name.equals("source", ignoreCase = true) }
            .thenByDescending { it.name?.substringBefore('p')?.toIntOrNull() }
            .thenByDescending { it.frameRate ?: it.name?.substringAfter('p', "")?.toFloatOrNull() ?: 30f }
            .thenByDescending { it.bitrate },
    )
    return buildList {
        add(VideoQuality(PlaybackContract.AUTO_QUALITY))
        addAll(videos.map {
            if (it.name.equals("source", ignoreCase = true)) {
                VideoQuality(PlaybackContract.SOURCE_QUALITY, it.codecs, it.bitrate, it.url, it.frameRate)
            } else it
        })
        add(VideoQuality(PlaybackContract.AUDIO_ONLY_QUALITY, audio?.codecs, audio?.bitrate, audio?.url))
    }
}

internal fun encodePlaybackQualities(json: Json, qualities: List<VideoQuality>?): String? =
    qualities?.takeIf { it.isNotEmpty() }?.let { json.encodeToString(it) }

internal fun encodePlaybackQuality(json: Json, quality: VideoQuality?): String? =
    quality?.let { json.encodeToString(it) }

internal fun decodePlaybackQualities(
    json: Json,
    encoded: String?,
    onError: (Exception) -> Unit = {},
): List<VideoQuality>? {
    if (encoded.isNullOrBlank()) return null
    return try {
        json.decodeFromString<List<VideoQuality>>(encoded)
    } catch (e: Exception) {
        onError(e)
        null
    }
}

internal fun decodePlaybackQuality(
    json: Json,
    encoded: String?,
    onError: (Exception) -> Unit = {},
): VideoQuality? {
    if (encoded.isNullOrBlank()) return null
    return try {
        json.decodeFromString<VideoQuality>(encoded)
    } catch (e: Exception) {
        onError(e)
        null
    }
}

internal fun resolvePlaybackQuality(
    qualities: List<VideoQuality>?,
    candidate: VideoQuality?,
): VideoQuality? {
    candidate ?: return null
    val namedMatch = qualities?.firstOrNull { actual ->
        actual.name.equals(candidate.name, ignoreCase = true) &&
            (candidate.codecs.isNullOrBlank() || actual.codecs.equals(candidate.codecs, ignoreCase = true)) &&
            (candidate.bitrate == null || actual.bitrate == candidate.bitrate)
    }
    return namedMatch ?: qualities?.firstOrNull {
        it.name.equals(candidate.name, ignoreCase = true)
    }
}

internal fun selectRestoredQuality(
    qualities: List<VideoQuality>?,
    candidate: VideoQuality?,
): VideoQuality? = candidate?.takeIf { quality ->
    qualities?.any { it.name == quality.name && it.url == quality.url } == true
}
