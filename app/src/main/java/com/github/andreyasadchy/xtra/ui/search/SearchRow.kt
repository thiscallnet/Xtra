package com.github.andreyasadchy.xtra.ui.search

import android.content.Context
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.SearchHistoryItem
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.model.ui.ranked
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs

/** One line of a search list: a section title, a channel/category/video, or a "try elsewhere" footer. */
sealed interface SearchRow {
    val key: String

    data class Header(val title: String, val actionText: String? = null, val actionKey: String? = null) : SearchRow {
        override val key get() = "header:$title"
    }

    data class Entry(
        val item: SearchHistoryItem,
        val subtitle: String,
        val live: Boolean = false,
        val removable: Boolean = false,
        /** Which list the entry came from, so the same channel can appear in two sections. */
        val section: String = "",
        /** Set for live stream and video results, which open the player instead of a page. */
        val stream: Stream? = null,
        val video: Video? = null,
    ) : SearchRow {
        override val key get() = "$section:${item.kind}:${item.refId}"
    }
}

/** Live viewer counts for channels, keyed by channel id and by lowercase login. */
typealias LiveChannels = Map<String, Int>

internal fun LiveChannels.viewersFor(item: SearchHistoryItem): Int? {
    if (!item.isChannel) return null
    return this[item.refId] ?: item.slug?.lowercase()?.let { this[it] }
}

/** Builds a row for [item]. [tag] ("Recent", "Following") prefixes the subtitle when a list mixes sources. */
internal fun searchEntry(
    context: Context,
    item: SearchHistoryItem,
    live: LiveChannels = emptyMap(),
    tag: String? = null,
    removable: Boolean = false,
    section: String = "",
    detail: String? = null,
    forceLive: Boolean = false,
): SearchRow.Entry {
    val viewers = live.viewersFor(item)
    val parts = buildList {
        tag?.let(::add)
        add(
            context.getString(
                when {
                    item.isChannel -> R.string.search_history_channel
                    item.isGame -> R.string.search_history_category
                    else -> R.string.search_history_video
                }
            )
        )
        when {
            item.isChannel -> item.slug?.takeIf { !it.equals(item.title, true) }?.let(::add)
            item.isVideo -> item.videoChannelName()?.let(::add)
        }
        detail?.let(::add)
        if (viewers != null) {
            add(
                context.resources.getQuantityString(
                    R.plurals.viewers,
                    viewers,
                    TwitchApiHelper.formatCount(viewers, context.prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true)),
                )
            )
        }
    }
    return SearchRow.Entry(item, parts.joinToString(" · "), viewers != null || forceLive, removable, section)
}

/** True when both entries are the same channel/category (id or login/slug match). */
internal fun SearchHistoryItem.sameAs(other: SearchHistoryItem): Boolean =
    kind == other.kind && (refId == other.refId || (slug != null && slug.equals(other.slug, true)))

/**
 * Entries worth pinning for [query]: things the user opened before first, then followed channels,
 * never listing the same channel twice.
 */
internal fun matchRows(
    context: Context,
    query: String,
    history: List<SearchHistoryItem>,
    followed: List<SearchHistoryItem>,
    live: LiveChannels,
    kinds: Set<String>,
    limit: Int,
    section: String = "match",
): List<SearchRow.Entry> {
    if (query.isBlank()) return emptyList()
    val recent = history.filter { it.kind in kinds }.ranked(query).take(limit)
    val following = if (SearchHistoryItem.KIND_CHANNEL in kinds) {
        followed.ranked(query).filter { f -> recent.none { it.sameAs(f) } }.take((limit - recent.size).coerceAtLeast(0))
    } else {
        emptyList()
    }
    return recent.map { searchEntry(context, it, live, context.getString(R.string.search_section_recent), section = section) } +
        following.map { searchEntry(context, it, live, context.getString(R.string.search_section_following), section = section) }
}

/** A live stream result: title, then channel, category and viewers. */
internal fun streamEntry(context: Context, stream: Stream, section: String): SearchRow.Entry? {
    val ref = stream.id?.takeIf { it.isNotBlank() } ?: stream.channelLogin?.takeIf { it.isNotBlank() } ?: return null
    val title = stream.title?.takeIf { it.isNotBlank() } ?: stream.channelName ?: return null
    val viewers = stream.viewerCount?.let {
        context.resources.getQuantityString(
            R.plurals.viewers,
            it,
            TwitchApiHelper.formatCount(it, context.prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true)),
        )
    }
    val subtitle = listOfNotNull(stream.channelName ?: stream.channelLogin, stream.gameName, viewers).joinToString(" · ")
    val item = SearchHistoryItem(SearchHistoryItem.KIND_STREAM, ref, title, stream.channelLogin, stream.thumbnail, 0, 0L)
    return SearchRow.Entry(item, subtitle, live = true, section = section, stream = stream)
}

/** A video result: title, then channel and category. */
internal fun videoEntry(video: Video, section: String): SearchRow.Entry? {
    val item = SearchHistoryItem.video(video) ?: return null
    val subtitle = listOfNotNull(video.channelName, video.gameName).joinToString(" · ")
    return SearchRow.Entry(item, subtitle, section = section, video = video)
}
