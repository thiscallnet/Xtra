package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

/** Stable screen types that can have independent card-grid column counts. */
enum class GridPage(val id: String) {
    BROWSE_GAMES("browse_games"),
    TOP_STREAMS("top_streams"),
    FOLLOWED_GAMES("followed_games"),
    FOLLOWED_STREAMS("followed_streams"),
    FOLLOWED_VIDEOS("followed_videos"),
    GAME_STREAMS("game_streams"),
    GAME_VIDEOS("game_videos"),
    GAME_CLIPS("game_clips"),
    CHANNEL_SUGGESTIONS("channel_suggestions"),
    CHANNEL_VIDEOS("channel_videos"),
    CHANNEL_CLIPS("channel_clips"),
    SEARCH_GAMES("search_games"),
    SEARCH_STREAMS("search_streams"),
    SEARCH_VIDEOS("search_videos"),
    SAVED_BOOKMARKS("saved_bookmarks");

    fun preferenceKey(orientation: Int): String =
        "grid_columns_${id}_${if (orientation == Configuration.ORIENTATION_PORTRAIT) "portrait" else "landscape"}"

    companion object {
        val all: Set<GridPage> = values().toSet()
    }
}

/** Resolves sparse page overrides while keeping the old global values as defaults. */
object GridColumnPreferences {
    fun get(context: Context, page: GridPage, orientation: Int): Int {
        val portrait = orientation == Configuration.ORIENTATION_PORTRAIT
        val legacyKey = if (portrait) C.PORTRAIT_COLUMN_COUNT else C.LANDSCAPE_COLUMN_COUNT
        val maximum = if (portrait) 4 else 6
        val preferences = context.prefs()
        val pageColumns = preferences.readColumns(page.preferenceKey(orientation))
        val legacyColumns = preferences.readColumns(legacyKey)
        val fallback = if (portrait) 1 else 2
        return (pageColumns ?: legacyColumns ?: fallback).coerceIn(1, maximum)
    }

    fun getLegacy(context: Context, orientation: Int): Int {
        val portrait = orientation == Configuration.ORIENTATION_PORTRAIT
        val key = if (portrait) C.PORTRAIT_COLUMN_COUNT else C.LANDSCAPE_COLUMN_COUNT
        val fallback = if (portrait) 1 else 2
        val maximum = if (portrait) 4 else 6
        return context.prefs().readColumns(key)?.coerceIn(1, maximum) ?: fallback
    }

    fun set(context: Context, page: GridPage, orientation: Int, columns: Int) {
        val portrait = orientation == Configuration.ORIENTATION_PORTRAIT
        val maximum = if (portrait) 4 else 6
        val legacyKey = if (portrait) C.PORTRAIT_COLUMN_COUNT else C.LANDSCAPE_COLUMN_COUNT
        val fallback = context.prefs().readColumns(legacyKey)
            ?.coerceIn(1, maximum) ?: if (portrait) 1 else 2
        context.prefs().edit {
            val key = page.preferenceKey(orientation)
            if (columns.coerceIn(1, maximum) == fallback) remove(key)
            else putString(key, columns.coerceIn(1, maximum).toString())
        }
    }

    val preferenceKeys: Set<String>
        get() = GridPage.all.flatMapTo(mutableSetOf()) { page ->
            setOf(
                page.preferenceKey(Configuration.ORIENTATION_PORTRAIT),
                page.preferenceKey(Configuration.ORIENTATION_LANDSCAPE),
            )
        }

    private fun android.content.SharedPreferences.readColumns(key: String): Int? =
        runCatching { getString(key, null)?.toIntOrNull() }.getOrNull()
}
