package com.github.andreyasadchy.xtra.ui.common

import android.content.Context
import android.content.SharedPreferences
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

/**
 * The small set of display options read by high-frequency feed binds.
 * Preferences are read once and replaced atomically when a setting changes;
 * RecyclerView binds only perform one volatile snapshot read.
 */
internal data class FeedUiPreferences(
    val nameDisplay: String,
    val truncateViewCount: Boolean,
    val showUptime: Boolean,
    val showTags: Boolean,
    val showBroadcastersCount: Boolean,
    val roundUserImage: Boolean,
)

internal object FeedUiPreferencesStore : SharedPreferences.OnSharedPreferenceChangeListener {
    private val lock = Any()

    @Volatile
    private var snapshot: FeedUiPreferences? = null

    private var preferences: SharedPreferences? = null

    fun current(context: Context): FeedUiPreferences {
        snapshot?.let { return it }
        synchronized(lock) {
            snapshot?.let { return it }
            val prefs = context.applicationContext.prefs()
            preferences = prefs
            prefs.registerOnSharedPreferenceChangeListener(this)
            return read(prefs).also { snapshot = it }
        }
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key !in KEYS) return
        val currentPreferences = preferences ?: return
        snapshot = read(currentPreferences)
    }

    private fun read(prefs: SharedPreferences): FeedUiPreferences = FeedUiPreferences(
        nameDisplay = prefs.getString(C.UI_NAME_DISPLAY, "0") ?: "0",
        truncateViewCount = prefs.getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true),
        showUptime = prefs.getBoolean(C.UI_UPTIME, true),
        showTags = prefs.getBoolean(C.UI_TAGS, true),
        showBroadcastersCount = prefs.getBoolean(C.UI_BROADCASTERS_COUNT, true),
        roundUserImage = prefs.getBoolean(C.UI_ROUND_USER_IMAGE, true),
    )

    private val KEYS = setOf(
        C.UI_NAME_DISPLAY,
        C.UI_TRUNCATE_VIEW_COUNT,
        C.UI_UPTIME,
        C.UI_TAGS,
        C.UI_BROADCASTERS_COUNT,
        C.UI_ROUND_USER_IMAGE,
    )
}
