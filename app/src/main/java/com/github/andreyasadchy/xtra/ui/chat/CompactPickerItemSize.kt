package com.github.andreyasadchy.xtra.ui.chat

/** Visual asset sizes for the opt-in compact picker. The 48dp cell remains the touch target. */
internal enum class CompactPickerItemSize(
    val preferenceValue: String,
    val assetSizeDp: Float,
) {
    SMALL("small", 28f),
    MEDIUM("medium", 32f),
    LARGE("large", 40f),
    ;

    companion object {
        fun fromPreference(value: String?): CompactPickerItemSize = entries.firstOrNull {
            it.preferenceValue == value
        } ?: MEDIUM
    }
}
