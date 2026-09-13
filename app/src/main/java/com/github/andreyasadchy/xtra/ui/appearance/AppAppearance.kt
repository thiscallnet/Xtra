package com.github.andreyasadchy.xtra.ui.appearance

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.android.material.appbar.AppBarLayout
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

internal const val DEFAULT_BACKGROUND_VISIBILITY = 65

internal enum class PlayerBackgroundMode(val preferenceValue: String) {
    INHERIT_APP("inherit_app"),
    CUSTOM("custom"),
    OFF("off");

    companion object {
        fun fromPreference(value: String?): PlayerBackgroundMode =
            entries.firstOrNull { it.preferenceValue == value } ?: INHERIT_APP
    }
}

internal data class BackgroundConfiguration(
    val uri: Uri? = null,
    val enabled: Boolean = false,
    val visibility: Int = DEFAULT_BACKGROUND_VISIBILITY,
) {
    val canRender: Boolean
        get() = enabled && uri != null && visibility > 0
}

internal fun makeBackdropAwareChrome(view: View) {
    view.findViewById<AppBarLayout>(com.github.andreyasadchy.xtra.R.id.appBar)?.setBackgroundColor(Color.TRANSPARENT)
    view.findViewById<Toolbar>(com.github.andreyasadchy.xtra.R.id.toolbar)?.setBackgroundColor(Color.TRANSPARENT)
}

/** Reads and writes the shared appearance model used by all first-party activities. */
internal class AppearanceRepository(context: Context) {
    private val preferences = context.prefs()
    private val listeners = mutableSetOf<() -> Unit>()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in OBSERVED_KEYS) listeners.toList().forEach { it() }
    }

    fun addChangeListener(listener: () -> Unit): () -> Unit {
        if (listeners.isEmpty()) preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        listeners += listener
        return {
            listeners -= listener
            if (listeners.isEmpty()) preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        }
    }

    fun appBackground(): BackgroundConfiguration = BackgroundConfiguration(
        uri = preferences.getString(C.APP_BACKGROUND_URI, null).toUriOrNull(),
        enabled = preferences.getBoolean(C.APP_BACKGROUND_ENABLED, false),
        visibility = preferences.getInt(C.APP_BACKGROUND_VISIBILITY, DEFAULT_BACKGROUND_VISIBILITY),
    )

    fun playerBackgroundMode(): PlayerBackgroundMode = PlayerBackgroundMode.fromPreference(
        preferences.getString(C.PLAYER_BACKGROUND_MODE, null),
    )

    fun playerBackground(): BackgroundConfiguration = BackgroundConfiguration(
        uri = preferences.getString(C.PLAYER_BACKGROUND_URI, null).toUriOrNull(),
        enabled = playerBackgroundMode() == PlayerBackgroundMode.CUSTOM,
        visibility = preferences.getInt(C.PLAYER_BACKGROUND_VISIBILITY, DEFAULT_BACKGROUND_VISIBILITY),
    )

    fun resolvedPlayerBackground(): BackgroundConfiguration = resolvePlayerBackground(
        app = appBackground(),
        player = playerBackground(),
        mode = playerBackgroundMode(),
    )

    fun setAppBackgroundEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(C.APP_BACKGROUND_ENABLED, enabled) }
    }

    fun setAppBackgroundVisibility(visibility: Int) {
        preferences.edit { putInt(C.APP_BACKGROUND_VISIBILITY, visibility.coerceIn(0, 100)) }
    }

    fun setAppBackgroundUri(uri: Uri) {
        replaceUri(C.APP_BACKGROUND_URI, uri)
        preferences.edit { putBoolean(C.APP_BACKGROUND_ENABLED, true) }
    }

    fun setPlayerBackgroundMode(mode: PlayerBackgroundMode) {
        preferences.edit { putString(C.PLAYER_BACKGROUND_MODE, mode.preferenceValue) }
    }

    fun setPlayerBackgroundVisibility(visibility: Int) {
        preferences.edit { putInt(C.PLAYER_BACKGROUND_VISIBILITY, visibility.coerceIn(0, 100)) }
    }

    fun setPlayerBackgroundUri(uri: Uri) {
        replaceUri(C.PLAYER_BACKGROUND_URI, uri)
        preferences.edit { putString(C.PLAYER_BACKGROUND_MODE, PlayerBackgroundMode.CUSTOM.preferenceValue) }
    }

    fun resetAppBackground() {
        releasePersistedUri(C.APP_BACKGROUND_URI)
        preferences.edit {
            remove(C.APP_BACKGROUND_URI)
            remove(C.APP_BACKGROUND_VISIBILITY)
            putBoolean(C.APP_BACKGROUND_ENABLED, false)
        }
    }

    fun resetPlayerBackground() {
        releasePersistedUri(C.PLAYER_BACKGROUND_URI)
        preferences.edit {
            remove(C.PLAYER_BACKGROUND_URI)
            remove(C.PLAYER_BACKGROUND_VISIBILITY)
            putString(C.PLAYER_BACKGROUND_MODE, PlayerBackgroundMode.INHERIT_APP.preferenceValue)
        }
    }

    fun isUriReadable(uri: Uri?): Boolean = uri?.let {
        runCatching { preferencesContext.contentResolver.openInputStream(it)?.use { } != null }.getOrDefault(false)
    } == true

    private val preferencesContext: Context = context.applicationContext ?: context

    private fun replaceUri(key: String, uri: Uri) {
        val previous = preferences.getString(key, null).toUriOrNull()
        preferences.edit { putString(key, uri.toString()) }
        if (previous != null && previous != uri) releaseUri(previous)
    }

    private fun releasePersistedUri(key: String) {
        preferences.getString(key, null).toUriOrNull()?.let(::releaseUri)
    }

    private fun releaseUri(uri: Uri) {
        runCatching {
            preferencesContext.contentResolver.releasePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private companion object {
        val OBSERVED_KEYS = setOf(
            C.APP_BACKGROUND_ENABLED,
            C.APP_BACKGROUND_URI,
            C.APP_BACKGROUND_VISIBILITY,
            C.PLAYER_BACKGROUND_MODE,
            C.PLAYER_BACKGROUND_URI,
            C.PLAYER_BACKGROUND_VISIBILITY,
            C.PLAYER_MESSAGE_TEXT_COLOR,
            C.PLAYER_METADATA_TEXT_COLOR,
        )
    }
}

internal fun resolvePlayerBackground(
    app: BackgroundConfiguration,
    player: BackgroundConfiguration,
    mode: PlayerBackgroundMode,
): BackgroundConfiguration = when (mode) {
    PlayerBackgroundMode.INHERIT_APP -> app
    PlayerBackgroundMode.CUSTOM -> player.copy(enabled = true)
    PlayerBackgroundMode.OFF -> BackgroundConfiguration()
}

private fun String?.toUriOrNull(): Uri? = this
    ?.takeIf { it.isNotBlank() }
    ?.let { runCatching { it.toUri() }.getOrNull() }
