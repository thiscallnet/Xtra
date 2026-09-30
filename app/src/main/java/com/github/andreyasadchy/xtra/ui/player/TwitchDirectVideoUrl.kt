package com.github.andreyasadchy.xtra.ui.player

import com.github.andreyasadchy.xtra.util.TwitchApiHelper

internal data class TwitchDirectVideoUrl(
    val template: String,
    val rendition: String,
    val querySuffix: String,
) {
    val canonicalContentUrl: String
        get() = "$template/chunked/index-dvr.m3u8$querySuffix"

    val qualityName: String
        get() = when (rendition) {
            "chunked" -> PlaybackContract.SOURCE_QUALITY
            "audio_only" -> PlaybackContract.AUDIO_ONLY_QUALITY
            else -> rendition
        }
}

private val plainResolutionQualityName = Regex("^(\\d+)p$", RegexOption.IGNORE_CASE)

internal fun resolveTwitchDirectRendition(name: String?, frameRate: Float? = null): String? {
    val qualityName = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    when {
        qualityName.equals(PlaybackContract.AUTO_QUALITY, ignoreCase = true) ||
            qualityName.equals(PlaybackContract.SOURCE_QUALITY, ignoreCase = true) -> return "chunked"
        qualityName.equals(PlaybackContract.AUDIO_ONLY_QUALITY, ignoreCase = true) -> return "audio_only"
    }
    TwitchApiHelper.defaultQualityList.firstOrNull { it.equals(qualityName, ignoreCase = true) }
        ?.let { return it }

    val resolution = plainResolutionQualityName.matchEntire(qualityName)?.groupValues?.getOrNull(1) ?: return null
    val frameRateSuffix = when {
        frameRate != null && frameRate in 29f..31f -> "30"
        frameRate != null && frameRate in 59f..61f -> "60"
        else -> return null
    }
    return "${resolution}p$frameRateSuffix"
        .takeIf { it in TwitchApiHelper.defaultQualityList }
}

internal fun parseTwitchDirectVideoUrl(url: String): TwitchDirectVideoUrl? {
    val suffix = "/index-dvr.m3u8"
    val queryStart = listOf(url.indexOf('?'), url.indexOf('#'))
        .filter { it >= 0 }
        .minOrNull() ?: url.length
    val manifestStart = url.lastIndexOf(suffix, queryStart - 1)
    if (manifestStart < 0) return null

    val renditionStart = url.lastIndexOf('/', manifestStart - 1)
    if (renditionStart < 0) return null
    val rendition = url.substring(renditionStart + 1, manifestStart)
    if (rendition !in TwitchApiHelper.defaultQualityList) return null

    return TwitchDirectVideoUrl(
        template = url.substring(0, renditionStart),
        rendition = rendition,
        querySuffix = url.substring(queryStart),
    )
}

internal fun canonicalizeTwitchDirectVideoUrl(url: String): String =
    parseTwitchDirectVideoUrl(url)?.canonicalContentUrl ?: url
