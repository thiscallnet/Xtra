package com.github.andreyasadchy.xtra.ui.player

import com.github.andreyasadchy.xtra.model.VideoQuality
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
