package com.github.andreyasadchy.xtra.util.updater

import androidx.annotation.StringRes
import com.github.andreyasadchy.xtra.R

enum class UpdateCheckFrequency(
    val preferenceValue: String,
    val intervalMillis: Long,
    @StringRes val labelRes: Int,
) {
    EVERY_6_HOURS("6_hours", 6L * HOUR_MILLIS, R.string.update_frequency_6_hours),
    EVERY_12_HOURS("12_hours", 12L * HOUR_MILLIS, R.string.update_frequency_12_hours),
    DAILY("daily", DAY_MILLIS, R.string.update_frequency_daily),
    EVERY_3_DAYS("3_days", 3L * DAY_MILLIS, R.string.update_frequency_3_days),
    WEEKLY("weekly", 7L * DAY_MILLIS, R.string.update_frequency_weekly),
    ;

    companion object {
        val DEFAULT = EVERY_6_HOURS

        fun fromPreference(value: String?): UpdateCheckFrequency =
            entries.firstOrNull { it.preferenceValue == value } ?: DEFAULT
    }
}

private const val HOUR_MILLIS = 3_600_000L
private const val DAY_MILLIS = 86_400_000L
