package com.github.andreyasadchy.xtra.ui.search

import com.github.andreyasadchy.xtra.util.C

/** Search tab keys: "0" videos, "1" streams, "2" channels, "3" games, "4" all. */
object SearchTabs {
    const val ALL = "4"

    private fun keyOf(item: String) = item.substringBefore(':')

    /**
     * The tab list to use, as "key:default:enabled" items. Tabs added by an update are inserted where
     * the defaults put them. When [ALL] is added to an existing setup it becomes the default tab and
     * the user's own tabs are kept as they were; any other added tab never takes over the default.
     */
    fun resolve(stored: String?): List<String> {
        val defaults = C.DEFAULT_SEARCH_TABS.split(',')
        if (stored == null) return defaults
        val list = stored.split(',')
            .filter { item -> item.split(':').size == 3 && defaults.any { keyOf(it) == keyOf(item) } }
            .toMutableList()
        var allWasMissing = false
        defaults.forEachIndexed { index, item ->
            if (list.none { keyOf(it) == keyOf(item) }) {
                val parts = item.split(':')
                if (parts[0] == ALL) {
                    allWasMissing = true
                    list.add(index.coerceAtMost(list.size), item)
                } else {
                    list.add(index.coerceAtMost(list.size), "${parts[0]}:0:${parts[2]}")
                }
            }
        }
        if (allWasMissing) {
            return list.map {
                val parts = it.split(':')
                if (parts[0] == ALL) it else "${parts[0]}:0:${parts[2]}"
            }
        }
        return list
    }
}
