package com.github.andreyasadchy.xtra.ui.settings

import android.content.Context
import android.content.Intent
import android.view.View
import com.github.andreyasadchy.xtra.model.ui.SettingsDragListItem
import com.github.andreyasadchy.xtra.util.C
import com.google.android.material.tabs.TabLayout

internal const val EXTRA_SETTINGS_SCREEN = "settings_screen"
internal const val EXTRA_SETTINGS_HIGHLIGHT_PREFERENCE = "settings_highlight_preference"
internal const val SETTINGS_SCREEN_TABS = "tabs"

internal fun Context.openTabCustomization(preferenceKey: String) {
    startActivity(Intent(this, SettingsActivity::class.java).apply {
        putExtra(EXTRA_SETTINGS_SCREEN, SETTINGS_SCREEN_TABS)
        putExtra(EXTRA_SETTINGS_HIGHLIGHT_PREFERENCE, "${preferenceKey}_dialog")
    })
}

internal fun View.setTabCustomizationLongPress(context: Context, preferenceKey: String) {
    setOnLongClickListener {
        context.openTabCustomization(preferenceKey)
        true
    }
}

internal fun TabLayout.setTabCustomizationLongPress(context: Context, preferenceKey: String) {
    post {
        for (index in 0 until tabCount) {
            getTabAt(index)?.view?.setTabCustomizationLongPress(context, preferenceKey)
        }
    }
}

internal fun minimumVisibleItemsForPreference(preferenceKey: String): Int = when (preferenceKey) {
    C.UI_FOLLOWING_TABS,
    C.UI_SAVED_TABS,
    C.UI_CHANNEL_TABS,
    C.UI_GAME_TABS,
    C.UI_SEARCH_TABS,
    -> 1
    else -> 0
}

internal fun canDisableVisibleItem(
    itemEnabled: Boolean,
    visibleItemCount: Int,
    minimumVisibleItems: Int,
): Boolean = !itemEnabled || visibleItemCount > minimumVisibleItems

internal fun ensureMinimumVisibleItems(
    items: List<SettingsDragListItem>,
    minimumVisibleItems: Int,
) {
    var visibleItemCount = items.count { it.enabled }
    items.forEach { item ->
        if (visibleItemCount >= minimumVisibleItems) return@forEach
        if (!item.enabled) {
            item.enabled = true
            visibleItemCount++
        }
    }
}

internal fun promoteDefaultToVisible(items: List<SettingsDragListItem>) {
    val defaultItem = items.firstOrNull { it.default && it.enabled }
        ?: items.firstOrNull { it.enabled }
        ?: return
    items.forEach { it.default = it === defaultItem }
}

internal fun setDefaultItem(items: List<SettingsDragListItem>, item: SettingsDragListItem) {
    items.forEach { it.default = it === item }
    item.enabled = true
}
