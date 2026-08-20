package com.github.andreyasadchy.xtra.ui.following

import androidx.annotation.StringRes
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C

/** The shared serialized tab definition used by both Following implementations and Settings. */
object FollowingTabs {
    const val OVERVIEW = "4"

    private const val LEGACY_DEFAULT = "0:0:1,1:1:1,2:0:1,3:0:1"

    data class Definition(
        val key: String,
        @StringRes val titleRes: Int,
    )

    val definitions = listOf(
        Definition(OVERVIEW, R.string.following_overview),
        Definition("1", R.string.live),
        Definition("2", R.string.videos),
        Definition("0", R.string.following_categories),
        Definition("3", R.string.channels),
    )

    private val knownKeys = definitions.mapTo(hashSetOf()) { it.key }

    fun resolve(stored: String?): List<String> {
        val defaultEntries = C.DEFAULT_FOLLOWING_TABS.split(',')
        val storedEntries = stored
            ?.split(',')
            ?.filter(::isValidEntry)
            ?.distinctBy(::keyOf)
            ?.toMutableList()

        if (storedEntries == null) return defaultEntries

        defaultEntries.forEachIndexed { index, defaultEntry ->
            if (storedEntries.none { keyOf(it) == keyOf(defaultEntry) }) {
                storedEntries.add(index.coerceAtMost(storedEntries.size), defaultEntry)
            }
        }
        return storedEntries
    }

    fun visibleKeys(stored: String?, showVideos: Boolean): List<String> {
        return visibleKeys(resolve(stored), showVideos)
    }

    fun visibleKeys(entries: List<String>, showVideos: Boolean): List<String> {
        return entries.mapNotNull { entry ->
            val parts = entry.split(':')
            val key = parts.firstOrNull()
            val enabled = parts.getOrNull(2) != "0"
            key?.takeIf { enabled && (it != "2" || showVideos) }
        }
    }

    @StringRes
    fun titleRes(key: String): Int = definitions.firstOrNull { it.key == key }?.titleRes ?: R.string.live

    /** Adds Overview to a pre-redesign preference while preserving custom landing tabs. */
    fun migrateStoredPreference(stored: String?): String? {
        if (stored == null || stored.split(',').any { isValidEntry(it) && keyOf(it) == OVERVIEW }) {
            return stored
        }

        if (stored == LEGACY_DEFAULT) return C.DEFAULT_FOLLOWING_TABS

        return resolve(stored).joinToString(",") { entry ->
            val parts = entry.split(':').toMutableList()
            if (parts.firstOrNull() == OVERVIEW) {
                // A custom layout should keep its chosen landing tab. Overview is
                // available after migration, but must not silently replace it.
                parts[1] = "0"
            }
            parts.joinToString(":")
        }
    }

    private fun isValidEntry(entry: String): Boolean {
        val parts = entry.split(':')
        return parts.size == 3 && parts[0] in knownKeys
    }

    private fun keyOf(entry: String): String = entry.substringBefore(':')
}
