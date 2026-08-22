package com.github.andreyasadchy.xtra.repository.preload

import android.content.Context
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.httpProxyHost
import com.github.andreyasadchy.xtra.util.httpProxyPort
import com.github.andreyasadchy.xtra.util.prefs
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
    val enableIntegrity: Boolean,
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
            append(enableIntegrity).append('\u0000')
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
                enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false),
                lowLatency = prefs.getBoolean(C.PLAYER_LOW_LATENCY, C.DEFAULT_PLAYER_LOW_LATENCY),
                proxyMultivariantPlaylist = prefs.getBoolean(C.PROXY_MULTIVARIANT_PLAYLIST, false),
                streamHeaders = prefs.getString(C.PLAYER_STREAM_HEADERS, null).parseHeaders(),
                customStreamProxyEnabled = prefs.getBoolean(C.PLAYER_STREAM_PROXY, false),
                customStreamProxyUrl = prefs.getString(C.PLAYER_PROXY_URL, null),
            )
        }
    }
}

private fun String?.parseHeaders(): Map<String, String> = runCatching {
    if (isNullOrBlank()) return emptyMap()
    val json = org.json.JSONObject(this)
    buildMap {
        json.keys().forEach { key -> put(key, json.optString(key)) }
    }
}.getOrDefault(emptyMap())
