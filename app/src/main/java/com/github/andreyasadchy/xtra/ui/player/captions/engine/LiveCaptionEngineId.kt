package com.github.andreyasadchy.xtra.ui.player.captions.engine

enum class LiveCaptionEngineId(val preferenceValue: String) {
    ZIPFORMER_20M("zipformer_20m"),
    MOONSHINE_V2_TINY("moonshine_v2_tiny"),
    ZIPFORMER_MOONSHINE_2PASS("zipformer_moonshine_2pass"),
    ;

    companion object {
        fun fromPreference(value: String?): LiveCaptionEngineId {
            return entries.firstOrNull { it.preferenceValue == value } ?: ZIPFORMER_20M
        }
    }
}
