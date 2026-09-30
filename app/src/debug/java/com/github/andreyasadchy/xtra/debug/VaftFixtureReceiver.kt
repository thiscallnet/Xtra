package com.github.andreyasadchy.xtra.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer

/** Redirects only the reserved fixture channel through the real repository and player. */
class VaftFixtureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val lazyClient = (context.applicationContext as XtraApp).xtraModule.okHttpClient
        val preferences = context.prefs()
        val backup = context.getSharedPreferences("vaft_fixture_preferences", Context.MODE_PRIVATE)
        if (intent.getBooleanExtra("disable", false)) {
            originalClient?.let { setLazyClient(lazyClient, it) }
            if (backup.getBoolean("active", false)) {
                preferences.edit().apply {
                    KEYS.forEach { key ->
                        val value = backup.all[key]
                        when (value) {
                            null -> remove(key)
                            is Boolean -> putBoolean(key, value)
                            is String -> putString(key, value)
                        }
                    }
                }.commit()
                backup.edit().clear().commit()
            }
            originalClient = null
            Log.i("VaftFixture", "disabled; client and preferences restored")
            return
        }
        if (originalClient != null) return
        val host = (intent.getStringExtra("host") ?: "http://10.0.2.2:8767").toHttpUrl()
        require(host.host in listOf("10.0.2.2", "127.0.0.1", "localhost"))
        val client = lazyClient.value
        originalClient = client
        if (!backup.getBoolean("active", false)) {
            backup.edit().apply {
                KEYS.forEach { key ->
                    when (val value = preferences.all[key]) {
                        is Boolean -> putBoolean(key, value)
                        is String -> putString(key, value)
                    }
                }
                putBoolean("active", true)
            }.commit()
        }
        val fixtureClient = client.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val url = request.url
            val body = request.body?.let { Buffer().apply { it.writeTo(this) }.readUtf8() }.orEmpty()
            val path = when {
                url.host == "vaft-fixture.invalid" -> url.encodedPath
                url.host == "usher.ttvnw.net" && url.encodedPath.endsWith("/vaft_fixture.m3u8") -> "/master"
                url.host == "usher.ttvnw.net" && url.encodedPath.endsWith("/999999999999.m3u8") -> "/vod/master"
                url.host == "gql.twitch.tv" && (body.contains("vaft_fixture") || body.contains("999999999999")) -> "/gql"
                else -> null
            }
            if (path == null) chain.proceed(request) else {
                val target = host.newBuilder().encodedPath(path).encodedQuery(url.encodedQuery).build()
                // Local fixtures receive no account headers or cookies.
                chain.proceed(request.newBuilder().url(target).headers(okhttp3.Headers.Builder().build())
                    .apply { request.header("Range")?.let { header("Range", it) } }
                    .header("Content-Type", "application/json").build())
                    .newBuilder().request(request).build()
            }
        }.build()
        setLazyClient(lazyClient, fixtureClient)
        preferences.edit().putString(C.NETWORK_LIBRARY, C.OKHTTP)
            .putString(C.TOKEN_PLAYER_TYPE, "site").putBoolean(C.PLAYER_AVOID_ADS, true)
            .putBoolean(C.PLAYER_LIVE_REWIND, true).putBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false)
            .putBoolean(C.PLAYER_STREAM_PROXY, false).putBoolean(C.SETTINGS_CHAT_ENABLED, false).commit()
        Log.i("VaftFixture", "enabled; only vaft_fixture traffic redirected")
    }

    companion object {
        private var originalClient: OkHttpClient? = null
        private val KEYS = listOf(C.NETWORK_LIBRARY, C.TOKEN_PLAYER_TYPE, C.PLAYER_AVOID_ADS,
            C.PLAYER_LIVE_REWIND, C.PROXY_PLAYBACK_ACCESS_TOKEN, C.PLAYER_STREAM_PROXY, C.SETTINGS_CHAT_ENABLED)

        // Debug-only test injection into the shared lazy client also reaches existing repositories.
        // Production source resolution and playback contain no fixture branches.
        private fun setLazyClient(client: Lazy<OkHttpClient>, value: OkHttpClient) {
            client.javaClass.getDeclaredField("_value").apply { isAccessible = true }.set(client, value)
        }
    }
}
