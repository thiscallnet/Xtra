package com.github.andreyasadchy.xtra.ui.following

import androidx.annotation.StringRes
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C

/** The shared serialized tab definition used by Following and its settings. */
object FollowingTabs {
    internal const val LEGACY_OVERVIEW = "4"
    internal const val LEGACY_CHANNELS = "3"

    private const val LEGACY_DEFAULT = "0:0:1,1:1:1,2:0:1,3:0:1"

    data class Definition(
        val key: String,
        @StringRes val titleRes: Int,
    )

    val definitions = listOf(
        Definition("1", R.string.live),
        Definition("2", R.string.videos),
        Definition("0", R.string.following_categories),
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

    fun preferredTabKey(entries: List<String>, visibleKeys: List<String>, requestedKey: String?): String? {
        return requestedKey?.takeIf(visibleKeys::contains)
            ?: entries.firstOrNull { it.split(':').getOrNull(1) != "0" }
                ?.substringBefore(':')
                ?.takeIf(visibleKeys::contains)
            ?: "1".takeIf(visibleKeys::contains)
            ?: visibleKeys.firstOrNull()
    }

    internal fun legacyMovedTabEnabled(stored: String?, key: String): Boolean? {
        if (stored == null) return null
        val explicitEntry = stored.split(',').firstOrNull { entry ->
            val parts = entry.split(':')
            parts.size == 3 && parts[0] == key
        }
        return explicitEntry?.split(':')?.getOrNull(2)?.let { it != "0" } ?: true
    }

    internal fun ensureLiveTabEnabled(stored: String?): String? {
        if (stored == null) return null
        val entries = resolve(stored).toMutableList()
        if (visibleKeys(entries, showVideos = false).isNotEmpty()) return entries.joinToString(",")

        val liveIndex = entries.indexOfFirst { it.substringBefore(':') == "1" }
        if (liveIndex >= 0) {
            val parts = entries[liveIndex].split(':').toMutableList()
            parts[2] = "1"
            entries[liveIndex] = parts.joinToString(":")
        } else {
            entries.add("1:1:1")
        }
        return entries.joinToString(",")
    }

    @StringRes
    fun titleRes(key: String): Int = definitions.firstOrNull { it.key == key }?.titleRes ?: R.string.live

    /** Removes tabs that moved out of Following while preserving the remaining layout. */
    fun migrateStoredPreference(stored: String?): String? {
        if (stored == null) {
            return stored
        }

        if (stored == LEGACY_DEFAULT) return C.DEFAULT_FOLLOWING_TABS

        return resolve(stored).joinToString(",")
    }

    private fun isValidEntry(entry: String): Boolean {
        val parts = entry.split(':')
        return parts.size == 3 && parts[0] in knownKeys
    }

    private fun keyOf(entry: String): String = entry.substringBefore(':')
}
