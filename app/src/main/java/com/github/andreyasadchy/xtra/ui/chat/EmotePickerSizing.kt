package com.github.andreyasadchy.xtra.ui.chat

internal fun calculateEmotePickerPagerHeight(
    hostHeight: Int,
    fixedContentHeight: Int,
    tabHeight: Int,
    pickerMargins: Int,
    maxHeight: Int,
): Int {
    return (hostHeight - fixedContentHeight - tabHeight - pickerMargins).coerceIn(0, maxHeight)
}
