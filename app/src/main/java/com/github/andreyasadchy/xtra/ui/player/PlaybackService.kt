package com.github.andreyasadchy.xtra.ui.player

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.DynamicsProcessing
import android.net.http.HttpEngine
import android.net.http.ProxyOptions
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.XtraModule
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.model.stats.ViewingPlaybackMetadata
import com.github.andreyasadchy.xtra.model.stats.mergeViewingCategoryPatch
import com.github.andreyasadchy.xtra.player.lowlatency.CronetDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.HttpEngineDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.OkHttpDataSource
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.ExoPlayerService.Companion.MEDIA_PLAYLIST_REGEX
import com.github.andreyasadchy.xtra.ui.player.ExoPlayerService.Companion.MULTIVARIANT_PLAYLIST_REGEX
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.httpProxyHost
import com.github.andreyasadchy.xtra.util.httpProxyPort
import com.github.andreyasadchy.xtra.util.m3u8.TwitchAdDetector
import com.github.andreyasadchy.xtra.util.prefs
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import okhttp3.Credentials
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import org.chromium.net.QuicOptions
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Timer
import kotlinx.coroutines.launch
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    lateinit var xtraModule: XtraModule

    private var mediaSession: MediaSession? = null
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var backgroundPlayback = false
    private var backgroundRecoveryTimer: Timer? = null
    private var backgroundRecoveryAttempt = 0
    private var proxyMediaPlaylist = false
    private var videoId: Long? = null
    private var offlineVideoId: Int? = null
    private var sleepTimer: Timer? = null
    private var sleepTimerEndTime = 0L
    private var lastSavedPosition: Long? = null
    private var savePositionTimer: Timer? = null
    private val viewingStatsSourceId = "playback-service:primary"
    private var viewingChannelId: String? = null
    private var viewingChannelLogin: String? = null
    private var viewingChannelName: String? = null
    private var viewingChannelImage: String? = null
    private var viewingCategoryId: String? = null
    private var viewingCategoryName: String? = null
    private var viewingCategoryImage: String? = null
    private var viewingTitle: String? = null
    private var viewingContentType: String? = null
    private var viewingContentId: String? = null

    override fun onCreate() {
        super.onCreate()
        xtraModule = (application as XtraApp).xtraModule
        val player = ExoPlayer.Builder(this).apply {
            setLoadControl(
                DefaultLoadControl.Builder().apply {
                    setBufferDurationsMs(
                        15000,
                        50000,
                        2000,
                        2000
                    )
                }.build()
            )
            setAudioAttributes(AudioAttributes.DEFAULT, prefs().getBoolean(C.PLAYER_AUDIO_FOCUS, false))
            setHandleAudioBecomingNoisy(true)
            setSeekBackIncrementMs((prefs().getString(C.PLAYER_REWIND, "10")?.toLongOrNull() ?: 10) * 1000)
            setSeekForwardIncrementMs((prefs().getString(C.PLAYER_FORWARD, "10")?.toLongOrNull() ?: 10) * 1000)
        }.build()
        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateViewingStats(player)
                    if (isPlaying) {
                        backgroundRecoveryTimer?.cancel()
                        backgroundRecoveryTimer = null
                        backgroundRecoveryAttempt = 0
                        if (savePositionTimer == null && (videoId != null || offlineVideoId != null)) {
                            savePositionTimer = Timer().apply {
                                scheduleAtFixedRate(30000, 30000) {
                                    Handler(Looper.getMainLooper()).post {
                                        updateSavedPosition()
                                    }
                                }
                            }
                        }
                    } else {
                        savePositionTimer?.cancel()
                        savePositionTimer = null
                        updateSavedPosition()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (backgroundPlayback
                        && prefs().getBoolean(C.PLAYER_AUTO_RECOVER_STREAMS, true)
                        && player.playWhenReady
                    ) {
                        scheduleBackgroundRecovery()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    updateViewingStats(player)
                    if (playbackState == Player.STATE_READY) {
                        backgroundRecoveryTimer?.cancel()
                        backgroundRecoveryTimer = null
                        backgroundRecoveryAttempt = 0
                    }
                }

                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    dynamicsProcessing?.let {
                        it.release()
                        dynamicsProcessing = null
                    }
                    if (prefs().getBoolean(C.PLAYER_AUDIO_COMPRESSOR, false)) {
                        reinitializeDynamicsProcessing(audioSessionId)
                    }
                }
            }
        )
        mediaSession = MediaSession.Builder(
            this,
            object : ForwardingSimpleBasePlayer(player) {
                override fun getState(): State {
                    val state = super.getState()
                    return state
                        .buildUpon()
                        .setAvailableCommands(
                            state.availableCommands.buildUpon()
                                .add(COMMAND_SEEK_TO_NEXT)
                                .add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                                .build()
                        )
                        .build()
                }

                override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
                    return when (seekCommand) {
                        COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                            player.seekForward()
                            Futures.immediateVoidFuture()
                        }
                        COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                            player.seekBack()
                            Futures.immediateVoidFuture()
                        }
                        else -> super.handleSeek(mediaItemIndex, positionMs, seekCommand)
                    }
                }
            }
        ).apply {
            setSessionActivity(
                PendingIntent.getActivity(
                    this@PlaybackService,
                    REQUEST_CODE_RESUME,
                    Intent(this@PlaybackService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                        action = MainActivity.INTENT_OPEN_PLAYER
                    },
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            setCallback(
                object : MediaSession.Callback {
                    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                        val connectionResult = super.onConnect(session, controller)
                        val sessionCommands = connectionResult.availableSessionCommands.buildUpon().apply {
                            add(SessionCommand(START_STREAM, Bundle.EMPTY))
                            add(SessionCommand(UPDATE_VIEWING_METADATA, Bundle.EMPTY))
                            add(SessionCommand(START_VIDEO, Bundle.EMPTY))
                            add(SessionCommand(START_CLIP, Bundle.EMPTY))
                            add(SessionCommand(START_OFFLINE_VIDEO, Bundle.EMPTY))
                            add(SessionCommand(TOGGLE_DYNAMICS_PROCESSING, Bundle.EMPTY))
                            add(SessionCommand(TOGGLE_PROXY, Bundle.EMPTY))
                            add(SessionCommand(SET_BACKGROUND_PLAYBACK, Bundle.EMPTY))
                            add(SessionCommand(SET_SLEEP_TIMER, Bundle.EMPTY))
                            add(SessionCommand(CHECK_ADS, Bundle.EMPTY))
                            add(SessionCommand(GET_QUALITIES, Bundle.EMPTY))
                            add(SessionCommand(GET_DURATION, Bundle.EMPTY))
                            add(SessionCommand(GET_ERROR_CODE, Bundle.EMPTY))
                            add(SessionCommand(GET_MEDIA_PLAYLIST, Bundle.EMPTY))
                            add(SessionCommand(GET_MULTIVARIANT_PLAYLIST, Bundle.EMPTY))
                        }.build()
                        return MediaSession.ConnectionResult.accept(sessionCommands, connectionResult.availablePlayerCommands)
                    }

                    override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                        return when (customCommand.customAction) {
                            UPDATE_VIEWING_METADATA -> {
                                handleViewingMetadataCommand(customCommand.customExtras, session.player)
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            START_STREAM -> {
                                backgroundPlayback = false
                                val uri = customCommand.customExtras.getString(URI)
                                val title = customCommand.customExtras.getString(TITLE)
                                val channelName = customCommand.customExtras.getString(CHANNEL_NAME)
                                val channelLogo = customCommand.customExtras.getString(CHANNEL_LOGO)
                                setViewingMetadata(
                                    ViewingPlaybackMetadata.CONTENT_TYPE_LIVE,
                                    customCommand.customExtras.getString(STREAM_ID),
                                    customCommand.customExtras,
                                )
                                videoId = null
                                offlineVideoId = null
                                proxyMediaPlaylist = false
                                val proxyHost = prefs().httpProxyHost()
                                val proxyPort = prefs().httpProxyPort()
                                val proxyUser = prefs().getString(C.PROXY_USER, null)
                                val proxyPassword = prefs().getString(C.PROXY_PASSWORD, null)
                                val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                                player.setMediaSource(
                                    HlsMediaSource.Factory(
                                        DefaultDataSource.Factory(
                                            this@PlaybackService,
                                            when {
                                                networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                                                    val proxyMultivariantPlaylist = prefs().getBoolean(C.PROXY_MULTIVARIANT_PLAYLIST, false) && !proxyHost.isNullOrBlank() && proxyPort != null
                                                    val proxyMediaPlaylist = !proxyHost.isNullOrBlank() && proxyPort != null
                                                    val proxyClient = if (proxyMultivariantPlaylist || proxyMediaPlaylist) {
                                                        val proxyHeaders = if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                                            listOf(android.util.Pair("Proxy-Authorization", Base64.encodeToString("$proxyUser:$proxyPassword".toByteArray(), Base64.NO_WRAP)))
                                                        } else emptyList()
                                                        val builder = HttpEngine.Builder(application)
                                                        try {
                                                            builder.setProxyOptions(ProxyOptions.fromProxyList(
                                                                listOf(
                                                                    android.net.http.Proxy.createHttpProxy(
                                                                        android.net.http.Proxy.SCHEME_HTTP,
                                                                        proxyHost,
                                                                        proxyPort,
                                                                        xtraModule.cronetExecutor.value,
                                                                        object : android.net.http.Proxy.HttpConnectCallback {
                                                                            override fun onBeforeRequest(request: android.net.http.Proxy.HttpConnectCallback.Request) {
                                                                                request.proceed(proxyHeaders)
                                                                            }

                                                                            override fun onResponseReceived(responseHeaders: List<android.util.Pair<String?, String?>?>, statusCode: Int): Int {
                                                                                return android.net.http.Proxy.HttpConnectCallback.RESPONSE_ACTION_PROCEED
                                                                            }
                                                                        }
                                                                    )
                                                                ),
                                                                ProxyOptions.ALL_PROXIES_FAILED_BEHAVIOR_DISALLOW_DIRECT
                                                            ))
                                                        } catch (e: NoClassDefFoundError) {
                                                            null
                                                        }?.build()
                                                    } else null
                                                    val multivariantPlaylistProxyClient = if (proxyMultivariantPlaylist && proxyClient == null) {
                                                        xtraModule.okHttpClient.value.newBuilder().apply {
                                                            proxySelector(
                                                                object : ProxySelector() {
                                                                    override fun select(u: URI): List<Proxy> {
                                                                        return if (Regex(MULTIVARIANT_PLAYLIST_REGEX).matches(u.host)) {
                                                                            listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)), Proxy.NO_PROXY)
                                                                        } else {
                                                                            listOf(Proxy.NO_PROXY)
                                                                        }
                                                                    }

                                                                    override fun connectFailed(u: URI, sa: SocketAddress, e: IOException) {}
                                                                }
                                                            )
                                                            if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                                                proxyAuthenticator { _, response ->
                                                                    response.request.newBuilder().header(
                                                                        "Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)
                                                                    ).build()
                                                                }
                                                            }
                                                        }.build()
                                                    } else null
                                                    val mediaPlaylistProxyClient = if (proxyMediaPlaylist && proxyClient == null) {
                                                        xtraModule.okHttpClient.value.newBuilder().apply {
                                                            proxySelector(
                                                                object : ProxySelector() {
                                                                    override fun select(u: URI): List<Proxy> {
                                                                        return if (Regex(MEDIA_PLAYLIST_REGEX).matches(u.host)) {
                                                                            listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)), Proxy.NO_PROXY)
                                                                        } else {
                                                                            listOf(Proxy.NO_PROXY)
                                                                        }
                                                                    }

                                                                    override fun connectFailed(u: URI, sa: SocketAddress, e: IOException) {}
                                                                }
                                                            )
                                                            if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                                                proxyAuthenticator { _, response ->
                                                                    response.request.newBuilder().header(
                                                                        "Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)
                                                                    ).build()
                                                                }
                                                            }
                                                        }.build()
                                                    } else null
                                                    HttpEngineDataSource.Factory(xtraModule.httpEngine.value, xtraModule.cronetExecutor.value, proxyMultivariantPlaylist, proxyMediaPlaylist, proxyClient, multivariantPlaylistProxyClient, mediaPlaylistProxyClient) { proxyMediaPlaylist }
                                                }
                                                networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                                                    val proxyMultivariantPlaylist = prefs().getBoolean(C.PROXY_MULTIVARIANT_PLAYLIST, false) && !proxyHost.isNullOrBlank() && proxyPort != null
                                                    val proxyMediaPlaylist = !proxyHost.isNullOrBlank() && proxyPort != null
                                                    val proxyClient = if ((proxyMultivariantPlaylist || proxyMediaPlaylist) && CronetProvider.getAllProviders(application).any { it.isEnabled }) {
                                                        val proxyHeaders = if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                                            mapOf("Proxy-Authorization" to Base64.encodeToString("$proxyUser:$proxyPassword".toByteArray(), Base64.NO_WRAP)).entries.toList()
                                                        } else emptyList()
                                                        val builder = CronetEngine.Builder(application).apply {
                                                            val userAgent = "Cronet/" + defaultUserAgent.substringAfter("Cronet/", "").substringBefore(')')
                                                            setUserAgent(userAgent)
                                                            @QuicOptions.Experimental
                                                            setQuicOptions(QuicOptions.builder().setHandshakeUserAgent(userAgent).build())
                                                        }
                                                        try {
                                                            @org.chromium.net.ProxyOptions.Experimental
                                                            builder.setProxyOptions(org.chromium.net.ProxyOptions(
                                                                listOf(
                                                                    org.chromium.net.Proxy(
                                                                        org.chromium.net.Proxy.HTTP,
                                                                        proxyHost,
                                                                        proxyPort,
                                                                        xtraModule.cronetExecutor.value,
                                                                        object : org.chromium.net.Proxy.Callback() {
                                                                            override fun onBeforeTunnelRequest(request: Request) {
                                                                                request.proceed(proxyHeaders)
                                                                            }

                                                                            override fun onTunnelHeadersReceived(responseHeaders: List<Map.Entry<String?, String?>?>, statusCode: Int): Boolean {
                                                                                return true
                                                                            }
                                                                        }
                                                                    )
                                                                )
                                                            ))
                                                        } catch (e: UnsupportedOperationException) {
                                                            null
                                                        }?.build()
                                                    } else null
                                                    val multivariantPlaylistProxyClient = if (proxyMultivariantPlaylist && proxyClient == null) {
                                                        xtraModule.okHttpClient.value.newBuilder().apply {
                                                            proxySelector(
                                                                object : ProxySelector() {
                                                                    override fun select(u: URI): List<Proxy> {
                                                                        return if (Regex(ExoPlayerService.MULTIVARIANT_PLAYLIST_REGEX).matches(u.host)) {
                                                                            listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)), Proxy.NO_PROXY)
                                                                        } else {
                                                                            listOf(Proxy.NO_PROXY)
                                                                        }
                                                                    }

                                                                    override fun connectFailed(u: URI, sa: SocketAddress, e: IOException) {}
                                                                }
                                                            )
                                                            if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                                                proxyAuthenticator { _, response ->
                                                                    response.request.newBuilder().header(
                                                                        "Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)
                                                                    ).build()
                                                                }
                                                            }
                                                        }.build()
                                                    } else null
                                                    val mediaPlaylistProxyClient = if (proxyMediaPlaylist && proxyClient == null) {
                                                        xtraModule.okHttpClient.value.newBuilder().apply {
                                                            proxySelector(
                                                                object : ProxySelector() {
                                                                    override fun select(u: URI): List<Proxy> {
                                                                        return if (Regex(ExoPlayerService.MEDIA_PLAYLIST_REGEX).matches(u.host)) {
                                                                            listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)), Proxy.NO_PROXY)
                                                                        } else {
                                                                            listOf(Proxy.NO_PROXY)
                                                                        }
                                                                    }

                                                                    override fun connectFailed(u: URI, sa: SocketAddress, e: IOException) {}
                                                                }
                                                            )
                                                            if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                                                proxyAuthenticator { _, response ->
                                                                    response.request.newBuilder().header(
                                                                        "Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)
                                                                    ).build()
                                                                }
                                                            }
                                                        }.build()
                                                    } else null
                                                    CronetDataSource.Factory(xtraModule.cronetEngine.value, xtraModule.cronetExecutor.value, proxyMultivariantPlaylist, proxyMediaPlaylist, proxyClient, multivariantPlaylistProxyClient, mediaPlaylistProxyClient) { proxyMediaPlaylist }
                                                }
                                                else -> {
                                                    val multivariantPlaylistProxyClient = if (prefs().getBoolean(C.PROXY_MULTIVARIANT_PLAYLIST, false) && !proxyHost.isNullOrBlank() && proxyPort != null) {
                                                        xtraModule.okHttpClient.value.newBuilder().apply {
                                                            proxySelector(
                                                                object : ProxySelector() {
                                                                    override fun select(u: URI): List<Proxy> {
                                                                        return if (Regex(ExoPlayerService.MULTIVARIANT_PLAYLIST_REGEX).matches(u.host)) {
                                                                            listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)), Proxy.NO_PROXY)
                                                                        } else {
                                                                            listOf(Proxy.NO_PROXY)
                                                                        }
                                                                    }

                                                                    override fun connectFailed(u: URI, sa: SocketAddress, e: IOException) {}
                                                                }
                                                            )
                                                            if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                                                proxyAuthenticator { _, response ->
                                                                    response.request.newBuilder().header(
                                                                        "Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)
                                                                    ).build()
                                                                }
                                                            }
                                                        }.build()
                                                    } else null
                                                    val mediaPlaylistProxyClient = if (!proxyHost.isNullOrBlank() && proxyPort != null) {
                                                        xtraModule.okHttpClient.value.newBuilder().apply {
                                                            proxySelector(
                                                                object : ProxySelector() {
                                                                    override fun select(u: URI): List<Proxy> {
                                                                        return if (Regex(ExoPlayerService.MEDIA_PLAYLIST_REGEX).matches(u.host)) {
                                                                            listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)), Proxy.NO_PROXY)
                                                                        } else {
                                                                            listOf(Proxy.NO_PROXY)
                                                                        }
                                                                    }

                                                                    override fun connectFailed(u: URI, sa: SocketAddress, e: IOException) {}
                                                                }
                                                            )
                                                            if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                                                proxyAuthenticator { _, response ->
                                                                    response.request.newBuilder().header(
                                                                        "Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)
                                                                    ).build()
                                                                }
                                                            }
                                                        }.build()
                                                    } else null
                                                    OkHttpDataSource.Factory(multivariantPlaylistProxyClient ?: xtraModule.okHttpClient.value, mediaPlaylistProxyClient) { proxyMediaPlaylist }
                                                }
                                            }.apply {
                                                prefs().getString(C.PLAYER_STREAM_HEADERS, null)?.let {
                                                    try {
                                                        val json = JSONObject(it)
                                                        hashMapOf<String, String>().apply {
                                                            json.keys().forEach { key ->
                                                                put(key, json.optString(key))
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        null
                                                    }
                                                }?.let {
                                                    setDefaultRequestProperties(it)
                                                }
                                            }
                                        )
                                    ).apply {
                                        setPlaylistParserFactory(ExoPlayerService.CustomHlsPlaylistParserFactory())
                                        setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
                                    }.createMediaSource(
                                        MediaItem.Builder().apply {
                                            setUri(uri?.toUri())
                                            setMimeType(MimeTypes.APPLICATION_M3U8)
                                            setLiveConfiguration(MediaItem.LiveConfiguration.Builder().apply {
                                                setTargetOffsetMs(2000L)
                                            }.build())
                                            setMediaMetadata(
                                                MediaMetadata.Builder().apply {
                                                    setTitle(title)
                                                    setArtist(channelName)
                                                    setArtworkUri(channelLogo?.toUri())
                                                }.build()
                                            )
                                        }.build()
                                    )
                                )
                                session.player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                session.player.setPlaybackSpeed(1f)
                                session.player.prepare()
                                session.player.playWhenReady = customCommand.customExtras.getBoolean(PLAY_WHEN_READY, true)
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            START_VIDEO -> {
                                backgroundPlayback = false
                                val uri = customCommand.customExtras.getString(URI)
                                val title = customCommand.customExtras.getString(TITLE)
                                val channelName = customCommand.customExtras.getString(CHANNEL_NAME)
                                val channelLogo = customCommand.customExtras.getString(CHANNEL_LOGO)
                                val newId = customCommand.customExtras.getLong(VIDEO_ID).takeIf { it != 0L }
                                setViewingMetadata(
                                    ViewingPlaybackMetadata.CONTENT_TYPE_VOD,
                                    newId?.toString(),
                                    customCommand.customExtras,
                                )
                                val position = if (videoId == newId && session.player.currentMediaItem != null) {
                                    session.player.currentPosition
                                } else {
                                    customCommand.customExtras.getLong(PLAYBACK_POSITION)
                                }
                                videoId = newId
                                offlineVideoId = null
                                val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                                player.setMediaSource(
                                    HlsMediaSource.Factory(
                                        DefaultDataSource.Factory(
                                            this@PlaybackService,
                                            when {
                                                networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                                                    HttpEngineDataSource.Factory(xtraModule.httpEngine.value, xtraModule.cronetExecutor.value, false, false, null, null, null) { false }
                                                }
                                                networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                                                    CronetDataSource.Factory(xtraModule.cronetEngine.value, xtraModule.cronetExecutor.value, false, false, null, null, null) { false }
                                                }
                                                else -> {
                                                    OkHttpDataSource.Factory(xtraModule.okHttpClient.value, null) { false }
                                                }
                                            }
                                        )
                                    ).apply {
                                        setPlaylistParserFactory(ExoPlayerService.CustomHlsPlaylistParserFactory())
                                    }.createMediaSource(
                                        MediaItem.Builder().apply {
                                            setUri(uri?.toUri())
                                            setMediaMetadata(
                                                MediaMetadata.Builder().apply {
                                                    setTitle(title)
                                                    setArtist(channelName)
                                                    setArtworkUri(channelLogo?.toUri())
                                                }.build()
                                            )
                                        }.build()
                                    )
                                )
                                session.player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                session.player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                session.player.prepare()
                                session.player.playWhenReady = true
                                session.player.seekTo(position)
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            START_CLIP -> {
                                backgroundPlayback = false
                                val uri = customCommand.customExtras.getString(URI)
                                val title = customCommand.customExtras.getString(TITLE)
                                val channelName = customCommand.customExtras.getString(CHANNEL_NAME)
                                val channelLogo = customCommand.customExtras.getString(CHANNEL_LOGO)
                                setViewingMetadata(
                                    ViewingPlaybackMetadata.CONTENT_TYPE_CLIP,
                                    customCommand.customExtras.getString(CLIP_ID),
                                    customCommand.customExtras,
                                )
                                videoId = null
                                offlineVideoId = null
                                val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                                player.setMediaSource(
                                    ProgressiveMediaSource.Factory(
                                        DefaultDataSource.Factory(
                                            this@PlaybackService,
                                            when {
                                                networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                                                    HttpEngineDataSource.Factory(xtraModule.httpEngine.value, xtraModule.cronetExecutor.value, false, false, null, null, null) { false }
                                                }
                                                networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                                                    CronetDataSource.Factory(xtraModule.cronetEngine.value, xtraModule.cronetExecutor.value, false, false, null, null, null) { false }
                                                }
                                                else -> {
                                                    OkHttpDataSource.Factory(xtraModule.okHttpClient.value, null) { false }
                                                }
                                            }
                                        )
                                    ).createMediaSource(
                                        MediaItem.Builder().apply {
                                            setUri(uri?.toUri())
                                            setMediaMetadata(
                                                MediaMetadata.Builder().apply {
                                                    setTitle(title)
                                                    setArtist(channelName)
                                                    setArtworkUri(channelLogo?.toUri())
                                                }.build()
                                            )
                                        }.build()
                                    )
                                )
                                session.player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                session.player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                session.player.prepare()
                                session.player.playWhenReady = true
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            START_OFFLINE_VIDEO -> {
                                backgroundPlayback = false
                                val uri = customCommand.customExtras.getString(URI)
                                val title = customCommand.customExtras.getString(TITLE)
                                val channelName = customCommand.customExtras.getString(CHANNEL_NAME)
                                val channelLogo = customCommand.customExtras.getString(CHANNEL_LOGO)
                                val newId = customCommand.customExtras.getInt(VIDEO_ID).takeIf { it != 0 }
                                setViewingMetadata(
                                    ViewingPlaybackMetadata.CONTENT_TYPE_OFFLINE_VIDEO,
                                    newId?.toString(),
                                    customCommand.customExtras,
                                )
                                val position = if (offlineVideoId == newId && session.player.currentMediaItem != null) {
                                    session.player.currentPosition
                                } else {
                                    customCommand.customExtras.getLong(PLAYBACK_POSITION)
                                }
                                videoId = null
                                offlineVideoId = newId
                                session.player.setMediaItem(
                                    MediaItem.Builder().apply {
                                        setUri(uri)
                                        setMediaMetadata(
                                            MediaMetadata.Builder().apply {
                                                setTitle(title)
                                                setArtist(channelName)
                                                setArtworkUri(channelLogo?.toUri())
                                            }.build()
                                        )
                                    }.build()
                                )
                                session.player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                session.player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                session.player.prepare()
                                session.player.playWhenReady = true
                                session.player.seekTo(position)
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            TOGGLE_DYNAMICS_PROCESSING -> {
                                if (dynamicsProcessing?.enabled == true) {
                                    dynamicsProcessing?.enabled = false
                                } else {
                                    if (dynamicsProcessing == null) {
                                        reinitializeDynamicsProcessing(player.audioSessionId)
                                    } else {
                                        dynamicsProcessing?.enabled = true
                                    }
                                }
                                val enabled = dynamicsProcessing?.enabled == true
                                prefs().edit { putBoolean(C.PLAYER_AUDIO_COMPRESSOR, enabled) }
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putBoolean(RESULT, enabled)
                                }))
                            }
                            TOGGLE_PROXY -> {
                                proxyMediaPlaylist = customCommand.customExtras.getBoolean(USING_PROXY)
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            SET_BACKGROUND_PLAYBACK -> {
                                backgroundPlayback = customCommand.customExtras.getBoolean(BACKGROUND_PLAYBACK)
                                if (!backgroundPlayback) {
                                    backgroundRecoveryTimer?.cancel()
                                    backgroundRecoveryTimer = null
                                    backgroundRecoveryAttempt = 0
                                }
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            SET_SLEEP_TIMER -> {
                                val duration = customCommand.customExtras.getLong(DURATION)
                                val endTime = sleepTimerEndTime
                                sleepTimer?.cancel()
                                sleepTimerEndTime = 0L
                                if (duration > 0L) {
                                    sleepTimer = Timer().apply {
                                        schedule(duration) {
                                            Handler(Looper.getMainLooper()).post {
                                                savePosition()
                                                runAfterPlaybackPersistence {
                                                    mediaSession?.player?.clearMediaItems()
                                                    pauseAllPlayersAndStopSelf()
                                                }
                                            }
                                        }
                                    }
                                    sleepTimerEndTime = System.currentTimeMillis() + duration
                                }
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putLong(RESULT, endTime)
                                }))
                            }
                            CHECK_ADS -> {
                                val playlist = (session.player.currentManifest as? HlsManifest)?.mediaPlaylist
                                val adSegment = playlist?.let { TwitchAdDetector.isAd(it) } == true
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putBoolean(RESULT, adSegment)
                                }))
                            }
                            GET_QUALITIES -> {
                                val playlist = (session.player.currentManifest as? HlsManifest)?.multivariantPlaylist
                                val list = playlist?.variants?.mapNotNull { variant ->
                                    val name = variant.format.label?.takeIf { it.isNotBlank() }
                                        ?: playlist.videos.find { it.groupId == variant.videoGroupId }?.name?.takeIf { it.isNotBlank() }
                                    if (name != null) {
                                        VideoQuality(name, variant.format.codecs, variant.format.bitrate, variant.url.toString())
                                    } else null
                                }
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putStringArray(NAMES, list?.map { it.name.toString() }?.toTypedArray())
                                    putStringArray(CODECS, list?.map { it.codecs.toString() }?.toTypedArray())
                                    putStringArray(BITRATES, list?.map { it.bitrate.toString() }?.toTypedArray())
                                    putStringArray(URLS, list?.map { it.url.toString() }?.toTypedArray())
                                }))
                            }
                            GET_DURATION -> {
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putLong(RESULT, (session.player.currentManifest as? HlsManifest)?.mediaPlaylist?.durationUs?.div(1000) ?: 0)
                                }))
                            }
                            GET_ERROR_CODE -> {
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putInt(RESULT, (session.player.playerError?.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0)
                                }))
                            }
                            GET_MEDIA_PLAYLIST -> {
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putStringArray(RESULT, (session.player.currentManifest as? HlsManifest)?.mediaPlaylist?.tags?.toTypedArray())
                                }))
                            }
                            GET_MULTIVARIANT_PLAYLIST -> {
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putStringArray(RESULT, (session.player.currentManifest as? HlsManifest)?.multivariantPlaylist?.tags?.toTypedArray())
                                }))
                            }
                            else -> super.onCustomCommand(session, controller, customCommand, args)
                        }
                    }
                }
            )
        }.build()
    }

    private fun reinitializeDynamicsProcessing(audioSessionId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dynamicsProcessing = DynamicsProcessing(0, audioSessionId, null).apply {
                for (channelIdx in 0 until channelCount) {
                    for (bandIdx in 0 until getMbcByChannelIndex(channelIdx).bandCount) {
                        setMbcBandByChannelIndex(
                            channelIdx,
                            bandIdx,
                            getMbcBandByChannelIndex(channelIdx, bandIdx).apply {
                                attackTime = 0f
                                releaseTime = 0.25f
                                ratio = 1.6f
                                threshold = -50f
                                kneeWidth = 40f
                                preGain = 0f
                                postGain = 10f
                            }
                        )
                    }
                }
                enabled = true
            }
        }
    }

    private fun savePosition() {
        mediaSession?.player?.let { player ->
            if (!player.currentTracks.isEmpty && prefs().getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
                videoId?.let {
                    xtraModule.playbackPersistence.saveVideoPosition(VideoPosition(it, player.currentPosition))
                    xtraModule.playbackPersistence.saveVideoHistoryPosition(it.toLong(), player.currentPosition)
                } ?:
                offlineVideoId?.let {
                    xtraModule.playbackPersistence.saveOfflineVideoPosition(it, player.currentPosition)
                }
            }
        }
    }

    private fun runAfterPlaybackPersistence(action: () -> Unit) {
        lifecycleScope.launch {
            xtraModule.playbackPersistence.flush()
            action()
        }
    }

    private fun updateSavedPosition() {
        mediaSession?.player?.let { player ->
            if (!player.currentTracks.isEmpty && prefs().getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
                val currentPosition = player.currentPosition
                val savedPosition = lastSavedPosition
                if (savedPosition == null || currentPosition - savedPosition !in 0..2000) {
                    lastSavedPosition = currentPosition
                    videoId?.let {
                        xtraModule.playbackPersistence.saveVideoPosition(VideoPosition(it, currentPosition))
                        xtraModule.playbackPersistence.saveVideoHistoryPosition(it.toLong(), currentPosition)
                    } ?:
                    offlineVideoId?.let {
                        xtraModule.playbackPersistence.saveOfflineVideoPosition(it, currentPosition)
                    }
                }
            }
        }
    }

    private fun scheduleBackgroundRecovery() {
        if (!prefs().getBoolean(C.PLAYER_AUTO_RECOVER_STREAMS, true)) {
            return
        }
        backgroundRecoveryTimer?.cancel()
        val delay = (500L shl backgroundRecoveryAttempt.coerceAtMost(4)).coerceAtMost(8000L)
        backgroundRecoveryAttempt = (backgroundRecoveryAttempt + 1).coerceAtMost(4)
        backgroundRecoveryTimer = Timer().apply {
            schedule(delay) {
                Handler(Looper.getMainLooper()).post {
                    backgroundRecoveryTimer = null
                    val player = mediaSession?.player
                    if (backgroundPlayback
                        && prefs().getBoolean(C.PLAYER_AUTO_RECOVER_STREAMS, true)
                        && player?.playWhenReady == true
                        && player.playerError != null
                    ) {
                        player.prepare()
                    }
                }
            }
        }
    }

    internal fun handleViewingMetadataCommand(extras: Bundle, player: Player) {
        val streamId = extras.getString(STREAM_ID)
        if (viewingContentType != ViewingPlaybackMetadata.CONTENT_TYPE_LIVE ||
            (streamId != null && streamId != viewingContentId)
        ) {
            return
        }

        // UPDATE_VIEWING_METADATA is a patch. A category is only updated when
        // both identity fields are supplied so an incomplete refresh cannot
        // create an inconsistent ID/name pair.
        val nextCategory = mergeViewingCategoryPatch(
            currentId = viewingCategoryId,
            currentName = viewingCategoryName,
            patchId = extras.getString(GAME_ID),
            patchName = extras.getString(GAME_NAME),
        )
        viewingCategoryId = nextCategory.id
        viewingCategoryName = nextCategory.name
        if (extras.containsKey(GAME_IMAGE)) {
            viewingCategoryImage = extras.getString(GAME_IMAGE)
        }
        if (extras.containsKey(TITLE)) {
            viewingTitle = extras.getString(TITLE)
        }
        updateViewingStats(player)
    }

    internal fun setViewingMetadata(
        contentType: String,
        contentId: String?,
        extras: Bundle,
    ) {
        finishViewingStats()
        viewingChannelId = extras.getString(CHANNEL_ID)
        viewingChannelLogin = extras.getString(CHANNEL_LOGIN)
        viewingChannelName = extras.getString(CHANNEL_NAME)
        viewingChannelImage = extras.getString(CHANNEL_LOGO)
        viewingCategoryId = extras.getString(GAME_ID)
        viewingCategoryName = extras.getString(GAME_NAME)
        viewingCategoryImage = extras.getString(GAME_IMAGE)
        viewingTitle = extras.getString(TITLE)
        viewingContentType = contentType
        viewingContentId = contentId
    }

    private fun finishViewingStats() {
        if (::xtraModule.isInitialized) {
            xtraModule.viewingStatsRecorder.update(
                sourceId = viewingStatsSourceId,
                metadata = viewingMetadata(),
                isPlaying = false,
                isBuffering = false,
            )
        }
    }

    private fun updateViewingStats(player: Player) {
        val contentType = viewingContentType ?: return
        xtraModule.viewingStatsRecorder.update(
            sourceId = viewingStatsSourceId,
            metadata = ViewingPlaybackMetadata(
                channelId = viewingChannelId,
                channelLogin = viewingChannelLogin,
                channelName = viewingChannelName,
                channelImage = viewingChannelImage,
                categoryId = viewingCategoryId,
                categoryName = viewingCategoryName,
                categoryImage = viewingCategoryImage,
                contentType = contentType,
                contentId = viewingContentId,
                title = viewingTitle,
            ),
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
        )
    }

    private fun viewingMetadata(): ViewingPlaybackMetadata? {
        val contentType = viewingContentType ?: return null
        return ViewingPlaybackMetadata(
            channelId = viewingChannelId,
            channelLogin = viewingChannelLogin,
            channelName = viewingChannelName,
            channelImage = viewingChannelImage,
            categoryId = viewingCategoryId,
            categoryName = viewingCategoryName,
            categoryImage = viewingCategoryImage,
            contentType = contentType,
            contentId = viewingContentId,
            title = viewingTitle,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition()
        val player = mediaSession?.player
        val isInteractive = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        val keepPlayback = player?.playWhenReady == true
                && player.playbackState != Player.STATE_ENDED
                && prefs().getBoolean(C.PLAYER_KEEP_PLAYING_AFTER_TASK_REMOVED, true)
                && prefs().getBoolean(C.SETTINGS_BACKGROUND_PLAYBACK, true)
        if (keepPlayback) {
            backgroundPlayback = true
            return
        }
        player?.clearMediaItems()
        runAfterPlaybackPersistence {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        if (::xtraModule.isInitialized) {
            xtraModule.viewingStatsRecorder.release(viewingStatsSourceId)
        }
        backgroundRecoveryTimer?.cancel()
        backgroundRecoveryTimer = null
        sleepTimer?.cancel()
        savePositionTimer?.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    companion object {
        const val START_STREAM = "startStream"
        const val UPDATE_VIEWING_METADATA = "updateViewingMetadata"
        const val START_VIDEO = "startVideo"
        const val START_CLIP = "startClip"
        const val START_OFFLINE_VIDEO = "startOfflineVideo"
        const val TOGGLE_DYNAMICS_PROCESSING = "toggleDynamicsProcessing"
        const val TOGGLE_PROXY = "toggleProxy"
        const val SET_BACKGROUND_PLAYBACK = "setBackgroundPlayback"
        const val SET_SLEEP_TIMER = "setSleepTimer"
        const val CHECK_ADS = "checkAds"
        const val GET_QUALITIES = "getQualities"
        const val GET_DURATION = "getDuration"
        const val GET_ERROR_CODE = "getErrorCode"
        const val GET_MEDIA_PLAYLIST = "getMediaPlaylist"
        const val GET_MULTIVARIANT_PLAYLIST = "getMultivariantPlaylist"

        const val RESULT = "result"
        const val URI = "uri"
        const val STREAM_ID = "streamId"
        const val VIDEO_ID = "videoId"
        const val CLIP_ID = "clipId"
        const val PLAYBACK_POSITION = "playbackPosition"
        const val TITLE = "title"
        const val CHANNEL_ID = "channelId"
        const val CHANNEL_LOGIN = "channelLogin"
        const val CHANNEL_NAME = "channelName"
        const val CHANNEL_LOGO = "channelLogo"
        const val GAME_ID = "gameId"
        const val GAME_NAME = "gameName"
        const val GAME_IMAGE = "gameImage"
        const val USING_PROXY = "usingProxy"
        const val PLAY_WHEN_READY = "playWhenReady"
        const val BACKGROUND_PLAYBACK = "backgroundPlayback"
        const val DURATION = "duration"
        const val NAMES = "names"
        const val CODECS = "codecs"
        const val BITRATES = "bitrates"
        const val URLS = "urls"

        const val REQUEST_CODE_RESUME = 2
    }
}
