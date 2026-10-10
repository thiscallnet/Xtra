package com.github.andreyasadchy.xtra.ui.search

import com.github.andreyasadchy.xtra.model.ui.SearchHistoryItem

/** The result groups the All tab can show from Twitch, in the order they are listed when scores tie. */
enum class SearchSection { CHANNELS, CATEGORIES, STREAMS, VIDEOS }

/**
 * Orders the Twitch result groups by what [query] most likely is, so people never pick a category first.
 *
 * - A near-exact channel name puts channels first (their live stream follows).
 * - A near-exact category name puts the category first, then the streams playing it.
 * - A longer phrase that names no channel or category reads like a stream or video title.
 */
internal fun sectionOrder(
    query: String,
    channels: List<SearchHistoryItem>,
    categories: List<SearchHistoryItem>,
): List<SearchSection> {
    val channelStrength = channels.maxOfOrNull { it.matchRank(query) } ?: 0
    val categoryStrength = categories.maxOfOrNull { it.matchRank(query) } ?: 0
    val words = query.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
    val titleLike = words >= 3 || (words == 2 && channelStrength < 3 && categoryStrength < 3)
    val scores = mapOf(
        SearchSection.CHANNELS to channelStrength * 10 + 2,
        SearchSection.CATEGORIES to categoryStrength * 10 + 1,
        SearchSection.STREAMS to when {
            categoryStrength >= 3 -> 30
            titleLike -> 26
            channelStrength >= 3 -> 12
            else -> 8
        },
        SearchSection.VIDEOS to if (titleLike) 24 else 5,
    )
    return SearchSection.entries.sortedByDescending { scores.getValue(it) }
}
