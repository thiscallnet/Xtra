package com.github.andreyasadchy.xtra.util

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.use
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import com.github.andreyasadchy.xtra.R
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.WeakHashMap

private val preferencesCache = WeakHashMap<Context, PreferenceSet>()

private data class PreferenceSet(
    val raw: SharedPreferences,
    val token: SharedPreferences,
    val proxy: SharedPreferences,
    val combined: SharedPreferences,
)

private fun Context.preferenceSet(): PreferenceSet {
    val context = applicationContext ?: this
    return synchronized(preferencesCache) {
        preferencesCache.getOrPut(context) {
            val raw = PreferenceManager.getDefaultSharedPreferences(context)
            val token = KeystorePreferences(context.getSharedPreferences("prefs2", Context.MODE_PRIVATE), "xtra-token-prefs")
            val proxy = KeystorePreferences(context.getSharedPreferences("proxy_credentials", Context.MODE_PRIVATE), "xtra-proxy-prefs")
            PreferenceSet(raw, token, proxy, DeveloperGatedPreferences(ProxyAwarePreferences(raw, proxy)))
        }
    }
}

fun Context.rawPrefs(): SharedPreferences = preferenceSet().raw

fun Context.prefs(): SharedPreferences = preferenceSet().combined

fun Context.tokenPrefs(): SharedPreferences = preferenceSet().token

fun Context.proxyPrefs(): SharedPreferences = preferenceSet().proxy

private class ProxyAwarePreferences(
    private val delegate: SharedPreferences,
    private val proxyPreferences: SharedPreferences,
) : SharedPreferences {

    private fun preferencesFor(key: String?): SharedPreferences =
        if (key in PROXY_KEYS) proxyPreferences else delegate

    override fun getAll(): MutableMap<String, *> = delegate.all.toMutableMap().apply {
        putAll(proxyPreferences.all)
    }

    override fun getString(key: String?, defValue: String?): String? = preferencesFor(key).getString(key, defValue)

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        preferencesFor(key).getStringSet(key, defValues)

    override fun getInt(key: String?, defValue: Int): Int = preferencesFor(key).getInt(key, defValue)

    override fun getLong(key: String?, defValue: Long): Long = preferencesFor(key).getLong(key, defValue)

    override fun getFloat(key: String?, defValue: Float): Float = preferencesFor(key).getFloat(key, defValue)

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = preferencesFor(key).getBoolean(key, defValue)

    override fun contains(key: String?): Boolean = preferencesFor(key).contains(key)

    override fun edit(): SharedPreferences.Editor = ProxyAwareEditor(delegate.edit(), proxyPreferences.edit())

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        delegate.registerOnSharedPreferenceChangeListener(listener)
        proxyPreferences.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        delegate.unregisterOnSharedPreferenceChangeListener(listener)
        proxyPreferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private class ProxyAwareEditor(
        private val delegate: SharedPreferences.Editor,
        private val proxy: SharedPreferences.Editor,
    ) : SharedPreferences.Editor {

        private fun editorFor(key: String?): SharedPreferences.Editor =
            if (key in PROXY_KEYS) proxy else delegate

        override fun putString(key: String?, value: String?): SharedPreferences.Editor =
            editorFor(key).putString(key, value).let { this }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            editorFor(key).putStringSet(key, values).let { this }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
            editorFor(key).putInt(key, value).let { this }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
            editorFor(key).putLong(key, value).let { this }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
            editorFor(key).putFloat(key, value).let { this }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
            editorFor(key).putBoolean(key, value).let { this }

        override fun remove(key: String?): SharedPreferences.Editor =
            editorFor(key).remove(key).let { this }

        override fun clear(): SharedPreferences.Editor {
            delegate.clear()
            proxy.clear()
            return this
        }

        override fun commit(): Boolean = delegate.commit() && proxy.commit()

        override fun apply() {
            delegate.apply()
            proxy.apply()
        }
    }

    private companion object {
        val PROXY_KEYS = setOf(C.PROXY_HOST, C.PROXY_PORT, C.PROXY_USER, C.PROXY_PASSWORD)
    }
}

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
    if (key == C.GQL_CLIENT_ID_WEB) return C.DEFAULT_GQL_CLIENT_ID_WEB
    if (enabled) {
        return if (key == C.NETWORK_LIBRARY && storedValue == C.AUTOMATIC) C.OKHTTP else storedValue ?: defaultValue
    }
    return when (key) {
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
        C.DEBUG_CHAT_FULL_MSG,
        C.DEBUG_WEBSOCKET_INFO,
        C.DEBUG_EVENT_SUB_CHAT,
        C.DEBUG_PLAYER_MENU_PLAYLIST_TAGS,
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
    val fontFamily = prefs().getString(C.SETTINGS_FONT_FAMILY, C.FONT_SYSTEM) ?: C.FONT_SYSTEM
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
    if (fontFamily != C.FONT_SYSTEM) {
        when (fontFamily) {
            C.FONT_SANS_SERIF -> R.style.AppFontFamilySansSerif
            C.FONT_SANS_SERIF_CONDENSED -> R.style.AppFontFamilySansSerifCondensed
            C.FONT_SERIF -> R.style.AppFontFamilySerif
            C.FONT_MONOSPACE -> R.style.AppFontFamilyMonospace
            else -> null
        }?.let { fontStyle ->
            theme.applyStyle(fontStyle, true)
            theme.applyStyle(
                if (compact) R.style.AppFontCompactAppearance else R.style.AppFontAppearance,
                true,
            )
        }
    }
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
