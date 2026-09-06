package com.github.andreyasadchy.xtra.ui.player

import android.media.MediaCodecList
import android.os.Build

data class VideoDecoderClassification(
    val hardwareAccelerated: Boolean?,
    val softwareOnly: Boolean?,
)

private val codecInfoByName by lazy(LazyThreadSafetyMode.PUBLICATION) {
    runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS)
            .codecInfos
            .associateBy { it.name.lowercase() }
    }.getOrDefault(emptyMap())
}

/**
 * Classifies the decoder selected by Media3 without treating an unfamiliar
 * decoder name as proof of hardware acceleration.
 */
fun classifyVideoDecoder(decoderName: String): VideoDecoderClassification {
    val codecInfo = codecInfoByName[decoderName.lowercase()]

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && codecInfo != null) {
        return VideoDecoderClassification(
            hardwareAccelerated = codecInfo.isHardwareAccelerated,
            softwareOnly = codecInfo.isSoftwareOnly,
        )
    }

    if (isKnownSoftwareDecoderName(decoderName)) {
        return VideoDecoderClassification(
            hardwareAccelerated = false,
            softwareOnly = true,
        )
    }

    return VideoDecoderClassification(
        hardwareAccelerated = null,
        softwareOnly = null,
    )
}

internal fun isKnownSoftwareDecoderName(decoderName: String): Boolean {
    val normalized = decoderName.lowercase()
    return normalized.startsWith("omx.google.") ||
        normalized.startsWith("c2.android.") ||
        normalized.startsWith("c2.google.") ||
        normalized.contains("ffmpeg") ||
        normalized.contains("software") ||
        normalized.contains("sw.decoder")
}
