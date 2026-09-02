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
internal const val SETTINGS_SCREEN_PLAYER_CONTROLS = "player_controls"
internal const val SETTINGS_SCREEN_PLAYER = "player"
internal const val SETTINGS_SCREEN_CHAT = "chat"
internal const val MAX_NAVIGATION_VISIBLE_ITEMS = 6
internal const val MAX_TV_NAVIGATION_VISIBLE_ITEMS = 8

internal fun navigationTabDefaults(isTelevision: Boolean): String =
    if (isTelevision) {
        C.DEFAULT_NAVIGATION_TAB_LIST.replace("4:0:0", "4:0:1")
    } else {
        C.DEFAULT_NAVIGATION_TAB_LIST
    }

internal fun resolveNavigationTabList(stored: String?, isTelevision: Boolean): List<String> {
    val defaults = navigationTabDefaults(isTelevision).split(',')
    val knownKeys = defaults.mapTo(hashSetOf()) { it.substringBefore(':') }
    val result = stored.orEmpty().split(',')
        .mapNotNull { entry ->
            val parts = entry.split(':')
            if (parts.size == 3 && parts[0] in knownKeys && parts[1] in setOf("0", "1") && parts[2] in setOf("0", "1")) {
                parts.joinToString(":")
            } else {
                null
            }
        }
        .distinctBy { it.substringBefore(':') }
        .toMutableList()
    val storedHasDrops = result.any { it.substringBefore(':') == "6" }

    defaults.forEach { defaultEntry ->
        if (result.none { it.substringBefore(':') == defaultEntry.substringBefore(':') }) {
            result += defaultEntry
        }
    }

    if (!isTelevision && !storedHasDrops && result.count { it.split(':').getOrNull(2) == "1" } > MAX_NAVIGATION_VISIBLE_ITEMS) {
        val discoverIndex = result.indexOfFirst { it.substringBefore(':') == "4" }
        val dropsIndex = result.indexOfFirst { it.substringBefore(':') == "6" }
        if (discoverIndex >= 0 && dropsIndex >= 0 && result[discoverIndex].split(':').getOrNull(2) == "1") {
            val discoverParts = result[discoverIndex].split(':')
            val dropsParts = result.removeAt(dropsIndex).split(':')
            result[discoverIndex] = discoverParts.let { parts ->
                "${parts[0]}:0:0"
            }
            result.add(
                discoverIndex,
                "${dropsParts[0]}:${if (discoverParts[1] == "1") "1" else dropsParts[1]}:${dropsParts[2]}",
            )
        }
    }
    return limitNavigationVisibleItems(
        result,
        if (isTelevision) MAX_TV_NAVIGATION_VISIBLE_ITEMS else MAX_NAVIGATION_VISIBLE_ITEMS,
    )
}

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

internal fun maximumVisibleItemsForPreference(preferenceKey: String): Int? =
    MAX_NAVIGATION_VISIBLE_ITEMS.takeIf { preferenceKey == C.UI_NAVIGATION_TAB_LIST }

internal fun limitNavigationVisibleItems(
    items: List<String>,
    maximumVisibleItems: Int = MAX_NAVIGATION_VISIBLE_ITEMS,
): List<String> {
    val result = items.toMutableList()
    while (result.count { it.split(':').getOrNull(2) != "0" } > maximumVisibleItems) {
        val index = result.indexOfLast { it.split(':').getOrNull(2) != "0" && !it.startsWith("4:") }
            .takeIf { it >= 0 }
            ?: result.indexOfLast { it.startsWith("4:") && it.split(':').getOrNull(2) != "0" }
        if (index < 0) break
        val parts = result[index].split(':')
        result[index] = "${parts[0]}:${parts.getOrElse(1) { "0" }}:0"
    }
    return result
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
