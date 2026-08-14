package com.github.andreyasadchy.xtra.ui.multiview.playback

enum class MultiviewQualityMode {
    AUTO,
    QUALITY_360P,
    QUALITY_480P,
    QUALITY_720P,
    QUALITY_1080P;

    companion object {
        fun fromPersistedName(name: String?): MultiviewQualityMode? = when (name) {
            // Values used by the original multiview quality menu.
            "SMART", "CUSTOM" -> AUTO
            "DATA_SAVER" -> QUALITY_480P
            "HIGH_QUALITY" -> QUALITY_1080P
            else -> name?.let { runCatching { valueOf(it) }.getOrNull() }
        }
    }
}
