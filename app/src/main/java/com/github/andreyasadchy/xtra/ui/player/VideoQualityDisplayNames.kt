package com.github.andreyasadchy.xtra.ui.player

import com.github.andreyasadchy.xtra.model.VideoQuality
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

internal fun videoQualityDisplayNames(
    qualities: List<VideoQuality>,
    specialLabel: (String) -> CharSequence?,
): List<Pair<String, VideoQuality>> = qualities.map { quality ->
    val name = quality.name.orEmpty()
    val special = specialLabel(name)?.toString()
    val baseLabel = special ?: name
    val details = buildList {
        val codec = if (name == PlaybackContract.AUDIO_ONLY_QUALITY) {
            quality.audioCodecName()
        } else {
            quality.videoCodecName()
        }
        codec?.let(::add)
        quality.bitrate?.takeIf { it > 0 }?.let { add(it.toMegabitsLabel()) }
    }
    val showDetailsForSpecial = name == PlaybackContract.SOURCE_QUALITY ||
        name == PlaybackContract.AUDIO_ONLY_QUALITY
    val label = if ((special != null && !showDetailsForSpecial) || details.isEmpty()) {
        baseLabel
    } else {
        "$baseLabel · ${details.joinToString(" · ")}"
    }
    label to quality
}

internal fun shouldUseSelectedQualityLabel(
    selectedQuality: VideoQuality?,
    renderedQuality: VideoQuality?,
): Boolean {
    val selectedName = selectedQuality?.name ?: return false
    val renderedName = renderedQuality?.name ?: return false
    val selectedResolution = Regex("(?i)^(\\d+p)\\d+$")
        .matchEntire(selectedName)
        ?.groupValues
        ?.getOrNull(1)
        ?: return false
    if (!Regex("(?i)^\\d+p$").matches(renderedName) ||
        !selectedResolution.equals(renderedName, ignoreCase = true)
    ) {
        return false
    }

    val renderedFrameRate = renderedQuality.frameRate
    return renderedFrameRate == null || !renderedFrameRate.isFinite() || renderedFrameRate > 30f
}

private fun Int.toMegabitsLabel(): String {
    val megabits = this / 1_000_000.0
    val format = DecimalFormat(
        if (megabits < 1.0) "0.##" else "0.#",
        DecimalFormatSymbols.getInstance(Locale.getDefault()),
    )
    return "${format.format(megabits)} Mbps"
}

private fun VideoQuality.videoCodecName(): String? {
    val videoCodec = codecs
        ?.split(',')
        ?.firstOrNull { codec ->
            codec.startsWith("avc1.", true) || codec.startsWith("hvc1.", true) ||
                codec.startsWith("hev1.", true) || codec.startsWith("av01.", true)
        }
        ?: return null
    return when (videoCodec.substringBefore('.').lowercase(Locale.ROOT)) {
        "avc1" -> "H.264"
        "hvc1", "hev1" -> "H.265"
        "av01" -> "AV1"
        else -> null
    }
}

private fun VideoQuality.audioCodecName(): String? {
    val audioCodec = codecs
        ?.split(',')
        ?.firstOrNull { codec ->
            codec.startsWith("mp4a.", true) || codec.equals("mp4a", true) ||
                codec.startsWith("opus", true) || codec.startsWith("ac-3", true) ||
                codec.startsWith("ac3", true) || codec.startsWith("ec-3", true) ||
                codec.startsWith("eac3", true) || codec.startsWith("vorbis", true)
        }
        ?: return null
    return when (audioCodec.substringBefore('.').lowercase(Locale.ROOT)) {
        "mp4a" -> "AAC"
        "opus" -> "Opus"
        "ac-3", "ac3" -> "AC-3"
        "ec-3", "eac3" -> "E-AC-3"
        "vorbis" -> "Vorbis"
        else -> null
    }
}
