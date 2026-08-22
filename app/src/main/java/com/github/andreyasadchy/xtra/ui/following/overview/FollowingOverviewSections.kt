package com.github.andreyasadchy.xtra.ui.following.overview

import androidx.annotation.StringRes
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C

/** The user-controlled order and visibility of the shelves on Following Overview. */
object FollowingOverviewSections {
    const val LIVE = "live"
    const val RECOMMENDED = "recommended"
    const val CONTINUE = "continue"
    const val UPCOMING = "upcoming"

    data class Definition(
        val key: String,
        @StringRes val titleRes: Int,
        @StringRes val emptyRes: Int,
    )

    val definitions = listOf(
        Definition(LIVE, R.string.following_live_channels, R.string.following_no_live_channels),
        Definition(RECOMMENDED, R.string.following_recommended_channels, R.string.following_no_recommended_channels),
        Definition(CONTINUE, R.string.following_continue_watching, R.string.following_no_continue_watching),
        Definition(UPCOMING, R.string.following_upcoming_streams, R.string.following_no_upcoming_streams),
    )

    private val knownKeys = definitions.mapTo(hashSetOf()) { it.key }

    fun resolve(stored: String?): List<String> {
        val defaultEntries = C.DEFAULT_FOLLOWING_OVERVIEW_SECTIONS.split(',')
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

    fun visibleKeys(stored: String?): List<String> = resolve(stored)
        .mapNotNull { entry ->
            entry.split(':').takeIf { it.getOrNull(2) != "0" }?.firstOrNull()
        }

    @StringRes
    fun titleRes(key: String): Int = definitions.firstOrNull { it.key == key }?.titleRes
        ?: R.string.following_overview

    private fun isValidEntry(entry: String): Boolean {
        val parts = entry.split(':')
        return parts.size == 3 && parts[0] in knownKeys
    }

    private fun keyOf(entry: String): String = entry.substringBefore(':')
}
