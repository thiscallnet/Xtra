package com.github.andreyasadchy.xtra.util

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.res.use
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import com.github.andreyasadchy.xtra.R
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

fun Context.rawPrefs(): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

fun Context.prefs(): SharedPreferences = DeveloperGatedPreferences(rawPrefs())

fun Context.tokenPrefs(): SharedPreferences = getSharedPreferences("prefs2", Context.MODE_PRIVATE)

private class DeveloperGatedPreferences(
    private val delegate: SharedPreferences,
) : SharedPreferences {
    private fun developerOverridesEnabled(): Boolean =
        developerOverridesEnabled(delegate)

    override fun getAll(): MutableMap<String, *> = delegate.all

    override fun getString(key: String?, defValue: String?): String? {
        return developerStringValue(
            key = key,
            storedValue = delegate.getString(key, null),
            defaultValue = defValue,
            enabled = developerOverridesEnabled(),
        )
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        delegate.getStringSet(key, defValues)

    override fun getInt(key: String?, defValue: Int): Int = delegate.getInt(key, defValue)

    override fun getLong(key: String?, defValue: Long): Long = delegate.getLong(key, defValue)

    override fun getFloat(key: String?, defValue: Float): Float = delegate.getFloat(key, defValue)

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        return developerBooleanValue(
            key = key,
            storedValue = delegate.getBoolean(key, defValue),
            enabled = developerOverridesEnabled(),
        )
    }

    override fun contains(key: String?): Boolean = delegate.contains(key)

    override fun edit(): SharedPreferences.Editor = delegate.edit()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        delegate.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        delegate.unregisterOnSharedPreferenceChangeListener(listener)
    }
}

internal fun developerOverridesEnabled(preferences: SharedPreferences): Boolean =
    preferences.getBoolean(C.SETTINGS_DEVELOPER_UNLOCKED, false) &&
        preferences.getBoolean(C.SETTINGS_DEVELOPER_ENABLED, false)

fun Context.developerOverridesEnabled(): Boolean = developerOverridesEnabled(rawPrefs())

internal fun developerStringValue(
    key: String?,
    storedValue: String?,
    defaultValue: String?,
    enabled: Boolean,
): String? {
    if (enabled) {
        return if (key == C.NETWORK_LIBRARY && storedValue == C.AUTOMATIC) C.OKHTTP else storedValue ?: defaultValue
    }
    return when (key) {
        C.API_LOGIN -> "0"
        C.HELIX_CLIENT_ID -> C.DEFAULT_HELIX_CLIENT_ID
        C.HELIX_REDIRECT -> C.DEFAULT_HELIX_REDIRECT
        C.GQL_CLIENT_ID2 -> C.DEFAULT_GQL_CLIENT_ID2
        C.GQL_REDIRECT2 -> "https://www.twitch.tv/settings/connections"
        C.GQL_CLIENT_ID_WEB -> C.DEFAULT_GQL_CLIENT_ID_WEB
        C.NETWORK_LIBRARY -> C.OKHTTP
        C.PLAYER -> C.EXOPLAYER
        C.PLAYER_STREAM_HEADERS -> null
        C.TOKEN_X_DEVICE_ID -> C.DEFAULT_TOKEN_X_DEVICE_ID
        C.TOKEN_PLAYER_TYPE -> C.DEFAULT_TOKEN_PLAYER_TYPE
        C.TOKEN_PLAYER_TYPE_VIDEO -> C.DEFAULT_TOKEN_PLAYER_TYPE_VIDEO
        C.TOKEN_SUPPORTED_CODECS -> C.DEFAULT_TOKEN_SUPPORTED_CODECS
        else -> storedValue ?: defaultValue
    }
}

internal fun developerBooleanValue(key: String?, storedValue: Boolean, enabled: Boolean): Boolean {
    if (enabled) return storedValue
    return when (key) {
        C.DEBUG_API_COMMANDS, C.DEBUG_API_CHAT_MESSAGES -> true
        C.USE_WEBVIEW_INTEGRITY -> true
        C.DEBUG_CHAT_FULL_MSG,
        C.DEBUG_WEBSOCKET_INFO,
        C.DEBUG_EVENT_SUB_CHAT,
        C.DEBUG_PLAYER_MENU_PLAYLIST_TAGS,
        C.ENABLE_INTEGRITY,
        C.GET_ALL_GQL_HEADERS,
        C.PROXY_PLAYBACK_ACCESS_TOKEN,
        C.PROXY_MULTIVARIANT_PLAYLIST,
        C.PROXY_MEDIA_PLAYLIST -> false
        C.DEBUG_USE_CUSTOM_PLAYBACK_SERVICE -> true
        C.TOKEN_RANDOM_DEVICE_ID,
        C.TOKEN_INCLUDE_TOKEN_STREAM,
        C.TOKEN_INCLUDE_TOKEN_VIDEO -> true
        else -> storedValue
    }
}

/** The positive setting is authoritative, while the legacy key remains a fallback during upgrades. */
fun SharedPreferences.isChatEnabled(): Boolean = if (contains(C.SETTINGS_CHAT_ENABLED)) {
    getBoolean(C.SETTINGS_CHAT_ENABLED, true)
} else {
    !getBoolean(C.CHAT_DISABLE, false)
}

/** HTTP proxy credentials are retained when disabled so the user can turn the proxy back on later. */
fun SharedPreferences.httpProxyEnabled(): Boolean = if (contains(C.SETTINGS_HTTP_PROXY_ENABLED)) {
    getBoolean(C.SETTINGS_HTTP_PROXY_ENABLED, false)
} else {
    !getString(C.PROXY_HOST, null).isNullOrBlank() && getString(C.PROXY_PORT, null)?.toIntOrNull() != null
}

fun SharedPreferences.httpProxyHost(): String? = getString(C.PROXY_HOST, null).takeIf { httpProxyEnabled() }

fun SharedPreferences.httpProxyPort(): Int? = getString(C.PROXY_PORT, null)?.toIntOrNull().takeIf { httpProxyEnabled() }

/** The alternate-stream master switch is authoritative; hiding is only a presentation choice. */
fun SharedPreferences.shouldAvoidTwitchAds(): Boolean = getBoolean(C.PLAYER_AVOID_ADS, true)

fun Activity.applyTheme() {
    // On Android 15, wrong language is used when multiple languages are set in device settings
    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        val lang = AppCompatDelegate.getApplicationLocales()
        resources.configuration.setLocale(
            if (!lang.isEmpty) {
                Locale.forLanguageTag(lang.toLanguageTags())
            } else {
                Locale.getDefault()
            }
        )
    }
    val themeMode = prefs().getString(C.SETTINGS_THEME_MODE, "system") ?: "system"
    val resolvedMode = if (themeMode == "system") {
        if ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) {
            "dark"
        } else {
            "light"
        }
    } else themeMode
    val deviceColors = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        prefs().getBoolean(C.SETTINGS_DEVICE_COLORS, false)
    val compact = prefs().getString(C.SETTINGS_DENSITY, "comfortable") == "compact"
    val noCorners = prefs().getString(C.UI_THEME_ROUNDED_CORNERS, "0") == "2"
    val smallCorners = prefs().getString(C.UI_THEME_ROUNDED_CORNERS, "0") == "1"
    val style = when (resolvedMode) {
        "light" -> when {
            smallCorners && compact -> R.style.LightThemeSmallCornersReducedPaddingCompactText
            smallCorners -> R.style.LightThemeSmallCorners
            noCorners && compact -> R.style.LightThemeNoCornersReducedPaddingCompactText
            noCorners -> R.style.LightThemeNoCorners
            compact -> R.style.LightThemeReducedPaddingCompactText
            else -> R.style.LightTheme
        }
        "amoled" -> when {
            smallCorners && compact -> R.style.AmoledThemeSmallCornersReducedPaddingCompactText
            smallCorners -> R.style.AmoledThemeSmallCorners
            noCorners && compact -> R.style.AmoledThemeNoCornersReducedPaddingCompactText
            noCorners -> R.style.AmoledThemeNoCorners
            compact -> R.style.AmoledThemeReducedPaddingCompactText
            else -> R.style.AmoledTheme
        }
        else -> when {
            smallCorners && compact -> R.style.DarkThemeSmallCornersReducedPaddingCompactText
            smallCorners -> R.style.DarkThemeSmallCorners
            noCorners && compact -> R.style.DarkThemeNoCornersReducedPaddingCompactText
            noCorners -> R.style.DarkThemeNoCorners
            compact -> R.style.DarkThemeReducedPaddingCompactText
            else -> R.style.DarkTheme
        }
    }
    setTheme(style)
    if (deviceColors && resolvedMode in setOf("light", "dark", "amoled")) {
        DynamicColors.applyToActivityIfAvailable(
            this,
            DynamicColorsOptions.Builder().setThemeOverlay(
                when (resolvedMode) {
                    "light" -> R.style.LightDynamicOverlay
                    else -> if (resolvedMode == "amoled") R.style.AmoledDynamicOverlay else R.style.DarkDynamicOverlay
                }
            ).build()
        )
    }
    val isLightTheme = obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.isLightTheme)).use {
        it.getBoolean(0, false)
    }
    WindowInsetsControllerCompat(window, window.decorView).run {
        isAppearanceLightStatusBars = isLightTheme
        isAppearanceLightNavigationBars = isLightTheme
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }
}

fun Context.getAlertDialogBuilder(): AlertDialog.Builder {
    return MaterialAlertDialogBuilder(this)
}
