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
import android.util.Log
import android.util.Base64
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.XtraModule
import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.model.stats.ViewingPlaybackMetadata
import com.github.andreyasadchy.xtra.player.lowlatency.CronetDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.HttpEngineDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.OkHttpDataSource
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.httpProxyHost
import com.github.andreyasadchy.xtra.util.httpProxyPort
import com.github.andreyasadchy.xtra.util.m3u8.TwitchAdDetector
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.shouldAvoidTwitchAds
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    lateinit var xtraModule: XtraModule

    private var mediaSession: MediaSession? = null
    private var canonicalPlayer: ExoPlayer? = null
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var presentation = PlayerPresentation.BACKGROUND
    private var recoveryTimer: Timer? = null
    private val recoveryPolicy = PlaybackRecoveryPolicy()
    private var proxyPolicy = PlaybackProxyPolicy()
    private var videoId: Long? = null
    private var clipId: String? = null
    private var offlineVideoId: Int? = null
    private var currentType: String? = null
    private var currentStreamId: String? = null
    private var currentUri: String? = null
    private var currentTitle: String? = null
    private var currentChannelId: String? = null
    private var currentChannelLogin: String? = null
    private var currentChannelName: String? = null
    private var currentChannelImage: String? = null
    private var canonicalPlaybackState: PlaybackState? = null
    private var restorePlaybackJob: Job? = null
    private var adAvoidanceJob: Job? = null
    private var primaryStreamRestoreJob: Job? = null
    private var proxyRestoreJob: Job? = null
    private val adController = TwitchAdController()
    private var playingAds = false
    private var hiddenForAd = false
    private var usingAlternateStream = false
    private var selectedQuality: VideoQuality? = null
    private var availableQualities: List<VideoQuality>? = null
    private var restoreQuality = false
    private var restorePlaylist = false
    private var sleepTimer: Timer? = null
    private var sleepTimerEndTime = 0L
    private var lastSavedPosition: Long? = null
    private var savePositionTimer: Timer? = null
    private val viewingStatsSourceId = "playback-service:primary"
    private var viewingChannelId: String? = null
    private var viewingChannelLogin: String? = null
    private var viewingChannelName: String? = null
    private var viewingChannelImage: String? = null
    private var viewingContentType: String? = null
    private var viewingContentId: String? = null

    override fun onCreate() {
        super.onCreate()
        xtraModule = (application as XtraApp).xtraModule
        val player = ExoPlayer.Builder(this, createCanonicalMediaSourceFactory()).apply {
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
            // ExoPlayer owns the normal media-app focus lifecycle. The existing
            // preference remains an explicit opt-in to mixing with other apps.
            setAudioAttributes(AudioAttributes.DEFAULT, prefs().getBoolean(C.PLAYER_AUDIO_FOCUS, true))
            setHandleAudioBecomingNoisy(true)
            setSeekBackIncrementMs((prefs().getString(C.PLAYER_REWIND, "10")?.toLongOrNull() ?: 10) * 1000)
            setSeekForwardIncrementMs((prefs().getString(C.PLAYER_FORWARD, "10")?.toLongOrNull() ?: 10) * 1000)
        }.build()
        canonicalPlayer = player
        player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
        player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
        Log.d(TAG, "Created canonical ExoPlayer id=${System.identityHashCode(player)}")
        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateViewingStats(player)
                    if (isPlaying) {
                        recoveryTimer?.cancel()
                        recoveryTimer = null
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
                    if (currentType == BasePlaybackService.STREAM
                        && prefs().getBoolean(C.PLAYER_AUTO_RECOVER_STREAMS, true)
                        && player.playWhenReady
                    ) {
                        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && player.isCurrentMediaItemLive) {
                            Log.i(TAG, "Recovering behind-live-window playback")
                            player.seekToDefaultPosition()
                            player.prepare()
                        } else {
                            scheduleRecovery(error)
                        }
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    updateViewingStats(player)
                    if (playbackState == Player.STATE_READY) {
                        recoveryTimer?.cancel()
                        recoveryTimer = null
                        recoveryPolicy.reset()
                    }
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    if (!tracks.isEmpty) {
                        applySelectedQuality(player)
                    }
                }

                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                    if (currentType == BasePlaybackService.STREAM && !timeline.isEmpty) {
                        refreshAvailableQualities(player)
                        handleStreamTimeline(player)
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
                    @Suppress("DEPRECATION")
                    @Deprecated("Media3 compatibility callback", level = DeprecationLevel.HIDDEN)
                    override fun onPlayerCommandRequest(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo,
                        playerCommand: Int,
                    ): Int {
                        if (playerCommand == Player.COMMAND_STOP && controller.packageName != packageName) {
                            Log.d(TAG, "Explicit system stop from ${controller.packageName}")
                            clearPersistedPlayback()
                        }
                        return super.onPlayerCommandRequest(session, controller, playerCommand)
                    }

                    override fun onPlaybackResumption(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo,
                        playWhenReady: Boolean,
                    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                        val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                        lifecycleScope.launch {
                            try {
                                val savedState = xtraModule.playbackPersistence.getPlaybackStatesAndWait().firstOrNull()
                                if (savedState == null) {
                                    result.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                                    return@launch
                                }
                                restoreCanonicalState(savedState)
                                val url = if (shouldResolveFreshStreamForResumption(savedState.type, playWhenReady)) {
                                    resolveFreshStreamUrl() ?: savedState.videoUrl
                                } else {
                                    savedState.videoUrl
                                }
                                if (url.isNullOrBlank()) {
                                    result.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                                    return@launch
                                }
                                currentUri = url
                                canonicalPlaybackState = savedState.copy(videoUrl = url)
                                val mediaItem = mediaItemForState(savedState, url)
                                result.set(
                                    MediaSession.MediaItemsWithStartPosition(
                                        listOf(mediaItem),
                                        0,
                                        savedState.position ?: 0L,
                                    )
                                )
                            } catch (e: CancellationException) {
                                result.cancel(false)
                            } catch (e: Exception) {
                                Log.w(TAG, "Playback resumption failed", e)
                                result.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                            }
                        }
                        return result
                    }

                    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                        val connectionResult = super.onConnect(session, controller)
                        val sessionCommands = connectionResult.availableSessionCommands.buildUpon().apply {
                            add(SessionCommand(START_STREAM, Bundle.EMPTY))
                            add(SessionCommand(START_VIDEO, Bundle.EMPTY))
                            add(SessionCommand(START_CLIP, Bundle.EMPTY))
                            add(SessionCommand(START_OFFLINE_VIDEO, Bundle.EMPTY))
                            add(SessionCommand(TOGGLE_DYNAMICS_PROCESSING, Bundle.EMPTY))
                            add(SessionCommand(TOGGLE_PROXY, Bundle.EMPTY))
                            add(SessionCommand(SET_BACKGROUND_PLAYBACK, Bundle.EMPTY))
                            add(SessionCommand(SET_PRESENTATION, Bundle.EMPTY))
                            add(SessionCommand(CLEAR_PLAYBACK, Bundle.EMPTY))
                            add(SessionCommand(SYNC_PLAYBACK_STATE, Bundle.EMPTY))
                            add(SessionCommand(GET_PLAYBACK_STATE, Bundle.EMPTY))
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
                            START_STREAM -> {
                                setPresentation(PlayerPresentation.FULL)
                                Log.d(TAG, "Replacing media source type=stream")
                                val uri = customCommand.customExtras.getString(URI)
                                val title = customCommand.customExtras.getString(TITLE)
                                val channelName = customCommand.customExtras.getString(CHANNEL_NAME)
                                val channelLogo = customCommand.customExtras.getString(CHANNEL_LOGO)
                                currentType = BasePlaybackService.STREAM
                                currentStreamId = customCommand.customExtras.getString(STREAM_ID)
                                currentUri = uri
                                currentTitle = title
                                clipId = null
                                setViewingMetadata(
                                    ViewingPlaybackMetadata.CONTENT_TYPE_LIVE,
                                    customCommand.customExtras.getString(STREAM_ID),
                                    customCommand.customExtras,
                                )
                                videoId = null
                                offlineVideoId = null
                                initializeCanonicalState(
                                    BasePlaybackService.STREAM,
                                    customCommand.customExtras,
                                    session.player.currentPosition.takeIf { session.player.currentMediaItem?.mediaId == "stream:${currentStreamId.orEmpty()}" },
                                    !customCommand.customExtras.getBoolean(PLAY_WHEN_READY, true),
                                )
                                val proxyHost = prefs().httpProxyHost().orEmpty()
                                val proxyPort = prefs().httpProxyPort() ?: 0
                                val proxyUser = prefs().getString(C.PROXY_USER, null)
                                val proxyPassword = prefs().getString(C.PROXY_PASSWORD, null)
                                val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                                val proxyConfigured = proxyHost.isNotBlank() && proxyPort != 0
                                val configuredProxyMultivariantPlaylist = proxyPolicy.sourceUsesMultivariantProxy(
                                    preferenceEnabled = prefs().getBoolean(C.PROXY_MULTIVARIANT_PLAYLIST, false),
                                    proxyConfigured = proxyConfigured,
                                )
                                val configuredProxyMediaPlaylist = proxyPolicy.sourceUsesMediaPlaylistProxy(proxyConfigured)
                                player.setMediaSource(
                                    HlsMediaSource.Factory(
                                        DefaultDataSource.Factory(
                                            this@PlaybackService,
                                            when {
                                                networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                                                    val proxyMultivariantPlaylist = configuredProxyMultivariantPlaylist
                                                    val proxyMediaPlaylist = configuredProxyMediaPlaylist
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
                                                                        return if (Regex(TwitchPlaybackConstants.MULTIVARIANT_PLAYLIST_REGEX).matches(u.host)) {
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
                                                                        return if (Regex(TwitchPlaybackConstants.MEDIA_PLAYLIST_REGEX).matches(u.host)) {
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
                                                    val proxyMultivariantPlaylist = configuredProxyMultivariantPlaylist
                                                    val proxyMediaPlaylist = configuredProxyMediaPlaylist
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
                                                                        return if (Regex(TwitchPlaybackConstants.MULTIVARIANT_PLAYLIST_REGEX).matches(u.host)) {
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
                                                                        return if (Regex(TwitchPlaybackConstants.MEDIA_PLAYLIST_REGEX).matches(u.host)) {
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
                                                    val proxyMediaPlaylist = configuredProxyMediaPlaylist
                                                    val multivariantPlaylistProxyClient = if (configuredProxyMultivariantPlaylist) {
                                                        xtraModule.okHttpClient.value.newBuilder().apply {
                                                            proxySelector(
                                                                object : ProxySelector() {
                                                                    override fun select(u: URI): List<Proxy> {
                                                                        return if (Regex(TwitchPlaybackConstants.MULTIVARIANT_PLAYLIST_REGEX).matches(u.host)) {
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
                                                    val mediaPlaylistProxyClient = if (configuredProxyMediaPlaylist) {
                                                        xtraModule.okHttpClient.value.newBuilder().apply {
                                                            proxySelector(
                                                                object : ProxySelector() {
                                                                    override fun select(u: URI): List<Proxy> {
                                                                        return if (Regex(TwitchPlaybackConstants.MEDIA_PLAYLIST_REGEX).matches(u.host)) {
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
                                        setPlaylistParserFactory(TwitchHlsPlaylistParserFactory())
                                        setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
                                    }.createMediaSource(
                                        MediaItem.Builder().apply {
                                            setMediaId("stream:${customCommand.customExtras.getString(STREAM_ID).orEmpty()}")
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
                                session.player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                session.player.prepare()
                                session.player.playWhenReady = customCommand.customExtras.getBoolean(PLAY_WHEN_READY, true)
                                persistPlaybackState(session.player.currentPosition, !session.player.playWhenReady)
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            START_VIDEO -> {
                                setPresentation(PlayerPresentation.FULL)
                                Log.d(TAG, "Replacing media source type=video")
                                val uri = customCommand.customExtras.getString(URI)
                                val title = customCommand.customExtras.getString(TITLE)
                                val channelName = customCommand.customExtras.getString(CHANNEL_NAME)
                                val channelLogo = customCommand.customExtras.getString(CHANNEL_LOGO)
                                val newId = customCommand.customExtras.getLong(VIDEO_ID).takeIf { it != 0L }
                                currentType = BasePlaybackService.VIDEO
                                currentStreamId = null
                                currentUri = uri
                                currentTitle = title
                                clipId = null
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
                                initializeCanonicalState(
                                    BasePlaybackService.VIDEO,
                                    customCommand.customExtras,
                                    position,
                                    false,
                                )
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
                                        setPlaylistParserFactory(TwitchHlsPlaylistParserFactory())
                                    }.createMediaSource(
                                        MediaItem.Builder().apply {
                                            setMediaId("video:${newId ?: 0L}")
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
                                persistPlaybackState(position, false)
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            START_CLIP -> {
                                setPresentation(PlayerPresentation.FULL)
                                Log.d(TAG, "Replacing media source type=clip")
                                val uri = customCommand.customExtras.getString(URI)
                                val title = customCommand.customExtras.getString(TITLE)
                                val channelName = customCommand.customExtras.getString(CHANNEL_NAME)
                                val channelLogo = customCommand.customExtras.getString(CHANNEL_LOGO)
                                currentType = BasePlaybackService.CLIP
                                currentStreamId = null
                                currentUri = uri
                                currentTitle = title
                                clipId = customCommand.customExtras.getString(CLIP_ID)
                                setViewingMetadata(
                                    ViewingPlaybackMetadata.CONTENT_TYPE_CLIP,
                                    customCommand.customExtras.getString(CLIP_ID),
                                    customCommand.customExtras,
                                )
                                videoId = null
                                offlineVideoId = null
                                initializeCanonicalState(
                                    BasePlaybackService.CLIP,
                                    customCommand.customExtras,
                                    session.player.currentPosition,
                                    false,
                                )
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
                                            setMediaId("clip:${customCommand.customExtras.getString(CLIP_ID).orEmpty()}")
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
                                persistPlaybackState(session.player.currentPosition, false)
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            START_OFFLINE_VIDEO -> {
                                setPresentation(PlayerPresentation.FULL)
                                Log.d(TAG, "Replacing media source type=offline")
                                val uri = customCommand.customExtras.getString(URI)
                                val title = customCommand.customExtras.getString(TITLE)
                                val channelName = customCommand.customExtras.getString(CHANNEL_NAME)
                                val channelLogo = customCommand.customExtras.getString(CHANNEL_LOGO)
                                val newId = customCommand.customExtras.getInt(VIDEO_ID).takeIf { it != 0 }
                                currentType = BasePlaybackService.OFFLINE_VIDEO
                                currentStreamId = null
                                currentUri = uri
                                currentTitle = title
                                clipId = null
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
                                initializeCanonicalState(
                                    BasePlaybackService.OFFLINE_VIDEO,
                                    customCommand.customExtras,
                                    position,
                                    false,
                                )
                                session.player.setMediaItem(
                                    MediaItem.Builder().apply {
                                        setMediaId("offline:${newId ?: 0}")
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
                                persistPlaybackState(position, false)
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
                                proxyRestoreJob?.cancel()
                                proxyRestoreJob = null
                                proxyPolicy = proxyPolicy.selectManually(customCommand.customExtras.getBoolean(USING_PROXY))
                                mediaSession?.player?.let { activePlayer ->
                                    if (currentType == BasePlaybackService.STREAM && !currentUri.isNullOrBlank()) {
                                        replaceStreamSource(currentUri, preservePosition = true, playWhenReady = activePlayer.playWhenReady)
                                    }
                                }
                                persistPlaybackState(mediaSession?.player?.currentPosition, mediaSession?.player?.playWhenReady != true)
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            SET_BACKGROUND_PLAYBACK -> {
                                setPresentation(
                                    if (customCommand.customExtras.getBoolean(BACKGROUND_PLAYBACK)) {
                                        PlayerPresentation.BACKGROUND
                                    } else {
                                        PlayerPresentation.FULL
                                    }
                                )
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            SET_PRESENTATION -> {
                                val requestedPresentation = customCommand.customExtras
                                    .getString(PRESENTATION)
                                    ?.let { value ->
                                        runCatching { PlayerPresentation.valueOf(value) }.getOrNull()
                                    }
                                if (requestedPresentation != null) {
                                    setPresentation(requestedPresentation)
                                }
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            CLEAR_PLAYBACK -> {
                                clearPersistedPlayback()
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            SYNC_PLAYBACK_STATE -> {
                                syncPlaybackState(customCommand.customExtras)
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            GET_PLAYBACK_STATE -> {
                                Futures.immediateFuture(
                                    SessionResult(
                                        SessionResult.RESULT_SUCCESS,
                                        playbackStateBundle(),
                                    )
                                )
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
                                                clearPersistedPlayback()
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
                                val list = refreshAvailableQualities(session.player)
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
                } ?:
                offlineVideoId?.let {
                    xtraModule.playbackPersistence.saveOfflineVideoPosition(it, player.currentPosition)
                }
            }
            persistPlaybackState(player.currentPosition, !player.playWhenReady)
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
                    } ?:
                    offlineVideoId?.let {
                        xtraModule.playbackPersistence.saveOfflineVideoPosition(it, currentPosition)
                    }
                }
            }
            persistPlaybackState(player.currentPosition, !player.playWhenReady)
        }
    }

    private fun scheduleRecovery(error: PlaybackException? = null) {
        if (!prefs().getBoolean(C.PLAYER_AUTO_RECOVER_STREAMS, true)) {
            return
        }
        recoveryTimer?.cancel()
        val delay = recoveryPolicy.nextDelayMs()
        recoveryTimer = Timer().apply {
            schedule(delay) {
                Handler(Looper.getMainLooper()).post {
                    recoveryTimer = null
                    val player = mediaSession?.player
                    if (prefs().getBoolean(C.PLAYER_AUTO_RECOVER_STREAMS, true)
                        && player?.playWhenReady == true
                        && player.playerError != null
                    ) {
                        Log.i(TAG, "Recovering playback after error=${error?.errorCode}; attempt=${recoveryPolicy.attempt}")
                        recoverStreamSource()
                    }
                }
            }
        }
    }

    private fun refreshAvailableQualities(player: Player): List<VideoQuality>? {
        val playlist = (player.currentManifest as? HlsManifest)?.multivariantPlaylist
        val list = playlist?.variants?.mapNotNull { variant ->
            val name = variant.format.label?.takeIf { it.isNotBlank() }
                ?: playlist.videos.find { it.groupId == variant.videoGroupId }?.name?.takeIf { it.isNotBlank() }
            if (name != null) {
                VideoQuality(name, variant.format.codecs, variant.format.bitrate, variant.url.toString())
            } else {
                null
            }
        }
        availableQualities = list
        val encodedQualities = list?.let { xtraModule.json.encodeToString(it) }
        if (canonicalPlaybackState?.qualities != encodedQualities) {
            canonicalPlaybackState?.let { state ->
                canonicalPlaybackState = state.copy(qualities = encodedQualities)
                persistPlaybackState(player.currentPosition, !player.playWhenReady)
            }
        }
        return list
    }

    private fun handleStreamTimeline(player: Player) {
        val playlist = (player.currentManifest as? HlsManifest)?.mediaPlaylist ?: return
        val ads = TwitchAdDetector.isAd(playlist)
        val avoidAds = prefs().shouldAvoidTwitchAds()
        val useProxy = proxyPolicy.sourceUsesNetworkProxy(hasConfiguredMediaProxy())
        if (ads != playingAds) {
            playingAds = ads
            Log.d(TAG, "Twitch ad state changed ads=$ads alternate=$usingAlternateStream proxy=${proxyPolicy.mediaPlaylistEnabled}")
            persistPlaybackState(player.currentPosition, !player.playWhenReady)
        }
        if (ads) {
            if (avoidAds && adAvoidanceJob?.isActive != true) {
                val playerTypes = adController.playerTypesForAd(prefs().getString(C.TOKEN_PLAYER_TYPE, "site"))
                if (playerTypes.isNotEmpty()) {
                    tryAlternateStream(playerTypes, useProxy)
                } else {
                    fallbackFromAd(useProxy, suppressAds = true)
                }
            } else if (!avoidAds && useProxy && !proxyPolicy.mediaPlaylistEnabled) {
                fallbackFromAd(useProxy, suppressAds = false)
            }
        } else {
            adController.onCleanPlaylist()
            restoreAdPlayback(player)
            schedulePrimaryStreamRestore()
        }
    }

    private fun applySelectedQuality(player: Player) {
        val quality = selectedQuality ?: return
        val builder = player.trackSelectionParameters.buildUpon()
        when (quality.name) {
            BasePlaybackService.AUDIO_ONLY_QUALITY -> {
                builder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
            }
            BasePlaybackService.CHAT_ONLY_QUALITY -> return
            BasePlaybackService.AUTO_QUALITY -> {
                builder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                    .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
            }
            else -> {
                builder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                player.currentTracks.groups.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }?.let { group ->
                    val targetHeight = quality.name?.substringBefore('p')?.toIntOrNull()
                    val targetFps = quality.name?.substringAfter('p')?.toIntOrNull() ?: 30
                    val targetBitrate = quality.bitrate
                    val selectedIndex = (0 until group.mediaTrackGroup.length)
                        .map { it to group.mediaTrackGroup.getFormat(it) }
                        .sortedWith(compareByDescending<Pair<Int, Format>> { it.second.height }.thenByDescending { it.second.frameRate })
                        .firstOrNull { (_, format) ->
                            (targetHeight == null || format.height <= targetHeight) &&
                                (targetHeight == null || targetFps.toFloat() >= format.frameRate) &&
                                (targetBitrate == null || format.bitrate <= targetBitrate)
                        }?.first
                    if (selectedIndex != null) {
                        builder.setOverrideForType(
                            androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, selectedIndex)
                        )
                    }
                }
            }
        }
        player.trackSelectionParameters = builder.build()
    }

    private fun suppressAdPlayback(player: Player) {
        if (hiddenForAd) return
        hiddenForAd = true
        if (selectedQuality?.name != BasePlaybackService.AUDIO_ONLY_QUALITY) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                .build()
        }
        player.volume = 0f
        persistPlaybackState(player.currentPosition, !player.playWhenReady)
    }

    private fun restoreAdPlayback(player: Player) {
        if (!hiddenForAd) return
        hiddenForAd = false
        if (selectedQuality?.name != BasePlaybackService.AUDIO_ONLY_QUALITY) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                .build()
        }
        player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
        persistPlaybackState(player.currentPosition, !player.playWhenReady)
    }

    private fun fallbackFromAd(useProxy: Boolean, suppressAds: Boolean) {
        val player = mediaSession?.player ?: return
        if (proxyPolicy.mediaPlaylistEnabled) {
            proxyRestoreJob?.cancel()
            proxyRestoreJob = null
            proxyPolicy = proxyPolicy.disableAfterFailure()
            replaceStreamSource(currentUri, preservePosition = true, playWhenReady = player.playWhenReady)
        } else if (proxyPolicy.canEnableAutomatically(useProxy) && !currentUri.isNullOrBlank()) {
            proxyRestoreJob?.cancel()
            proxyPolicy = proxyPolicy.enableAutomatically()
            replaceStreamSource(currentUri, preservePosition = true, playWhenReady = player.playWhenReady)
            proxyRestoreJob = lifecycleScope.launch {
                for (i in 0 until 10) {
                    delay(10_000L)
                    if (currentType != BasePlaybackService.STREAM || !checkPlaylist(currentUri)) break
                }
                if (currentType == BasePlaybackService.STREAM) {
                    proxyPolicy = proxyPolicy.disableAfterCleanPlaylist()
                    persistPlaybackState(player.currentPosition, !player.playWhenReady)
                }
            }
        } else if (suppressAds) {
            suppressAdPlayback(player)
        }
    }

    private fun tryAlternateStream(playerTypes: List<String>, useProxy: Boolean) {
        val channelLogin = currentChannelLogin ?: run {
            fallbackFromAd(useProxy, suppressAds = true)
            return
        }
        mediaSession?.player?.let { suppressAdPlayback(it) }
        adAvoidanceJob = lifecycleScope.launch {
            val candidate = try {
                xtraModule.playerRepository.loadCleanStreamPlaylistUrl(
                    context = this@PlaybackService,
                    networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(this@PlaybackService, prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true)),
                    channelLogin = channelLogin,
                    randomDeviceId = prefs().getBoolean(C.TOKEN_RANDOM_DEVICE_ID, true),
                    xDeviceId = prefs().getString(C.TOKEN_X_DEVICE_ID, "twitch-web-wall-mason"),
                    playerTypes = playerTypes,
                    supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                    proxyPlaybackAccessToken = prefs().getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false),
                    proxyHost = prefs().httpProxyHost(),
                    proxyPort = prefs().httpProxyPort(),
                    proxyUser = prefs().getString(C.PROXY_USER, null),
                    proxyPassword = prefs().getString(C.PROXY_PASSWORD, null),
                    enableIntegrity = prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Alternate Twitch stream resolution failed", e)
                null
            }
            if (candidate != null && currentType == BasePlaybackService.STREAM) {
                primaryStreamRestoreJob?.cancel()
                primaryStreamRestoreJob = null
                usingAlternateStream = true
                replaceStreamSource(candidate.url, preservePosition = true, playWhenReady = mediaSession?.player?.playWhenReady == true)
            } else {
                fallbackFromAd(useProxy, suppressAds = true)
            }
        }
    }

    private fun schedulePrimaryStreamRestore() {
        if (!usingAlternateStream || primaryStreamRestoreJob?.isActive == true || currentType != BasePlaybackService.STREAM) return
        val channelLogin = currentChannelLogin ?: return
        val primaryPlayerType = prefs().getString(C.TOKEN_PLAYER_TYPE, "site") ?: "site"
        primaryStreamRestoreJob = lifecycleScope.launch {
            while (isActive && usingAlternateStream && currentType == BasePlaybackService.STREAM) {
                val candidate = try {
                    xtraModule.playerRepository.loadCleanStreamPlaylistUrl(
                        context = this@PlaybackService,
                        networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(this@PlaybackService, prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true)),
                        channelLogin = channelLogin,
                        randomDeviceId = prefs().getBoolean(C.TOKEN_RANDOM_DEVICE_ID, true),
                        xDeviceId = prefs().getString(C.TOKEN_X_DEVICE_ID, "twitch-web-wall-mason"),
                        playerTypes = listOf(primaryPlayerType),
                        supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                        proxyPlaybackAccessToken = prefs().getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false),
                        proxyHost = prefs().httpProxyHost(),
                        proxyPort = prefs().httpProxyPort(),
                        proxyUser = prefs().getString(C.PROXY_USER, null),
                        proxyPassword = prefs().getString(C.PROXY_PASSWORD, null),
                        enableIntegrity = prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                        requireVerifiedClean = true,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Primary Twitch stream probe failed", e)
                    null
                }
                if (candidate?.verifiedClean == true && currentType == BasePlaybackService.STREAM) {
                    replaceStreamSource(candidate.url, preservePosition = true, playWhenReady = mediaSession?.player?.playWhenReady == true)
                    usingAlternateStream = false
                    adController.reset()
                    restoreAdPlayback(mediaSession?.player ?: return@launch)
                    break
                }
                delay(10_000L)
            }
        }
    }

    private suspend fun resolveFreshStreamUrl(useNetworkProxy: Boolean = true): String? {
        val channelLogin = currentChannelLogin ?: return null
        val customProxyUrl = prefs().getString(C.PLAYER_PROXY_URL, null)
        if (canonicalPlaybackState?.useCustomProxy == true) {
            if (!customProxyUrl.isNullOrBlank()) {
                return customProxyUrl.replace("\$channel", channelLogin)
            }
            canonicalPlaybackState = canonicalPlaybackState?.copy(useCustomProxy = false)
        }
        val proxyHost = if (useNetworkProxy) prefs().httpProxyHost() else null
        val proxyPort = if (useNetworkProxy) prefs().httpProxyPort() else null
        val proxyUser = if (useNetworkProxy) prefs().getString(C.PROXY_USER, null) else null
        val proxyPassword = if (useNetworkProxy) prefs().getString(C.PROXY_PASSWORD, null) else null
        return try {
            xtraModule.playerRepository.loadStreamPlaylistUrl(
                context = this@PlaybackService,
                networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                gqlHeaders = TwitchApiHelper.getGQLHeaders(this@PlaybackService, prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true)),
                channelLogin = channelLogin,
                randomDeviceId = prefs().getBoolean(C.TOKEN_RANDOM_DEVICE_ID, true),
                xDeviceId = prefs().getString(C.TOKEN_X_DEVICE_ID, "twitch-web-wall-mason"),
                playerType = prefs().getString(C.TOKEN_PLAYER_TYPE, "site"),
                supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                proxyPlaybackAccessToken = prefs().getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false),
                proxyHost = proxyHost,
                proxyPort = proxyPort,
                proxyUser = proxyUser,
                proxyPassword = proxyPassword,
                enableIntegrity = prefs().getBoolean(C.ENABLE_INTEGRITY, false),
            ).takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Fresh Twitch stream resolution failed", e)
            val fallbackTypes = TwitchAdController.PLAYER_TYPES.filterNot {
                it == prefs().getString(C.TOKEN_PLAYER_TYPE, "site")
            }
            val fallback = try {
                xtraModule.playerRepository.loadCleanStreamPlaylistUrl(
                    context = this@PlaybackService,
                    networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(this@PlaybackService, prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true)),
                    channelLogin = channelLogin,
                    randomDeviceId = prefs().getBoolean(C.TOKEN_RANDOM_DEVICE_ID, true),
                    xDeviceId = prefs().getString(C.TOKEN_X_DEVICE_ID, "twitch-web-wall-mason"),
                    playerTypes = fallbackTypes,
                    supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                    proxyPlaybackAccessToken = prefs().getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false),
                    proxyHost = proxyHost,
                    proxyPort = proxyPort,
                    proxyUser = proxyUser,
                    proxyPassword = proxyPassword,
                    enableIntegrity = prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Fallback Twitch stream resolution failed", e)
                null
            }
            if (fallback != null) {
                usingAlternateStream = true
                fallback.url
            } else {
                null
            }
        }
    }

    private fun recoverStreamSource() {
        if (currentType != BasePlaybackService.STREAM) {
            mediaSession?.player?.prepare()
            return
        }
        restorePlaybackJob?.cancel()
        restorePlaybackJob = lifecycleScope.launch {
            val player = mediaSession?.player ?: return@launch
            val bypassNetworkProxy = disableProxyForRecovery()
            val url = resolveFreshStreamUrl(useNetworkProxy = !bypassNetworkProxy)
            if (url != null) {
                replaceStreamSource(url, preservePosition = true, playWhenReady = player.playWhenReady)
            } else {
                player.prepare()
            }
        }
    }

    private fun disableProxyForRecovery(): Boolean {
        val customProxyEnabled = canonicalPlaybackState?.useCustomProxy == true
        val bypassNetworkProxy =
            proxyPolicy.mediaPlaylistEnabled || proxyPolicy.automaticFallbackDisabled || customProxyEnabled
        var changed = false
        if (proxyPolicy.mediaPlaylistEnabled || customProxyEnabled) {
            proxyPolicy = proxyPolicy.disableAfterFailure()
            changed = true
        }
        if (customProxyEnabled) {
            canonicalPlaybackState = canonicalPlaybackState?.copy(useCustomProxy = false)
            changed = true
        }
        if (changed) {
            val player = mediaSession?.player
            persistPlaybackState(player?.currentPosition, player?.playWhenReady != true)
        }
        if (bypassNetworkProxy) {
            Log.i(TAG, "Disabling Twitch proxy for source recovery")
        }
        return bypassNetworkProxy
    }

    private fun replaceStreamSource(url: String?, preservePosition: Boolean, playWhenReady: Boolean) {
        val player = canonicalPlayer ?: return
        if (url.isNullOrBlank()) return
        val position = if (preservePosition) player.currentPosition else 0L
        recoveryTimer?.cancel()
        recoveryTimer = null
        Log.d(TAG, "Replacing Twitch source type=$currentType preservePosition=$preservePosition alternate=$usingAlternateStream proxy=${proxyPolicy.mediaPlaylistEnabled}")
        currentUri = url
        canonicalPlaybackState = canonicalPlaybackState?.copy(videoUrl = url, position = position, paused = !playWhenReady)
        player.setMediaSource(createStreamMediaSource(url))
        player.prepare()
        player.playWhenReady = playWhenReady
        if (preservePosition) player.seekTo(position)
        persistPlaybackState(position, !playWhenReady)
    }

    /**
     * MediaSession playback resumption restores MediaItems through ExoPlayer's
     * MediaSource.Factory. Keep that factory on the canonical player so a cold
     * media-button resume uses the same Twitch-aware data sources as normal
     * playback instead of falling back to a stock network client.
     */
    private fun createCanonicalMediaSourceFactory(): MediaSource.Factory {
        return object : MediaSource.Factory {
            private var drmSessionManagerProvider: DrmSessionManagerProvider? = null
            private var loadErrorHandlingPolicy: LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(6)

            override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
                drmSessionManagerProvider = provider
                return this
            }

            override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
                loadErrorHandlingPolicy = policy
                return this
            }

            override fun getSupportedTypes(): IntArray = DefaultMediaSourceFactory(
                DefaultDataSource.Factory(this@PlaybackService, createPlaybackDataSourceFactory()),
            ).getSupportedTypes()

            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                val mimeType = mediaItem.localConfiguration?.mimeType
                val isHls = mimeType == MimeTypes.APPLICATION_M3U8 ||
                    mediaItem.mediaId.startsWith("stream:") ||
                    mediaItem.mediaId.startsWith("video:")
                val dataSourceFactory = DefaultDataSource.Factory(this@PlaybackService, createPlaybackDataSourceFactory())
                return if (isHls) {
                    HlsMediaSource.Factory(dataSourceFactory).apply {
                        setPlaylistParserFactory(TwitchHlsPlaylistParserFactory())
                        setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                        drmSessionManagerProvider?.let { setDrmSessionManagerProvider(it) }
                    }.createMediaSource(mediaItem)
                } else {
                    DefaultMediaSourceFactory(dataSourceFactory).apply {
                        setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                        drmSessionManagerProvider?.let { setDrmSessionManagerProvider(it) }
                    }.createMediaSource(mediaItem)
                }
            }
        }
    }

    private fun hasConfiguredMediaProxy(): Boolean =
        !prefs().httpProxyHost().isNullOrBlank() && prefs().httpProxyPort() != null

    private fun createPlaybackDataSourceFactory(): HttpDataSource.Factory {
        val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val proxyHost = prefs().httpProxyHost().orEmpty()
        val proxyPort = prefs().httpProxyPort() ?: 0
        val proxyUser = prefs().getString(C.PROXY_USER, null)
        val proxyPassword = prefs().getString(C.PROXY_PASSWORD, null)
        val proxyConfigured = proxyHost.isNotBlank() && proxyPort != 0
        val proxyMultivariantPlaylist = proxyPolicy.sourceUsesMultivariantProxy(
            preferenceEnabled = prefs().getBoolean(C.PROXY_MULTIVARIANT_PLAYLIST, false),
            proxyConfigured = proxyConfigured,
        )
        val useMediaPlaylistProxy = proxyPolicy.sourceUsesMediaPlaylistProxy(
            proxyConfigured = proxyConfigured,
        )

        val factory: HttpDataSource.Factory = when {
            networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                val proxyClient = if (proxyMultivariantPlaylist || useMediaPlaylistProxy) {
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
                                    },
                                )
                            ),
                            ProxyOptions.ALL_PROXIES_FAILED_BEHAVIOR_DISALLOW_DIRECT,
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
                                    return if (Regex(TwitchPlaybackConstants.MULTIVARIANT_PLAYLIST_REGEX).matches(u.host)) {
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
                val mediaPlaylistProxyClient = if (useMediaPlaylistProxy && proxyClient == null) {
                    xtraModule.okHttpClient.value.newBuilder().apply {
                        proxySelector(
                            object : ProxySelector() {
                                override fun select(u: URI): List<Proxy> {
                                    return if (Regex(TwitchPlaybackConstants.MEDIA_PLAYLIST_REGEX).matches(u.host)) {
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
                HttpEngineDataSource.Factory(
                    xtraModule.httpEngine.value,
                    xtraModule.cronetExecutor.value,
                    proxyMultivariantPlaylist,
                    useMediaPlaylistProxy,
                    proxyClient,
                    multivariantPlaylistProxyClient,
                    mediaPlaylistProxyClient,
                ) { useMediaPlaylistProxy }
            }
            networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                val proxyClient = if ((proxyMultivariantPlaylist || useMediaPlaylistProxy) && CronetProvider.getAllProviders(application).any { it.isEnabled }) {
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
                                    },
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
                                    return if (Regex(TwitchPlaybackConstants.MULTIVARIANT_PLAYLIST_REGEX).matches(u.host)) {
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
                val mediaPlaylistProxyClient = if (useMediaPlaylistProxy && proxyClient == null) {
                    xtraModule.okHttpClient.value.newBuilder().apply {
                        proxySelector(
                            object : ProxySelector() {
                                override fun select(u: URI): List<Proxy> {
                                    return if (Regex(TwitchPlaybackConstants.MEDIA_PLAYLIST_REGEX).matches(u.host)) {
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
                CronetDataSource.Factory(
                    xtraModule.cronetEngine.value,
                    xtraModule.cronetExecutor.value,
                    proxyMultivariantPlaylist,
                    useMediaPlaylistProxy,
                    proxyClient,
                    multivariantPlaylistProxyClient,
                    mediaPlaylistProxyClient,
                ) { useMediaPlaylistProxy }
            }
            else -> {
                val multivariantPlaylistProxyClient = if (proxyMultivariantPlaylist) {
                    xtraModule.okHttpClient.value.newBuilder().apply {
                        proxySelector(
                            object : ProxySelector() {
                                override fun select(u: URI): List<Proxy> {
                                    return if (Regex(TwitchPlaybackConstants.MULTIVARIANT_PLAYLIST_REGEX).matches(u.host)) {
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
                val mediaPlaylistProxyClient = if (useMediaPlaylistProxy) {
                    xtraModule.okHttpClient.value.newBuilder().apply {
                        proxySelector(
                            object : ProxySelector() {
                                override fun select(u: URI): List<Proxy> {
                                    return if (Regex(TwitchPlaybackConstants.MEDIA_PLAYLIST_REGEX).matches(u.host)) {
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
                OkHttpDataSource.Factory(
                    multivariantPlaylistProxyClient ?: xtraModule.okHttpClient.value,
                    mediaPlaylistProxyClient,
                ) { useMediaPlaylistProxy }
            }
        }
        prefs().getString(C.PLAYER_STREAM_HEADERS, null)?.let {
            try {
                JSONObject(it).let { json ->
                    hashMapOf<String, String>().apply {
                        json.keys().forEach { key -> put(key, json.optString(key)) }
                    }
                }
            } catch (e: Exception) {
                null
            }
        }?.let(factory::setDefaultRequestProperties)
        return factory
    }

    private fun createStreamMediaSource(url: String): MediaSource {
        val dataSourceFactory = createPlaybackDataSourceFactory()
        val mediaItem = MediaItem.Builder()
            .setMediaId("stream:${currentStreamId.orEmpty()}")
            .setUri(url.toUri())
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(2000L).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(currentTitle)
                    .setArtist(currentChannelName)
                    .setArtworkUri(currentChannelImage?.toUri())
                    .build()
            )
            .build()
        return HlsMediaSource.Factory(DefaultDataSource.Factory(this, dataSourceFactory))
            .setPlaylistParserFactory(TwitchHlsPlaylistParserFactory())
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
            .createMediaSource(mediaItem)
    }

    private suspend fun checkPlaylist(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val request = okhttp3.Request.Builder().url(url).build()
            xtraModule.okHttpClient.value.newCall(request).execute().use { response ->
                response.isSuccessful && response.body.string().let { body ->
                    TwitchAdDetector.isAd(com.github.andreyasadchy.xtra.util.m3u8.PlaylistUtils.parseMediaPlaylist(body.byteInputStream()))
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun persistPlaybackState(position: Long?, paused: Boolean) {
        if (!::xtraModule.isInitialized || currentType == null) {
            return
        }
        val state = (canonicalPlaybackState ?: PlaybackState(
            type = currentType,
            streamId = currentStreamId,
            videoId = videoId?.toString(),
            clipId = clipId,
            offlineVideoId = offlineVideoId,
            channelId = currentChannelId,
            channelLogin = currentChannelLogin,
            channelName = currentChannelName,
            channelImage = currentChannelImage,
            title = currentTitle,
            videoUrl = currentUri,
        )).copy(
            position = position,
            paused = paused,
            videoUrl = currentUri ?: canonicalPlaybackState?.videoUrl,
        )
        canonicalPlaybackState = state
        xtraModule.playbackPersistence.savePlaybackState(state)
    }

    private fun initializeCanonicalState(type: String, extras: Bundle, position: Long?, paused: Boolean) {
        val streamId = extras.getString(STREAM_ID)
        val videoId = when (type) {
            BasePlaybackService.CLIP -> extras.getString(VIDEO_ID)
            BasePlaybackService.VIDEO -> runCatching { extras.getLong(VIDEO_ID) }
                .getOrDefault(0L)
                .takeIf { it != 0L }
                ?.toString()
            else -> null
        }
        val clipId = extras.getString(CLIP_ID)
        val offlineVideoId = extras.getInt(VIDEO_ID).takeIf { type == BasePlaybackService.OFFLINE_VIDEO && it != 0 }
        val state = PlaybackState(
            type = type,
            streamId = streamId,
            videoId = videoId,
            clipId = clipId,
            offlineVideoId = offlineVideoId,
            channelId = extras.getString(CHANNEL_ID),
            channelLogin = extras.getString(CHANNEL_LOGIN),
            channelName = extras.getString(CHANNEL_NAME),
            channelImage = extras.getString(CHANNEL_LOGO),
            gameId = extras.getString(GAME_ID),
            gameSlug = extras.getString(GAME_SLUG),
            gameName = extras.getString(GAME_NAME),
            title = extras.getString(TITLE),
            thumbnail = extras.getString(THUMBNAIL),
            createdAt = extras.getString(CREATED_AT),
            viewerCount = extras.getInt(VIEWER_COUNT).takeIf { extras.containsKey(VIEWER_COUNT) && it >= 0 },
            durationSeconds = extras.getInt(DURATION_SECONDS).takeIf { extras.containsKey(DURATION_SECONDS) && it > 0 },
            videoType = extras.getString(VIDEO_TYPE),
            videoOffsetSeconds = extras.getInt(VIDEO_OFFSET_SECONDS).takeIf { extras.containsKey(VIDEO_OFFSET_SECONDS) && it >= 0 },
            videoCreatedAt = extras.getString(VIDEO_CREATED_AT),
            videoAnimatedPreviewURL = extras.getString(VIDEO_ANIMATED_PREVIEW),
            videoUrl = extras.getString(URI),
            position = position,
            paused = paused,
            useCustomProxy = extras.getBoolean(USE_CUSTOM_PROXY),
            skipAccessToken = extras.getBoolean(SKIP_ACCESS_TOKEN),
        )
        canonicalPlaybackState = state
        selectedQuality = null
        availableQualities = null
        restoreQuality = false
        restorePlaylist = false
        recoveryTimer?.cancel()
        recoveryTimer = null
        recoveryPolicy.reset()
        restorePlaybackJob?.cancel()
        restorePlaybackJob = null
        adAvoidanceJob?.cancel()
        adAvoidanceJob = null
        primaryStreamRestoreJob?.cancel()
        primaryStreamRestoreJob = null
        proxyRestoreJob?.cancel()
        proxyRestoreJob = null
        adController.reset()
        playingAds = false
        hiddenForAd = false
        usingAlternateStream = false
        proxyPolicy = PlaybackProxyPolicy(
            mediaPlaylistEnabled = type == BasePlaybackService.STREAM && hasConfiguredMediaProxy(),
        )
    }

    private fun restoreCanonicalState(state: PlaybackState) {
        currentType = state.type
        currentStreamId = state.streamId
        videoId = state.videoId?.toLongOrNull()
        clipId = state.clipId
        offlineVideoId = state.offlineVideoId
        currentUri = state.videoUrl
        currentTitle = state.title
        currentChannelId = state.channelId
        currentChannelLogin = state.channelLogin
        currentChannelName = state.channelName
        currentChannelImage = state.channelImage
        canonicalPlaybackState = state
        selectedQuality = decodeQuality(state.quality)
        availableQualities = decodeQualities(state.qualities)
        restoreQuality = state.restoreQuality
        restorePlaylist = state.restorePlaylist
        playingAds = false
        hiddenForAd = false
        usingAlternateStream = false
        adController.reset()
        proxyPolicy = PlaybackProxyPolicy(
            mediaPlaylistEnabled = state.type == BasePlaybackService.STREAM && hasConfiguredMediaProxy(),
        )
        setViewingMetadata(
            when (state.type) {
                BasePlaybackService.STREAM -> ViewingPlaybackMetadata.CONTENT_TYPE_LIVE
                BasePlaybackService.CLIP -> ViewingPlaybackMetadata.CONTENT_TYPE_CLIP
                BasePlaybackService.OFFLINE_VIDEO -> ViewingPlaybackMetadata.CONTENT_TYPE_OFFLINE_VIDEO
                else -> ViewingPlaybackMetadata.CONTENT_TYPE_VOD
            },
            state.streamId ?: state.clipId ?: state.videoId ?: state.offlineVideoId?.toString(),
            Bundle().apply {
                putString(CHANNEL_ID, state.channelId)
                putString(CHANNEL_LOGIN, state.channelLogin)
                putString(CHANNEL_NAME, state.channelName)
                putString(CHANNEL_LOGO, state.channelImage)
                putString(TITLE, state.title)
            },
        )
    }

    private fun mediaItemForState(state: PlaybackState, url: String): MediaItem {
        return MediaItem.Builder().apply {
            setMediaId(
                when (state.type) {
                    BasePlaybackService.STREAM -> "stream:${state.streamId.orEmpty()}"
                    BasePlaybackService.VIDEO -> "video:${state.videoId.orEmpty()}"
                    BasePlaybackService.CLIP -> "clip:${state.clipId.orEmpty()}"
                    BasePlaybackService.OFFLINE_VIDEO -> "offline:${state.offlineVideoId ?: 0}"
                    else -> "playback"
                }
            )
            setUri(url.toUri())
            if (state.type == BasePlaybackService.STREAM || state.type == BasePlaybackService.VIDEO) {
                setMimeType(MimeTypes.APPLICATION_M3U8)
            }
            if (state.type == BasePlaybackService.STREAM) {
                setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(2000L).build())
            }
            setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(state.title)
                    .setArtist(state.channelName)
                    .setArtworkUri(state.channelImage?.toUri())
                    .build()
            )
        }.build()
    }

    private fun syncPlaybackState(extras: Bundle) {
        val current = canonicalPlaybackState ?: return
        mediaSession?.player?.currentMediaItem?.localConfiguration?.uri?.toString()?.let { currentUri = it }
        val updated = current.copy(
            qualities = extras.getString(QUALITIES) ?: current.qualities,
            quality = extras.getString(QUALITY) ?: current.quality,
            previousQuality = extras.getString(PREVIOUS_QUALITY) ?: current.previousQuality,
            restoreQuality = if (extras.containsKey(RESTORE_QUALITY)) extras.getBoolean(RESTORE_QUALITY) else current.restoreQuality,
            playlistUrl = if (extras.containsKey(PLAYLIST_URL)) extras.getString(PLAYLIST_URL) else current.playlistUrl,
            restorePlaylist = if (extras.containsKey(RESTORE_PLAYLIST)) extras.getBoolean(RESTORE_PLAYLIST) else current.restorePlaylist,
            useCustomProxy = if (extras.containsKey(USE_CUSTOM_PROXY)) extras.getBoolean(USE_CUSTOM_PROXY) else current.useCustomProxy,
            skipAccessToken = if (extras.containsKey(SKIP_ACCESS_TOKEN)) extras.getBoolean(SKIP_ACCESS_TOKEN) else current.skipAccessToken,
            videoUrl = currentUri ?: current.videoUrl,
            position = mediaSession?.player?.currentPosition ?: current.position,
            paused = !(mediaSession?.player?.playWhenReady ?: !current.paused),
        )
        canonicalPlaybackState = updated
        selectedQuality = decodeQuality(updated.quality)
        availableQualities = decodeQualities(updated.qualities)
        restoreQuality = updated.restoreQuality
        restorePlaylist = updated.restorePlaylist
        persistPlaybackState(updated.position, updated.paused)
    }

    private fun decodeQuality(value: String?): VideoQuality? {
        return value?.let { runCatching { xtraModule.json.decodeFromString<VideoQuality>(it) }.getOrNull() }
    }

    private fun decodeQualities(value: String?): List<VideoQuality>? {
        return value?.let {
            runCatching {
                xtraModule.json.decodeFromString<kotlinx.serialization.json.JsonArray>(it).map { item ->
                    xtraModule.json.decodeFromJsonElement<VideoQuality>(item)
                }
            }.getOrNull()
        }
    }

    private fun playbackStateBundle(): Bundle {
        return Bundle().apply {
            putBoolean(PLAYING_ADS, playingAds)
            putBoolean(HIDDEN_FOR_AD, hiddenForAd)
            putBoolean(USING_ALTERNATE_STREAM, usingAlternateStream)
            putBoolean(USING_PROXY, proxyPolicy.mediaPlaylistEnabled)
            putString(QUALITY, canonicalPlaybackState?.quality)
            putString(PREVIOUS_QUALITY, canonicalPlaybackState?.previousQuality)
            putString(QUALITIES, canonicalPlaybackState?.qualities)
            putString(PLAYLIST_URL, canonicalPlaybackState?.playlistUrl)
            putBoolean(RESTORE_QUALITY, canonicalPlaybackState?.restoreQuality == true)
            putBoolean(RESTORE_PLAYLIST, canonicalPlaybackState?.restorePlaylist == true)
            putBoolean(USE_CUSTOM_PROXY, canonicalPlaybackState?.useCustomProxy == true)
            putBoolean(SKIP_ACCESS_TOKEN, canonicalPlaybackState?.skipAccessToken == true)
        }
    }

    private fun clearPersistedPlayback() {
        if (::xtraModule.isInitialized) {
            xtraModule.playbackPersistence.deletePlaybackStates()
        }
        currentType = null
        currentStreamId = null
        currentUri = null
        currentTitle = null
        currentChannelId = null
        currentChannelLogin = null
        currentChannelName = null
        currentChannelImage = null
        videoId = null
        clipId = null
        offlineVideoId = null
        canonicalPlaybackState = null
        selectedQuality = null
        availableQualities = null
        recoveryTimer?.cancel()
        recoveryTimer = null
        recoveryPolicy.reset()
        restorePlaybackJob?.cancel()
        restorePlaybackJob = null
        adAvoidanceJob?.cancel()
        adAvoidanceJob = null
        primaryStreamRestoreJob?.cancel()
        primaryStreamRestoreJob = null
        proxyRestoreJob?.cancel()
        proxyRestoreJob = null
        adController.reset()
        playingAds = false
        hiddenForAd = false
        usingAlternateStream = false
        proxyPolicy = PlaybackProxyPolicy()
    }

    private fun setPresentation(newPresentation: PlayerPresentation) {
        if (presentation == newPresentation) {
            return
        }
        Log.d(TAG, "presentation $presentation -> $newPresentation")
        presentation = newPresentation
    }

    private fun setViewingMetadata(
        contentType: String,
        contentId: String?,
        extras: Bundle,
    ) {
        finishViewingStats()
        currentChannelId = extras.getString(CHANNEL_ID)
        currentChannelLogin = extras.getString(CHANNEL_LOGIN)
        currentChannelName = extras.getString(CHANNEL_NAME)
        currentChannelImage = extras.getString(CHANNEL_LOGO)
        currentTitle = extras.getString(TITLE) ?: currentTitle
        viewingChannelId = currentChannelId
        viewingChannelLogin = currentChannelLogin
        viewingChannelName = currentChannelName
        viewingChannelImage = currentChannelImage
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
                contentType = contentType,
                contentId = viewingContentId,
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
            contentType = contentType,
            contentId = viewingContentId,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition()
        val player = mediaSession?.player
        val keepPlayback = player?.playWhenReady == true
                && player.playbackState != Player.STATE_ENDED
                && prefs().getBoolean(C.PLAYER_KEEP_PLAYING_AFTER_TASK_REMOVED, true)
                && prefs().getBoolean(C.SETTINGS_BACKGROUND_PLAYBACK, true)
        if (keepPlayback) {
            setPresentation(PlayerPresentation.BACKGROUND)
            Log.d(TAG, "Keeping playback alive after task removal")
            return
        }
        player?.clearMediaItems()
        clearPersistedPlayback()
        runAfterPlaybackPersistence {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Destroying canonical player id=${canonicalPlayer?.let(System::identityHashCode)}")
        savePosition()
        if (::xtraModule.isInitialized) {
            xtraModule.viewingStatsRecorder.release(viewingStatsSourceId)
        }
        recoveryTimer?.cancel()
        recoveryTimer = null
        restorePlaybackJob?.cancel()
        restorePlaybackJob = null
        adAvoidanceJob?.cancel()
        adAvoidanceJob = null
        primaryStreamRestoreJob?.cancel()
        primaryStreamRestoreJob = null
        proxyRestoreJob?.cancel()
        proxyRestoreJob = null
        sleepTimer?.cancel()
        savePositionTimer?.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        canonicalPlayer = null
        super.onDestroy()
    }

    companion object {
        const val START_STREAM = "startStream"
        const val START_VIDEO = "startVideo"
        const val START_CLIP = "startClip"
        const val START_OFFLINE_VIDEO = "startOfflineVideo"
        const val TOGGLE_DYNAMICS_PROCESSING = "toggleDynamicsProcessing"
        const val TOGGLE_PROXY = "toggleProxy"
        const val SET_BACKGROUND_PLAYBACK = "setBackgroundPlayback"
        const val SET_PRESENTATION = "setPresentation"
        const val CLEAR_PLAYBACK = "clearPlayback"
        const val SYNC_PLAYBACK_STATE = "syncPlaybackState"
        const val GET_PLAYBACK_STATE = "getPlaybackState"
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
        const val GAME_SLUG = "gameSlug"
        const val GAME_NAME = "gameName"
        const val THUMBNAIL = "thumbnail"
        const val CREATED_AT = "createdAt"
        const val VIEWER_COUNT = "viewerCount"
        const val DURATION_SECONDS = "durationSeconds"
        const val VIDEO_TYPE = "videoType"
        const val VIDEO_OFFSET_SECONDS = "videoOffsetSeconds"
        const val VIDEO_CREATED_AT = "videoCreatedAt"
        const val VIDEO_ANIMATED_PREVIEW = "videoAnimatedPreview"
        const val VIDEO_URL = "videoUrl"
        const val USING_PROXY = "usingProxy"
        const val USING_ALTERNATE_STREAM = "usingAlternateStream"
        const val HIDDEN_FOR_AD = "hiddenForAd"
        const val PLAYING_ADS = "playingAds"
        const val QUALITY = "quality"
        const val PREVIOUS_QUALITY = "previousQuality"
        const val QUALITIES = "qualities"
        const val RESTORE_QUALITY = "restoreQuality"
        const val PLAYLIST_URL = "playlistUrl"
        const val RESTORE_PLAYLIST = "restorePlaylist"
        const val USE_CUSTOM_PROXY = "useCustomProxy"
        const val SKIP_ACCESS_TOKEN = "skipAccessToken"
        const val PLAY_WHEN_READY = "playWhenReady"
        const val BACKGROUND_PLAYBACK = "backgroundPlayback"
        const val PRESENTATION = "presentation"
        const val DURATION = "duration"
        const val NAMES = "names"
        const val CODECS = "codecs"
        const val BITRATES = "bitrates"
        const val URLS = "urls"

        const val REQUEST_CODE_RESUME = 2

        private const val TAG = "PlaybackService"
    }
}
