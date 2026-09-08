package com.github.andreyasadchy.xtra.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val DEFAULT_CHAT_UI_BATCH_INTERVAL_MS = 200L

fun parseChatUiBatchIntervalMs(value: String?): Long =
    value?.trim()?.toLongOrNull()?.takeIf { it >= 0L } ?: DEFAULT_CHAT_UI_BATCH_INTERVAL_MS

internal object ChatBatchingPreferences : SharedPreferences.OnSharedPreferenceChangeListener {
    private val lock = Any()
    private val _batchIntervalMs = MutableStateFlow(DEFAULT_CHAT_UI_BATCH_INTERVAL_MS)
    private val batchIntervalMs = _batchIntervalMs.asStateFlow()
    @Volatile
    private var preferences: SharedPreferences? = null

    fun intervalMs(context: Context): StateFlow<Long> {
        synchronized(lock) {
            if (preferences == null) {
                val currentPreferences = context.applicationContext.prefs()
                preferences = currentPreferences
                _batchIntervalMs.value = read(currentPreferences)
                currentPreferences.registerOnSharedPreferenceChangeListener(this)
            }
        }
        return batchIntervalMs
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key != C.CHAT_UI_BATCH_INTERVAL_MS) return
        val currentPreferences = preferences ?: return
        _batchIntervalMs.value = read(currentPreferences)
    }

    private fun read(preferences: SharedPreferences): Long = parseChatUiBatchIntervalMs(
        preferences.getString(C.CHAT_UI_BATCH_INTERVAL_MS, null),
    )
}
