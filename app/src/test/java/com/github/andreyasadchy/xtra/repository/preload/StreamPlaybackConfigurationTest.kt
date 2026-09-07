package com.github.andreyasadchy.xtra.repository.preload

import android.content.SharedPreferences
import com.github.andreyasadchy.xtra.util.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArraySet

class StreamPlaybackConfigurationTest {
    @Test
    fun lowLatencyIsPartOfThePreloadConfigurationGeneration() {
        assertNotEquals(configuration(lowLatency = false).fingerprint, configuration(lowLatency = true).fingerprint)
    }

    @Test
    fun geckoIdentityPreferencesRefreshTheCachedConfiguration() {
        val playbackPreferences = ObservablePreferences()
        val tokenPreferences = ObservablePreferences()
        val fallback = configuration(gqlHeaders = mapOf(C.HEADER_CLIENT_ID to "fallback"))
        val store = StreamPlaybackConfigurationStore(
            playbackPreferences = playbackPreferences,
            tokenPreferences = tokenPreferences,
            initialConfiguration = fallback,
            loadConfiguration = { configurationFor(tokenPreferences, fallback) },
        )

        putGeckoIdentity(tokenPreferences, integrity = "integrity-a")
        assertEquals("integrity-a", store.current.gqlHeaders["Client-Integrity"])
        val acquiredRevision = store.currentState.revision

        tokenPreferences.edit().putString(C.GECKO_GQL_CLIENT_INTEGRITY, "integrity-b").commit()
        assertEquals("integrity-b", store.current.gqlHeaders["Client-Integrity"])
        assertNotEquals(acquiredRevision, store.currentState.revision)

        listOf(
            C.GECKO_GQL_AUTHORIZATION,
            C.GECKO_GQL_CLIENT_ID,
            C.GECKO_GQL_CLIENT_INTEGRITY,
            C.GECKO_GQL_X_DEVICE_ID,
            C.GECKO_GQL_USER_ID,
            C.GECKO_GQL_AUTH_TOKEN_FINGERPRINT,
        ).forEach { tokenPreferences.edit().remove(it).commit() }
        assertEquals(fallback.gqlHeaders, store.current.gqlHeaders)
        assertNotEquals("integrity-b", store.current.gqlHeaders["Client-Integrity"])
    }

    private fun configurationFor(
        tokenPreferences: SharedPreferences,
        fallback: StreamPlaybackConfiguration,
    ): StreamPlaybackConfiguration {
        val required = listOf(
            C.GECKO_GQL_AUTHORIZATION,
            C.GECKO_GQL_CLIENT_ID,
            C.GECKO_GQL_CLIENT_INTEGRITY,
            C.GECKO_GQL_X_DEVICE_ID,
            C.GECKO_GQL_USER_ID,
            C.GECKO_GQL_AUTH_TOKEN_FINGERPRINT,
        )
        if (required.any { tokenPreferences.getString(it, null).isNullOrBlank() }) return fallback
        return fallback.copy(
            gqlHeaders = buildMap {
                put(C.HEADER_TOKEN, tokenPreferences.getString(C.GECKO_GQL_AUTHORIZATION, null)!!)
                put(C.HEADER_CLIENT_ID, tokenPreferences.getString(C.GECKO_GQL_CLIENT_ID, null)!!)
                put("Client-Integrity", tokenPreferences.getString(C.GECKO_GQL_CLIENT_INTEGRITY, null)!!)
                put("X-Device-Id", tokenPreferences.getString(C.GECKO_GQL_X_DEVICE_ID, null)!!)
            },
        )
    }

    private fun putGeckoIdentity(preferences: SharedPreferences, integrity: String) {
        preferences.edit()
            .putString(C.GECKO_GQL_AUTHORIZATION, "OAuth token")
            .putString(C.GECKO_GQL_CLIENT_ID, "gecko-client")
            .putString(C.GECKO_GQL_CLIENT_INTEGRITY, integrity)
            .putString(C.GECKO_GQL_X_DEVICE_ID, "gecko-device")
            .putString(C.GECKO_GQL_USER_ID, "user")
            .putString(C.GECKO_GQL_AUTH_TOKEN_FINGERPRINT, "fingerprint")
            .commit()
    }

    private fun configuration(
        lowLatency: Boolean = false,
        gqlHeaders: Map<String, String> = emptyMap(),
    ) = StreamPlaybackConfiguration(
        networkLibrary = "okhttp",
        gqlHeaders = gqlHeaders,
        randomDeviceId = true,
        xDeviceId = "device",
        playerType = "site",
        supportedCodecs = "h264",
        proxyPlaybackAccessToken = false,
        proxyHost = null,
        proxyPort = null,
        proxyUser = null,
        proxyPassword = null,
        lowLatency = lowLatency,
        proxyMultivariantPlaylist = false,
        streamHeaders = emptyMap(),
        customStreamProxyEnabled = false,
        customStreamProxyUrl = null,
    )
}

private class ObservablePreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        listener?.let(listeners::add)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        listener?.let(listeners::remove)
    }

    private inner class Editor : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            put(key, values?.toMutableSet())
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) changes[key] = null
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() = applyChanges()

        private fun <T> put(key: String?, value: T): SharedPreferences.Editor {
            if (key != null) changes[key] = value
            return this
        }

        private fun applyChanges() {
            val clearedKeys = if (clear) values.keys.toSet() else emptySet()
            if (clear) values.clear()
            changes.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            listeners.forEach { listener ->
                (clearedKeys + changes.keys).forEach { key ->
                    listener.onSharedPreferenceChanged(this@ObservablePreferences, key)
                }
            }
        }
    }
}
