package com.github.andreyasadchy.xtra.model.ui

class SettingsDragListItem(
    val key: String,
    var text: String,
    var default: Boolean,
    var enabled: Boolean,
    var group: String = "hidden",
)
