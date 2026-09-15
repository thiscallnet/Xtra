package com.github.andreyasadchy.xtra.repository

import android.content.Context
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

data class WatchStreakReminderState(
    val accountId: String,
    val initialized: Boolean,
    val observedNotificationIds: List<String>,
)

class WatchStreakReminderStateStore(context: Context) {

    private val preferences = context.applicationContext.prefs()

    fun read(): WatchStreakReminderState? {
        val accountId = preferences.stringOrNull(C.WATCH_STREAK_STATE_ACCOUNT_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val observedIds = preferences.stringOrNull(C.WATCH_STREAK_OBSERVED_IDS)
            .orEmpty()
            .split(ID_SEPARATOR)
            .filter(String::isNotBlank)
        return WatchStreakReminderState(
            accountId = accountId,
            initialized = preferences.getBoolean(C.WATCH_STREAK_INITIALIZED, false),
            observedNotificationIds = observedIds,
        )
    }

    fun save(state: WatchStreakReminderState) {
        preferences.edit {
            putString(C.WATCH_STREAK_STATE_ACCOUNT_ID, state.accountId)
            putBoolean(C.WATCH_STREAK_INITIALIZED, state.initialized)
            putString(C.WATCH_STREAK_OBSERVED_IDS, state.observedNotificationIds.takeLast(MAX_OBSERVED_IDS).joinToString(ID_SEPARATOR))
        }
    }

    fun observe(accountId: String, notificationId: String) {
        val existing = read()
        val observed = if (existing?.accountId == accountId) existing.observedNotificationIds else emptyList()
        save(
            WatchStreakReminderState(
                accountId = accountId,
                initialized = true,
                observedNotificationIds = (observed + notificationId).distinct().takeLast(MAX_OBSERVED_IDS),
            ),
        )
    }

    fun clear() {
        preferences.edit {
            remove(C.WATCH_STREAK_STATE_ACCOUNT_ID)
            remove(C.WATCH_STREAK_INITIALIZED)
            remove(C.WATCH_STREAK_OBSERVED_IDS)
        }
    }

    companion object {
        private const val MAX_OBSERVED_IDS = 128
        private const val ID_SEPARATOR = "\u001f"
    }

    private fun android.content.SharedPreferences.stringOrNull(key: String): String? =
        runCatching { getString(key, null) }.getOrNull()
}
