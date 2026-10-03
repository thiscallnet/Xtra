package com.github.andreyasadchy.xtra.util.chat

import android.content.SharedPreferences
import org.json.JSONArray

/** Remembers recently dismissed pinned-message IDs for each channel. */
object PinnedMessageDismissalCache {
    private const val DISMISSED_PREFIX = "dismissed_pinned_messages_"
    private const val MAX_DISMISSED_PER_CHANNEL = 64

    fun dismiss(preferences: SharedPreferences, channelId: String, pinnedMessageId: String) {
        if (channelId.isBlank() || pinnedMessageId.isBlank()) return

        val dismissed = readDismissed(preferences, channelId).toMutableList().apply {
            remove(pinnedMessageId)
            add(pinnedMessageId)
        }
        preferences.edit()
            .putString(
                DISMISSED_PREFIX + channelId,
                JSONArray().apply {
                    dismissed.takeLast(MAX_DISMISSED_PER_CHANNEL).forEach(::put)
                }.toString(),
            )
            .apply()
    }

    fun isDismissed(preferences: SharedPreferences, channelId: String, pinnedMessageId: String): Boolean {
        if (channelId.isBlank() || pinnedMessageId.isBlank()) return false
        return pinnedMessageId in readDismissed(preferences, channelId)
    }

    private fun readDismissed(preferences: SharedPreferences, channelId: String): Set<String> {
        val value = preferences.getString(DISMISSED_PREFIX + channelId, null) ?: return emptySet()
        return runCatching {
            val array = JSONArray(value)
            buildSet {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.getOrDefault(emptySet())
    }
}
