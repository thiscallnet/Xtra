package com.github.andreyasadchy.xtra.ui.player

import com.github.andreyasadchy.xtra.model.VideoQuality
import java.util.Locale

internal fun videoQualityDisplayNames(
    qualities: List<VideoQuality>,
    specialLabel: (String) -> CharSequence?,
): List<Pair<String, VideoQuality>> {
    val nameCounts = qualities
        .mapNotNull { it.name?.takeIf(String::isNotBlank)?.lowercase(Locale.ROOT) }
        .groupingBy { it }
        .eachCount()
    val hideCodecs = qualities.all { quality ->
        when (quality.codecs?.substringBefore('.')) {
            "avc1", "mp4a", null -> true
            else -> false
        }
    }

    return qualities.map { quality ->
        val name = quality.name.orEmpty()
        val label = specialLabel(name)?.toString() ?: when {
            nameCounts[name.lowercase(Locale.ROOT)]?.let { it > 1 } == true ->
                "$name (${quality.duplicateVariantDescription()})"
            hideCodecs -> name
            else -> "$name ${quality.videoCodecName()}"
        }
        label to quality
    }
}

private fun VideoQuality.duplicateVariantDescription(): String = buildList {
    videoCodecName()?.let(::add)
    bitrate?.takeIf { it > 0 }?.let { value ->
        add(String.format(Locale.getDefault(), "%.1f Mbps", value / 1_000_000.0))
    }
}.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "${bitrate ?: "unknown"} bps"

private fun VideoQuality.videoCodecName(): String? {
    val videoCodec = codecs
        ?.split(',')
        ?.firstOrNull { codec ->
            codec.startsWith("avc1.", true) || codec.startsWith("hvc1.", true) ||
                codec.startsWith("hev1.", true) || codec.startsWith("av01.", true)
        }
        ?: return null
    val codecType = videoCodec.substringBefore('.').lowercase(Locale.ROOT)
    return when (codecType) {
        "avc1" -> {
            val profile = when (videoCodec.substringAfter('.', "").take(2).uppercase(Locale.ROOT)) {
                "42" -> "Baseline"
                "4D" -> "Main"
                "58" -> "Extended"
                "64" -> "High"
                "6E" -> "High 10"
                "7A" -> "High 4:2:2"
                "F4" -> "High 4:4:4"
                else -> null
            }
            if (profile == null) "H.264" else "H.264 $profile"
        }
        "hvc1", "hev1" -> "H.265"
        "av01" -> "AV1"
        else -> null
    }
}
