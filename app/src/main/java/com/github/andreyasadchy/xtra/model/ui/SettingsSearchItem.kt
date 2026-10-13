package com.github.andreyasadchy.xtra.model.ui

import androidx.navigation.NavDirections

data class SettingsSearchItem(
    val navDirections: NavDirections,
    val location: String? = null,
    val key: String? = null,
    val title: CharSequence? = null,
    val summary: CharSequence? = null,
    val value: String? = null,
)
