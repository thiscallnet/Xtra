package com.github.andreyasadchy.xtra.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.HttpEngine
import android.net.http.ProxyOptions
import android.os.Build
import android.util.Base64
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.github.andreyasadchy.xtra.XtraModule
import com.github.andreyasadchy.xtra.player.lowlatency.CronetDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.HttpEngineDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.OkHttpDataSource
import com.github.andreyasadchy.xtra.repository.preload.StreamPlaybackConfiguration
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils.proxyCandidates
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.ui.player.ExoPlayerService.Companion.MEDIA_PLAYLIST_REGEX
import com.github.andreyasadchy.xtra.ui.player.ExoPlayerService.Companion.MULTIVARIANT_PLAYLIST_REGEX
import okhttp3.Credentials
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import org.chromium.net.QuicOptions
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Per-source state used by the live playlist proxy callback. */
class StreamProxyState {
    @Volatile
    var proxyMediaPlaylist: Boolean = false
}

/**
 * The single HLS source factory for live Twitch playback, media preloading, and previews.
 * A new DataSource.Factory is made for each MediaItem so its proxy state is not shared by
 * recycled cards or unrelated playback sessions.
 */
class StreamHlsMediaSourceFactory(
    private val context: Context,
    private val xtraModule: XtraModule,
    private val configuration: StreamPlaybackConfiguration,
    private val proxyStates: MutableMap<String, StreamProxyState> = ConcurrentHashMap(),
) : MediaSource.Factory {

    private var drmSessionManagerProvider: DrmSessionManagerProvider? = null
    private var loadErrorHandlingPolicy: LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(6)
    private val defaultMediaSourceFactory = DefaultMediaSourceFactory(
        DefaultDataSource.Factory(context, dataSourceFactory(StreamProxyState()))
    )

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        if (!StreamMediaSourceRouting.isHls(
                mimeType = mediaItem.localConfiguration?.mimeType,
                uriPath = mediaItem.localConfiguration?.uri?.path,
            )
        ) {
            return defaultMediaSourceFactory.createMediaSource(mediaItem)
        }
        val state = proxyStates.getOrPut(mediaItem.mediaId) { StreamProxyState() }
        return HlsMediaSource.Factory(DefaultDataSource.Factory(context, dataSourceFactory(state))).apply {
            setPlaylistParserFactory(ExoPlayerService.CustomHlsPlaylistParserFactory())
            setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            drmSessionManagerProvider?.let(::setDrmSessionManagerProvider)
        }.createMediaSource(mediaItem)
    }

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        drmSessionManagerProvider = provider
        defaultMediaSourceFactory.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        loadErrorHandlingPolicy = policy
        defaultMediaSourceFactory.setLoadErrorHandlingPolicy(policy)
        return this
    }

    override fun getSupportedTypes(): IntArray =
        (HlsMediaSource.Factory(DefaultDataSource.Factory(context, dataSourceFactory(StreamProxyState())))
            .getSupportedTypes() + defaultMediaSourceFactory.getSupportedTypes()).distinct().toIntArray()

    fun createLiveMediaItem(
        mediaId: String,
        uri: String,
        title: String?,
        channelName: String?,
        channelLogo: String?,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(uri.toUri())
        .setMimeType(MimeTypes.APPLICATION_M3U8)
        .setLiveConfiguration(
            MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(
                    if (configuration.lowLatency) C.LOW_LATENCY_TARGET_OFFSET_MS
                    else C.NORMAL_LATENCY_TARGET_OFFSET_MS
                )
                .build()
        )
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title ?: channelName)
                .setArtist(channelName)
                .setArtworkUri(channelLogo?.toUri())
                .build()
        )
        .build()

    fun createVodMediaItem(
        mediaId: String,
        uri: String,
        title: String?,
        channelName: String?,
        channelLogo: String?,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(uri.toUri())
        .setMimeType(MimeTypes.APPLICATION_M3U8)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title ?: channelName)
                .setArtist(channelName)
                .setArtworkUri(channelLogo?.toUri())
                .build()
        )
        .build()

    fun stateFor(mediaId: String): StreamProxyState = proxyStates.getOrPut(mediaId) { StreamProxyState() }

    fun findState(mediaId: String): StreamProxyState? = proxyStates[mediaId]

    private fun dataSourceFactory(state: StreamProxyState): DataSource.Factory {
        val proxyHost = configuration.proxyHost
        val proxyPort = configuration.proxyPort
        val proxyUser = configuration.proxyUser
        val proxyPassword = configuration.proxyPassword
        val proxyMultivariantPlaylist = configuration.proxyMultivariantPlaylist &&
            !proxyHost.isNullOrBlank() && proxyPort != null
        val proxyMediaPlaylist = !proxyHost.isNullOrBlank() && proxyPort != null

        val upstreamFactory: DataSource.Factory = when {
            configuration.networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null ->
                createHttpEngineFactory(state, proxyHost, proxyPort, proxyUser, proxyPassword, proxyMultivariantPlaylist, proxyMediaPlaylist)
            configuration.networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null ->
                createCronetFactory(state, proxyHost, proxyPort, proxyUser, proxyPassword, proxyMultivariantPlaylist, proxyMediaPlaylist)
            else -> createOkHttpFactory(state, proxyHost, proxyPort, proxyUser, proxyPassword, proxyMultivariantPlaylist, proxyMediaPlaylist)
        }
        return upstreamFactory.apply {
            if (configuration.streamHeaders.isNotEmpty()) {
                when (this) {
                    is HttpEngineDataSource.Factory -> setDefaultRequestProperties(configuration.streamHeaders)
                    is CronetDataSource.Factory -> setDefaultRequestProperties(configuration.streamHeaders)
                    is OkHttpDataSource.Factory -> setDefaultRequestProperties(configuration.streamHeaders)
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private fun createHttpEngineFactory(
        state: StreamProxyState,
        proxyHost: String?,
        proxyPort: Int?,
        proxyUser: String?,
        proxyPassword: String?,
        proxyMultivariantPlaylist: Boolean,
        proxyMediaPlaylist: Boolean,
    ): DataSource.Factory {
        val host = proxyHost.orEmpty()
        val port = proxyPort ?: 0
        val proxyClient = if (proxyMultivariantPlaylist || proxyMediaPlaylist) {
            val proxyHeaders = if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                listOf(android.util.Pair("Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)))
            } else emptyList()
            val builder = HttpEngine.Builder(context)
            try {
                builder.setProxyOptions(
                    ProxyOptions.fromProxyList(
                        listOf(
                            android.net.http.Proxy.createHttpProxy(
                                android.net.http.Proxy.SCHEME_HTTP,
                                host,
                                port,
                                xtraModule.cronetExecutor.value,
                                object : android.net.http.Proxy.HttpConnectCallback {
                                    override fun onBeforeRequest(request: android.net.http.Proxy.HttpConnectCallback.Request) {
                                        request.proceed(proxyHeaders)
                                    }

                                    override fun onResponseReceived(responseHeaders: List<android.util.Pair<String?, String?>?>, statusCode: Int): Int =
                                        android.net.http.Proxy.HttpConnectCallback.RESPONSE_ACTION_PROCEED
                                },
                            )
                        ),
                        ProxyOptions.ALL_PROXIES_FAILED_BEHAVIOR_DISALLOW_DIRECT,
                    )
                )
            } catch (_: NoClassDefFoundError) {
                null
            }?.build()
        } else null
        val multivariantProxy = if (proxyMultivariantPlaylist && proxyClient == null) {
            proxyOkHttpClient(host, port, proxyUser, proxyPassword, MULTIVARIANT_PLAYLIST_REGEX)
        } else null
        val mediaProxy = if (proxyMediaPlaylist && proxyClient == null) {
            proxyOkHttpClient(host, port, proxyUser, proxyPassword, MEDIA_PLAYLIST_REGEX)
        } else null
        return HttpEngineDataSource.Factory(
            xtraModule.httpEngine.value,
            xtraModule.cronetExecutor.value,
            proxyMultivariantPlaylist,
            proxyMediaPlaylist,
            proxyClient,
            multivariantProxy,
            mediaProxy,
        ) { state.proxyMediaPlaylist }
    }

    private fun createCronetFactory(
        state: StreamProxyState,
        proxyHost: String?,
        proxyPort: Int?,
        proxyUser: String?,
        proxyPassword: String?,
        proxyMultivariantPlaylist: Boolean,
        proxyMediaPlaylist: Boolean,
    ): DataSource.Factory {
        val host = proxyHost.orEmpty()
        val port = proxyPort ?: 0
        val proxyClient = if ((proxyMultivariantPlaylist || proxyMediaPlaylist) &&
            CronetProvider.getAllProviders(context).any { it.isEnabled }
        ) {
            val proxyHeaders = if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                mapOf("Proxy-Authorization" to Credentials.basic(proxyUser, proxyPassword)).entries.toList()
            } else emptyList()
            val builder = CronetEngine.Builder(context).apply {
                val userAgent = "Cronet/" + androidx.media3.common.util.Util.getUserAgent(context, "Xtra")
                    .substringAfter("Cronet/", "").substringBefore(')')
                setUserAgent(userAgent)
                @Suppress("DEPRECATION")
                setQuicOptions(QuicOptions.builder().setHandshakeUserAgent(userAgent).build())
            }
            try {
                @Suppress("DEPRECATION")
                builder.setProxyOptions(
                    org.chromium.net.ProxyOptions(
                        listOf(
                            org.chromium.net.Proxy(
                                org.chromium.net.Proxy.HTTP,
                                host,
                                port,
                                xtraModule.cronetExecutor.value,
                                object : org.chromium.net.Proxy.Callback() {
                                    override fun onBeforeTunnelRequest(request: org.chromium.net.Proxy.Callback.Request) {
                                        request.proceed(proxyHeaders)
                                    }

                                    override fun onTunnelHeadersReceived(responseHeaders: List<Map.Entry<String?, String?>?>, statusCode: Int): Boolean = true
                                },
                            )
                        )
                    )
                )
            } catch (_: UnsupportedOperationException) {
                null
            }?.build()
        } else null
        val multivariantProxy = if (proxyMultivariantPlaylist && proxyClient == null) {
            proxyOkHttpClient(host, port, proxyUser, proxyPassword, MULTIVARIANT_PLAYLIST_REGEX)
        } else null
        val mediaProxy = if (proxyMediaPlaylist && proxyClient == null) {
            proxyOkHttpClient(host, port, proxyUser, proxyPassword, MEDIA_PLAYLIST_REGEX)
        } else null
        return CronetDataSource.Factory(
            xtraModule.cronetEngine.value,
            xtraModule.cronetExecutor.value,
            proxyMultivariantPlaylist,
            proxyMediaPlaylist,
            proxyClient,
            multivariantProxy,
            mediaProxy,
        ) { state.proxyMediaPlaylist }
    }

    private fun createOkHttpFactory(
        state: StreamProxyState,
        proxyHost: String?,
        proxyPort: Int?,
        proxyUser: String?,
        proxyPassword: String?,
        proxyMultivariantPlaylist: Boolean,
        proxyMediaPlaylist: Boolean,
    ): DataSource.Factory {
        val host = proxyHost.orEmpty()
        val port = proxyPort ?: 0
        val multivariantProxy = if (proxyMultivariantPlaylist) {
            proxyOkHttpClient(host, port, proxyUser, proxyPassword, MULTIVARIANT_PLAYLIST_REGEX)
        } else null
        val mediaProxy = if (proxyMediaPlaylist) {
            proxyOkHttpClient(host, port, proxyUser, proxyPassword, MEDIA_PLAYLIST_REGEX)
        } else null
        return OkHttpDataSource.Factory(
            multivariantProxy ?: xtraModule.okHttpClient.value,
            mediaProxy,
        ) { state.proxyMediaPlaylist }
    }

    private fun proxyOkHttpClient(
        proxyHost: String?,
        proxyPort: Int?,
        proxyUser: String?,
        proxyPassword: String?,
        hostPattern: String,
    ): okhttp3.Call.Factory? {
        if (proxyHost.isNullOrBlank() || proxyPort == null) return null
        return xtraModule.okHttpClient.value.newBuilder().apply {
            val allowDirectFallback = context.prefs().getBoolean(C.PROXY_ALLOW_DIRECT_FALLBACK, true)
            proxySelector(
                object : ProxySelector() {
                    override fun select(uri: URI): List<Proxy> = if (Regex(hostPattern).matches(uri.host.orEmpty())) {
                        proxyCandidates(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)), allowDirectFallback)
                    } else listOf(Proxy.NO_PROXY)

                    override fun connectFailed(uri: java.net.URI, sa: SocketAddress, e: IOException) = Unit
                }
            )
            if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                proxyAuthenticator { _, response ->
                    response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)).build()
                }
            }
        }.build()
    }
}

internal object StreamMediaSourceRouting {
    fun isHls(mimeType: String?, uriPath: String?): Boolean =
        mimeType == MimeTypes.APPLICATION_M3U8 || uriPath?.endsWith(".m3u8", ignoreCase = true) == true
}
