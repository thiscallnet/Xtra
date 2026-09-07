package com.github.andreyasadchy.xtra.repository.preload

import android.content.Context
import android.content.SharedPreferences
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.httpProxyHost
import com.github.andreyasadchy.xtra.util.httpProxyPort
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import java.security.MessageDigest

/** Immutable network/playback inputs shared by URL resolution and Media3 sources. */
data class StreamPlaybackConfiguration(
    val networkLibrary: String?,
    val gqlHeaders: Map<String, String>,
    val randomDeviceId: Boolean?,
    val xDeviceId: String?,
    val playerType: String?,
    val supportedCodecs: String?,
    val proxyPlaybackAccessToken: Boolean,
    val proxyHost: String?,
    val proxyPort: Int?,
    val proxyUser: String?,
    val proxyPassword: String?,
    val lowLatency: Boolean,
    val proxyMultivariantPlaylist: Boolean,
    val streamHeaders: Map<String, String>,
    val customStreamProxyEnabled: Boolean,
    val customStreamProxyUrl: String?,
) {
    val fingerprint: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = buildString {
            append(networkLibrary).append('\u0000')
            gqlHeaders.toSortedMap().forEach { (key, value) -> append(key).append('=').append(value).append('\u0000') }
            append(randomDeviceId).append('\u0000')
            append(xDeviceId).append('\u0000')
            append(playerType).append('\u0000')
            append(supportedCodecs).append('\u0000')
            append(proxyPlaybackAccessToken).append('\u0000')
            append(proxyHost).append('\u0000').append(proxyPort).append('\u0000')
            append(proxyUser).append('\u0000').append(proxyPassword).append('\u0000')
            append(lowLatency).append('\u0000')
            append(proxyMultivariantPlaylist).append('\u0000')
            streamHeaders.toSortedMap().forEach { (key, value) -> append(key).append('=').append(value).append('\u0000') }
            append(customStreamProxyEnabled).append('\u0000').append(customStreamProxyUrl)
        }
        digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun from(context: Context): StreamPlaybackConfiguration {
            val appContext = context.applicationContext
            val prefs = appContext.prefs()
            return StreamPlaybackConfiguration(
                networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                gqlHeaders = TwitchApiHelper.getGQLHeaders(
                    appContext,
                    prefs.getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true),
                ),
                randomDeviceId = prefs.getBoolean(C.TOKEN_RANDOM_DEVICE_ID, true),
                xDeviceId = prefs.getString(C.TOKEN_X_DEVICE_ID, C.DEFAULT_TOKEN_X_DEVICE_ID),
                playerType = prefs.getString(C.TOKEN_PLAYER_TYPE, C.DEFAULT_TOKEN_PLAYER_TYPE),
                supportedCodecs = prefs.getString(C.TOKEN_SUPPORTED_CODECS, C.DEFAULT_TOKEN_SUPPORTED_CODECS),
                proxyPlaybackAccessToken = prefs.getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false),
                proxyHost = prefs.httpProxyHost(),
                proxyPort = prefs.httpProxyPort(),
                proxyUser = prefs.getString(C.PROXY_USER, null),
                proxyPassword = prefs.getString(C.PROXY_PASSWORD, null),
                lowLatency = prefs.getBoolean(C.PLAYER_LOW_LATENCY, C.DEFAULT_PLAYER_LOW_LATENCY),
                proxyMultivariantPlaylist = prefs.getBoolean(C.PROXY_MULTIVARIANT_PLAYLIST, false),
                streamHeaders = prefs.getString(C.PLAYER_STREAM_HEADERS, null).parseHeaders(),
                customStreamProxyEnabled = prefs.getBoolean(C.PLAYER_STREAM_PROXY, false),
                customStreamProxyUrl = prefs.getString(C.PLAYER_PROXY_URL, null),
            )
        }
    }
}

data class StreamPlaybackConfigurationState(
    val revision: Long,
    val configuration: StreamPlaybackConfiguration,
)

/**
 * Process-owned configuration snapshot. Preference callbacks rebuild it only when an input
 * changes; preload and playback hot paths read the already parsed immutable value.
 */
class StreamPlaybackConfigurationStore private constructor(
    private val playbackPreferences: SharedPreferences,
    private val tokenPreferences: SharedPreferences,
    private val configurationLoader: () -> StreamPlaybackConfiguration,
    initialConfiguration: StreamPlaybackConfiguration,
) {
    constructor(context: Context) : this(
        playbackPreferences = context.applicationContext.prefs(),
        tokenPreferences = context.applicationContext.tokenPrefs(),
        configurationLoader = { StreamPlaybackConfiguration.from(context.applicationContext) },
        initialConfiguration = StreamPlaybackConfiguration.from(context.applicationContext),
    )

    internal constructor(
        playbackPreferences: SharedPreferences,
        tokenPreferences: SharedPreferences,
        initialConfiguration: StreamPlaybackConfiguration,
        loadConfiguration: () -> StreamPlaybackConfiguration,
    ) : this(playbackPreferences, tokenPreferences, loadConfiguration, initialConfiguration)

    @Volatile
    private var state = StreamPlaybackConfigurationState(
        revision = 0L,
        configuration = initialConfiguration,
    )

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in CONFIGURATION_KEYS) refresh()
    }

    init {
        playbackPreferences.registerOnSharedPreferenceChangeListener(listener)
        tokenPreferences.registerOnSharedPreferenceChangeListener(listener)
    }

    val current: StreamPlaybackConfiguration
        get() = state.configuration

    val currentState: StreamPlaybackConfigurationState
        get() = state

    @Synchronized
    fun refresh(): StreamPlaybackConfigurationState {
        val next = configurationLoader()
        val previous = state
        if (next != previous.configuration) {
            state = StreamPlaybackConfigurationState(previous.revision + 1L, next)
        }
        return state
    }

    private companion object {
        val CONFIGURATION_KEYS = setOf(
            C.NETWORK_LIBRARY,
            C.PLAYER_STREAM_HEADERS,
            C.PLAYER_STREAM_PROXY,
            C.PLAYER_PROXY_URL,
            C.PROXY_PLAYBACK_ACCESS_TOKEN,
            C.PROXY_MULTIVARIANT_PLAYLIST,
            C.PROXY_HOST,
            C.PROXY_PORT,
            C.PROXY_USER,
            C.PROXY_PASSWORD,
            C.PLAYER_LOW_LATENCY,
            C.TOKEN_INCLUDE_TOKEN_STREAM,
            C.TOKEN_RANDOM_DEVICE_ID,
            C.TOKEN_X_DEVICE_ID,
            C.TOKEN_PLAYER_TYPE,
            C.TOKEN_SUPPORTED_CODECS,
            C.GQL_TOKEN_WEB,
            C.TWITCH_WEB_COOKIE_HEADER,
            C.GQL_CLIENT_ID_WEB,
            C.GECKO_GQL_AUTHORIZATION,
            C.GECKO_GQL_CLIENT_ID,
            C.GECKO_GQL_CLIENT_INTEGRITY,
            C.GECKO_GQL_X_DEVICE_ID,
            C.GECKO_GQL_CLIENT_SESSION_ID,
            C.GECKO_GQL_CLIENT_VERSION,
            C.GECKO_GQL_USER_ID,
            C.GECKO_GQL_AUTH_TOKEN_FINGERPRINT,
            C.GECKO_GQL_CAPTURED_AT,
        )
    }
}

private fun String?.parseHeaders(): Map<String, String> = runCatching {
    if (isNullOrBlank()) return emptyMap()
    val json = org.json.JSONObject(this)
    buildMap {
        json.keys().forEach { key -> put(key, json.optString(key)) }
    }
}.getOrDefault(emptyMap())
