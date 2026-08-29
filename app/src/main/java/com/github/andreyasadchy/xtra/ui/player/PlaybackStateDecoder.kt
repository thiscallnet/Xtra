package com.github.andreyasadchy.xtra.ui.player

import com.github.andreyasadchy.xtra.model.VideoQuality
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement

internal fun decodePlaybackQualities(
    json: Json,
    encoded: String?,
    onError: (Exception) -> Unit = {},
): List<VideoQuality>? {
    if (encoded.isNullOrBlank()) return null

    return try {
        json.decodeFromString<JsonArray>(encoded).map {
            json.decodeFromJsonElement<VideoQuality>(it)
        }
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

internal fun selectRestoredQuality(
    qualities: List<VideoQuality>?,
    candidate: VideoQuality?,
): VideoQuality? = candidate?.takeIf { quality ->
    qualities?.any { it.name == quality.name && it.url == quality.url } == true
}
