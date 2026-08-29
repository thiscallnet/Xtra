package com.github.andreyasadchy.xtra.ui.player

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.audiofx.DynamicsProcessing
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.http.HttpEngine
import android.net.http.ProxyOptions
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.StatFs
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.ParsingLoadable
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.player.lowlatency.CronetDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.HlsPlaylistParser
import com.github.andreyasadchy.xtra.player.lowlatency.HttpEngineDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.OkHttpDataSource
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.clip.ClipPreparationRepository
import com.github.andreyasadchy.xtra.ui.player.clip.ClipSizeEstimator
import com.github.andreyasadchy.xtra.ui.player.clip.ClipSnapshot
import com.github.andreyasadchy.xtra.ui.player.clip.HlsClipSnapshotMapper
import com.github.andreyasadchy.xtra.ui.player.clip.LiveClipBufferManager
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.MediaButtonReceiver
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.readBytesLimited
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.httpProxyHost
import com.github.andreyasadchy.xtra.util.httpProxyPort
import com.github.andreyasadchy.xtra.util.m3u8.PlaylistUtils
import com.github.andreyasadchy.xtra.util.m3u8.TwitchAdDetector
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.shouldAvoidTwitchAds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Request
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import org.chromium.net.QuicOptions
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Timer
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate
import kotlin.math.floor
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(UnstableApi::class)
class ExoPlayerService : BasePlaybackService() {

    var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var notificationManager: NotificationManager? = null
    private var artworkUri: String? = null
    private var cachedBitmap: Bitmap? = null
    private var bitmapLoadJob: Job? = null

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var sleepTimer: Timer? = null
    private var sleepTimerEndTime = 0L
    private var lastSavedPosition: Long? = null
    private var savePositionTimer: Timer? = null
    private var stopServiceTimer: Timer? = null

    private var playingAds = false
    private var proxyMediaPlaylist = false
    private var stopProxy = false
    private var hidden = false
    private val adController = TwitchAdController()
    private var adAvoidanceJob: Job? = null
    private var primaryStreamRestoreJob: Job? = null
    private var usingAlternateStream = false
    private var backupQualities: List<String>? = null
    private var liveRewindPositionOverride: Long? = null
    private var updateQualities = false
    private var created = false
    private var resumeWhenForeground = false
    private val videoOutputState = VideoOutputState()
    private var streamRecoveryJob: Job? = null
    private var streamRecoveryAttempt = 0
    private val initialRestore = CompletableDeferred<Unit>()
    private val liveClipBufferManager = LiveClipBufferManager()
    private var hlsClipDataSourceFactory: DataSource.Factory? = null
    private var liveClipPreparation: Deferred<ClipPreparationRepository.PreparedLiveClip>? = null
    private var vodClipSnapshot: ClipSnapshot? = null
    private var vodClipPreparation: Deferred<ClipPreparationRepository.PreparedLiveClip>? = null

    override fun isViewingPlaybackPlaying(): Boolean = player?.isPlaying == true

    override fun isViewingPlaybackBuffering(): Boolean = player?.playbackState == Player.STATE_BUFFERING

    val vaftActive: Boolean
        get() = type == STREAM && prefs().shouldAvoidTwitchAds() && (playingAds || usingAlternateStream || hidden)

    interface Listener {
        fun started()
        fun loaded()
        fun changePlayerMode()
        fun updateQualityStatus() {}
        fun updateLiveClipStatus() {}
        fun toast(resId: Int, duration: Int)
        fun updateVideoInfo()
    }

    var serviceListener: Listener? = null

    override fun onCreate() {
        super.onCreate()
        xtraModule = (application as XtraApp).xtraModule
        lifecycleScope.launch(Dispatchers.IO) {
            ClipPreparationRepository.cleanupStale(File(cacheDir, LIVE_CLIP_DIRECTORY))
            ClipPreparationRepository.cleanupStale(File(cacheDir, VOD_CLIP_DIRECTORY))
        }
    }

    private fun create(restorePauseState: Boolean) {
        if (!created) {
            created = true
            val playerListener = object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    dynamicsProcessing?.let {
                        it.release()
                        dynamicsProcessing = null
                    }
                    if (prefs().getBoolean(C.PLAYER_AUDIO_COMPRESSOR, false)) {
                        reinitializeDynamicsProcessing(audioSessionId)
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    updatePlaybackState()
                    updateMetadata()
                }

                override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                    updateMetadata()
                    updateNotification()
                }

                override fun onTracksChanged(tracks: Tracks) {
                    if (!tracks.isEmpty) {
                        if (!loaded) {
                            loaded = true
                            serviceListener?.loaded()
                            toggleSubtitles(prefs().getBoolean(C.PLAYER_SUBTITLES_ENABLED, false))
                        }
                        if (qualities?.find { it.name == AUTO_QUALITY } != null && quality?.name != AUDIO_ONLY_QUALITY && !hidden) {
                            changeQuality(
                                quality,
                                resetLiveClipGeneration = false,
                                persistSavedQuality = false,
                            )
                        }
                    }
                }

                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    updatePlaybackState()
                    updateMetadata()
                    updateNotification()
                    if (type == STREAM && !liveRewindActive) {
                        (player?.currentManifest as? HlsManifest)?.let { manifest ->
                            configureLiveClipBuffer()
                            liveClipBufferManager.capture(manifest)
                            serviceListener?.updateLiveClipStatus()
                        }
                    } else if (type == VIDEO) {
                        serviceListener?.updateLiveClipStatus()
                    }
                    if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && !timeline.isEmpty && qualities?.find { it.name == AUTO_QUALITY } != null) {
                        updateQualities = quality?.name != AUDIO_ONLY_QUALITY
                    }
                    if (qualities.isNullOrEmpty() || updateQualities) {
                        val playlist = (player?.currentManifest as? HlsManifest)?.multivariantPlaylist
                        val list = playlist?.variants?.mapNotNull { variant ->
                            val name = variant.format.label?.takeIf { it.isNotBlank() }
                                ?: playlist.videos.find { it.groupId == variant.videoGroupId }?.name?.takeIf { it.isNotBlank() }
                            if (name != null) {
                                VideoQuality(name, variant.format.codecs, variant.format.bitrate, variant.url.toString())
                            } else null
                        }
                        if (!list.isNullOrEmpty()) {
                            qualities = list.asSequence()
                                .sortedByDescending {
                                    it.bitrate
                                }
                                .sortedByDescending {
                                    it.name?.substringAfter("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                                }
                                .sortedByDescending {
                                    it.name?.substringBefore("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                                }
                                .toMutableList().apply {
                                    add(0, VideoQuality(AUTO_QUALITY))
                                    find { it.name.equals("source", true) }?.let { source ->
                                        remove(source)
                                        add(1, VideoQuality(SOURCE_QUALITY, source.codecs, source.bitrate, source.url))
                                    }
                                    val audio = find { it.name?.startsWith("audio", true) == true }
                                    audio?.let { remove(it) }
                                    add(VideoQuality(AUDIO_ONLY_QUALITY, audio?.codecs, audio?.bitrate, audio?.url))
                                }
                            setDefaultQuality()
                            serviceListener?.changePlayerMode()
                            if (quality?.name == AUDIO_ONLY_QUALITY) {
                                changeQuality(quality, persistSavedQuality = false)
                            }
                        }
                        if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) {
                            updateQualities = false
                        }
                    }
                    if (type == STREAM && !liveRewindActive) {
                        val avoidAds = prefs().shouldAvoidTwitchAds()
                        val suppressAds = avoidAds
                        val useProxy = prefs().httpProxyHost() != null
                                && prefs().httpProxyPort() != null
                        if (suppressAds || useProxy) {
                            val playlist = (player?.currentManifest as? HlsManifest)?.mediaPlaylist
                            val ads = playlist?.let { TwitchAdDetector.isAd(it) } == true
                            val oldValue = playingAds
                            playingAds = ads
                            if (ads != oldValue) {
                                logAd("state channel=${channelLogin ?: "null"} ads=$ads avoid=$avoidAds proxy=$useProxy hidden=$hidden playerType=${prefs().getString(C.TOKEN_PLAYER_TYPE, "site")}")
                                serviceListener?.updateQualityStatus()
                            }
                            if (ads) {
                                if (avoidAds) {
                                    if (adAvoidanceJob?.isActive != true) {
                                        val playerTypes = adController.playerTypesForAd(
                                            prefs().getString(C.TOKEN_PLAYER_TYPE, "site")
                                        )
                                        logAd("ad detected channel=${channelLogin ?: "null"} candidates=${playerTypes.joinToString()} jobActive=${adAvoidanceJob?.isActive == true}")
                                        if (playerTypes.isNotEmpty()) {
                                            suppressAdPlayback()
                                            tryAlternateStream(playerTypes, useProxy)
                                        } else {
                                            fallbackFromAd(useProxy, suppressAds)
                                        }
                                    }
                                } else if (!oldValue) {
                                    fallbackFromAd(useProxy, suppressAds)
                                }
                            } else {
                                adController.onCleanPlaylist()
                                restoreAdPlayback()
                                schedulePrimaryStreamRestore()
                            }
                        }
                    }
                }

                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                    updatePlaybackState()
                }

                override fun onPlayerError(error: PlaybackException) {
                    updatePlaybackState()
                    when (type) {
                        STREAM -> {
                            val responseCode = (player?.playerError?.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
                            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                            val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                            val isNetworkAvailable = networkCapabilities != null
                                    && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                    && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                            if (isNetworkAvailable) {
                                when {
                                    responseCode == 404 -> {
                                        serviceListener?.toast(R.string.stream_ended, Toast.LENGTH_LONG)
                                    }
                                    useCustomProxy && responseCode >= 400 -> {
                                        useCustomProxy = false
                                        serviceListener?.toast(R.string.proxy_error, Toast.LENGTH_LONG)
                                        scheduleStreamRecovery()
                                    }
                                    else -> {
                                        serviceListener?.toast(R.string.player_error, Toast.LENGTH_SHORT)
                                        scheduleStreamRecovery()
                                    }
                                }
                            } else {
                                scheduleStreamRecovery()
                            }
                        }
                        VIDEO -> {
                            val responseCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
                            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                            val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                            val isNetworkAvailable = networkCapabilities != null
                                    && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                    && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                            if (isNetworkAvailable) {
                                when {
                                    !skipAccessToken && responseCode != 0 -> {
                                        skipAccessToken = true
                                        videoAnimatedPreviewURL?.let { preview ->
                                            val urls = TwitchApiHelper.getVideoUrlsFromPreview(preview, videoType, backupQualities)
                                            val list = urls.map {
                                                VideoQuality(it.key, url = it.value)
                                            }
                                            qualities = list
                                                .sortedByDescending {
                                                    it.bitrate
                                                }
                                                .sortedByDescending {
                                                    it.name?.substringAfter("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                                                }
                                                .sortedByDescending {
                                                    it.name?.substringBefore("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                                                }
                                                .toMutableList().apply {
                                                    find { it.name.equals("source", true) }?.let { source ->
                                                        remove(source)
                                                        add(0, VideoQuality(SOURCE_QUALITY, source.codecs, source.bitrate, source.url))
                                                    }
                                                    val audio = find { it.name?.startsWith("audio", true) == true }
                                                    audio?.let { remove(it) }
                                                    add(VideoQuality(AUDIO_ONLY_QUALITY, audio?.codecs, audio?.bitrate, audio?.url))
                                                }
                                            quality = qualities?.firstOrNull()
                                            serviceListener?.changePlayerMode()
                                            val url = quality?.url
                                            if (url != null) {
                                                player?.let { player ->
                                                    val playbackPosition = player.currentPosition
                                                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                                        setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                                    }.build()
                                                    val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                                                    val dataSourceFactory = DefaultDataSource.Factory(
                                                        this@ExoPlayerService,
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
                                                    hlsClipDataSourceFactory = dataSourceFactory
                                                    player.setMediaSource(
                                                        HlsMediaSource.Factory(dataSourceFactory).apply {
                                                            setPlaylistParserFactory(CustomHlsPlaylistParserFactory())
                                                        }.createMediaSource(
                                                            MediaItem.fromUri(url)
                                                        )
                                                    )
                                                    player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                                    player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                                    player.prepare()
                                                    player.playWhenReady = true
                                                    player.seekTo(playbackPosition)
                                                }
                                            }
                                        }
                                    }
                                    responseCode == 403 -> {
                                        serviceListener?.toast(R.string.video_subscribers_only, Toast.LENGTH_LONG)
                                    }
                                    else -> {
                                        serviceListener?.toast(R.string.player_error, Toast.LENGTH_SHORT)
                                        lifecycleScope.launch {
                                            delay(1500.milliseconds)
                                            player?.prepare()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    updatePlaybackState()
                    updateNotification()
                }

                override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                    updatePlaybackState()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        streamRecoveryJob?.cancel()
                        streamRecoveryJob = null
                        streamRecoveryAttempt = 0
                    }
                    updatePlaybackState()
                    updateNotification()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlaybackState()
                    if (isPlaying) {
                        if (savePositionTimer == null && type != STREAM) {
                            savePositionTimer = Timer().apply {
                                scheduleAtFixedRate(30000, 30000) {
                                    Handler(Looper.getMainLooper()).post {
                                        updateSavedPosition()
                                    }
                                }
                            }
                        }
                        stopServiceTimer?.cancel()
                        stopServiceTimer = null
                    } else {
                        savePositionTimer?.cancel()
                        savePositionTimer = null
                        updateSavedPosition()
                        if (stopServiceTimer == null && serviceListener == null) {
                            stopServiceTimer = Timer().apply {
                                schedule(600000) {
                                    Handler(Looper.getMainLooper()).post {
                                        runAfterPlaybackPersistence {
                                            stopSelf()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    updatePlaybackState()
                }

                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    updatePlaybackState()
                }
            }
            val sessionCallback = object : MediaSession.Callback() {
                override fun onPrepare() {
                    player?.prepare()
                }

                override fun onPlay() {
                    Util.handlePlayPauseButtonAction(player)
                }

                override fun onPause() {
                    player?.pause()
                }

                override fun onSkipToNext() {
                    player?.seekForward()
                }

                override fun onSkipToPrevious() {
                    player?.seekBack()
                }

                override fun onFastForward() {
                    player?.seekForward()
                }

                override fun onRewind() {
                    player?.seekBack()
                }

                override fun onStop() {
                    player?.stop()
                }

                override fun onSeekTo(pos: Long) {
                    player?.seekTo(pos)
                }

                override fun onSetPlaybackSpeed(speed: Float) {
                    player?.setPlaybackSpeed(speed)
                }

                override fun onCustomAction(action: String, extras: Bundle?) {
                    when (action) {
                        INTENT_REWIND -> player?.seekBack()
                        INTENT_FAST_FORWARD -> player?.seekForward()
                    }
                }

                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val eventHandled = super.onMediaButtonEvent(mediaButtonIntent)
                    return if (eventHandled) {
                        true
                    } else {
                        if (mediaButtonIntent.action == Intent.ACTION_MEDIA_BUTTON) {
                            val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                            }
                            if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                                when (keyEvent.keyCode) {
                                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                        player?.seekBack()
                                        true
                                    }
                                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                        player?.seekForward()
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        } else false
                    }
                }
            }
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
            this.player = player
            player.addListener(playerListener)
            val session = MediaSession(this, "ExoPlayerService")
            this.session = session
            session.setCallback(sessionCallback)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    session.setMediaButtonBroadcastReceiver(ComponentName(this, MediaButtonReceiver::class.java))
                } catch (e: IllegalArgumentException) {
                    // https://github.com/androidx/media/issues/1730
                }
            } else {
                @Suppress("DEPRECATION")
                session.setMediaButtonReceiver(
                    PendingIntent.getBroadcast(this, 0, Intent(Intent.ACTION_MEDIA_BUTTON).setClass(this, MediaButtonReceiver::class.java), PendingIntent.FLAG_MUTABLE)
                )
            }
            session.isActive = true
            notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channelId = getString(R.string.notification_playback_channel_id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager?.getNotificationChannel(channelId) == null) {
                notificationManager?.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        ContextCompat.getString(this, R.string.notification_playback_channel_title),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                            setShowBadge(false)
                        }
                    }
                )
            }
            start(restorePauseState)
        }
    }

    private fun start(restorePauseState: Boolean) {
        if (type != STREAM) {
            clearLiveClipState()
        }
        lifecycleScope.launch {
            try {
                restorePlaybackState()
            } finally {
                initialRestore.complete(Unit)
            }
            when (type) {
                STREAM -> {
                    clearLiveRewindState()
                    started = true
                    serviceListener?.started()
                    if (qualities.isNullOrEmpty()) {
                        useCustomProxy = prefs().getBoolean(C.PLAYER_STREAM_PROXY, false)
                    }
                    loadStream(restorePauseState)
                }
                VIDEO -> {
                    started = true
                    serviceListener?.started()
                    if (videoId != null) {
                        loadVideo(restorePauseState)
                        if (title == null) {
                            updateVideoInfo()
                        }
                    } else {
                        videoUrl?.let { videoUrl ->
                            val template = videoUrl.removeSuffix("/chunked/index-dvr.m3u8")
                            val list = TwitchApiHelper.defaultQualityList.map { quality ->
                                val name = if (quality == "chunked") {
                                    "source"
                                } else {
                                    quality
                                }
                                val url = "${template}/${quality}/index-dvr.m3u8"
                                VideoQuality(name, url = url)
                            }
                            qualities = list
                                .sortedByDescending {
                                    it.bitrate
                                }
                                .sortedByDescending {
                                    it.name?.substringAfter("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                                }
                                .sortedByDescending {
                                    it.name?.substringBefore("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                                }
                                .toMutableList().apply {
                                    find { it.name.equals("source", true) }?.let { source ->
                                        remove(source)
                                        add(0, VideoQuality(SOURCE_QUALITY, source.codecs, source.bitrate, source.url))
                                    }
                                    val audio = find { it.name?.startsWith("audio", true) == true }
                                    audio?.let { remove(it) }
                                    add(VideoQuality(AUDIO_ONLY_QUALITY, audio?.codecs, audio?.bitrate, audio?.url))
                                }
                            quality = qualities?.firstOrNull()
                            serviceListener?.changePlayerMode()
                            val url = quality?.url
                            if (url != null) {
                                player?.let { player ->
                                    val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                                    val dataSourceFactory = DefaultDataSource.Factory(
                                        this@ExoPlayerService,
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
                                    hlsClipDataSourceFactory = dataSourceFactory
                                    player.setMediaSource(
                                        HlsMediaSource.Factory(dataSourceFactory).apply {
                                            setPlaylistParserFactory(CustomHlsPlaylistParserFactory())
                                        }.createMediaSource(
                                            MediaItem.fromUri(url)
                                        )
                                    )
                                    player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                    player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                    player.prepare()
                                    player.playWhenReady = !restorePauseState || !paused
                                    player.seekTo(savedPosition ?: 0)
                                }
                            }
                        }
                    }
                }
                CLIP -> {
                    started = true
                    serviceListener?.started()
                    loadClip(restorePauseState)
                }
                OFFLINE_VIDEO -> {
                    offlineVideoId?.let { id ->
                        val video = xtraModule.offlineVideosRepository.getById(id)
                        if (video != null) {
                            val playbackPosition = if (prefs().getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
                                video.lastWatchPosition
                            } else {
                                null
                            } ?: savedPosition ?: 0
                            chatUrl = video.chatUrl
                            started = true
                            serviceListener?.started()
                            if (qualities.isNullOrEmpty()) {
                                qualities = listOf(
                                    VideoQuality(SOURCE_QUALITY, url = video.url),
                                    VideoQuality(AUDIO_ONLY_QUALITY),
                                )
                                setDefaultQuality()
                            }
                            serviceListener?.changePlayerMode()
                            val url = quality?.url ?: qualities?.firstOrNull()?.url
                            if (url != null) {
                                player?.let { player ->
                                    if (quality?.name == AUDIO_ONLY_QUALITY) {
                                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                            setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                                        }.build()
                                    }
                                    player.setMediaItem(MediaItem.fromUri(url))
                                    player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                    player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                    player.prepare()
                                    player.playWhenReady = true
                                    player.seekTo(playbackPosition)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun suppressAdPlayback() {
        if (!hidden) {
            hidden = true
            logAd("suppress playback channel=${channelLogin ?: "null"}")
            player?.let { player ->
                if (quality?.name != AUDIO_ONLY_QUALITY) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                        setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                    }.build()
                }
                player.volume = 0f
            }
            serviceListener?.toast(R.string.waiting_ads, Toast.LENGTH_LONG)
        }
    }

    private fun restoreAdPlayback() {
        if (hidden) {
            hidden = false
            logAd("restore playback channel=${channelLogin ?: "null"}")
            player?.let { player ->
                if (quality?.name != AUDIO_ONLY_QUALITY) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                        setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                    }.build()
                }
                player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
            }
        }
    }

    private fun fallbackFromAd(useProxy: Boolean, suppressAds: Boolean) {
        logAd("fallback channel=${channelLogin ?: "null"} usingProxy=$proxyMediaPlaylist useProxy=$useProxy suppress=$suppressAds")
        if (proxyMediaPlaylist) {
            if (!stopProxy) {
                setProxyMediaPlaylist(false)
                stopProxy = true
            }
            return
        }
        val playlist = quality?.url
        if (!stopProxy && !playlist.isNullOrBlank() && useProxy) {
            setProxyMediaPlaylist(true)
            lifecycleScope.launch {
                for (i in 0 until 10) {
                    delay(10.seconds)
                    if (!checkPlaylist(prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP), playlist)) {
                        break
                    }
                }
                setProxyMediaPlaylist(false)
            }
        } else if (suppressAds) {
            suppressAdPlayback()
        }
    }

    private fun tryAlternateStream(playerTypes: List<String>, useProxy: Boolean) {
        val channelLogin = channelLogin ?: run {
            fallbackFromAd(useProxy, suppressAds = true)
            return
        }
        logAd("alternate probe started channel=$channelLogin candidates=${playerTypes.joinToString()}")
        adAvoidanceJob = lifecycleScope.launch {
            val candidate = try {
                xtraModule.playerRepository.loadCleanStreamPlaylistUrl(
                    context = this@ExoPlayerService,
                    networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(this@ExoPlayerService, prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true)),
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
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logAd("alternate probe exception channel=$channelLogin error=${e.javaClass.simpleName}")
                null
            }
            logAd("alternate probe result channel=$channelLogin candidate=${candidate?.playerType ?: "none"}")
            if (candidate != null && type == STREAM) {
                try {
                    primaryStreamRestoreJob?.cancel()
                    primaryStreamRestoreJob = null
                    usingAlternateStream = true
                    serviceListener?.updateQualityStatus()
                    loadStream(restorePauseState = true, playlistUrlOverride = candidate.url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logAd("alternate stream load failed channel=$channelLogin playerType=${candidate.playerType} error=${e.javaClass.simpleName}")
                    usingAlternateStream = false
                    serviceListener?.updateQualityStatus()
                    fallbackFromAd(useProxy, suppressAds = true)
                }
            } else {
                fallbackFromAd(useProxy, suppressAds = true)
            }
        }
    }

    private fun schedulePrimaryStreamRestore() {
        if (!usingAlternateStream || primaryStreamRestoreJob?.isActive == true || type != STREAM) {
            return
        }
        val channelLogin = channelLogin ?: return
        val primaryPlayerType = prefs().getString(C.TOKEN_PLAYER_TYPE, "site") ?: "site"
        logAd("primary restore probe started channel=$channelLogin playerType=$primaryPlayerType")
        primaryStreamRestoreJob = lifecycleScope.launch {
            while (usingAlternateStream && type == STREAM) {
                val candidate = try {
                    xtraModule.playerRepository.loadCleanStreamPlaylistUrl(
                        context = this@ExoPlayerService,
                        networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(this@ExoPlayerService, prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true)),
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
                        requireVerifiedClean = true,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logAd("primary restore probe failed channel=$channelLogin error=${e.javaClass.simpleName}")
                    null
                }
                if (candidate?.verifiedClean == true && type == STREAM) {
                    try {
                        loadStream(restorePauseState = true, playlistUrlOverride = candidate.url)
                        usingAlternateStream = false
                        serviceListener?.updateQualityStatus()
                        adController.reset()
                        restoreAdPlayback()
                        logAd("primary stream restored channel=$channelLogin playerType=${candidate.playerType}")
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logAd("primary stream restore failed channel=$channelLogin error=${e.javaClass.simpleName}")
                    }
                }
                delay(10.seconds)
            }
            logAd("primary restore probe stopped channel=$channelLogin")
        }
    }

    private suspend fun loadStream(
        restorePauseState: Boolean = false,
        restart: Boolean = false,
        playlistUrlOverride: String? = null,
    ) {
        channelLogin?.let { channelLogin ->
            logAd("load stream channel=$channelLogin override=${!playlistUrlOverride.isNullOrBlank()} restart=$restart")
            if (!playlistUrlOverride.isNullOrBlank()) {
                playlistUrl = playlistUrlOverride
                qualities = null
                updateQualities = true
            } else if (restart || qualities.isNullOrEmpty()) {
                adAvoidanceJob?.cancel()
                primaryStreamRestoreJob?.cancel()
                primaryStreamRestoreJob = null
                usingAlternateStream = false
                serviceListener?.updateQualityStatus()
                adController.reset()
                stopProxy = false
                val proxyUrl = prefs().getString(C.PLAYER_PROXY_URL, "")
                if (useCustomProxy && !proxyUrl.isNullOrBlank()) {
                    playlistUrl = proxyUrl.replace("\$channel", channelLogin)
                } else {
                    useCustomProxy = false
                    val url = xtraModule.streamPreloadCoordinator.resolveForPlayback(channelLogin) ?: try {
                        xtraModule.playerRepository.loadStreamPlaylistUrl(
                            context = this,
                            networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                            gqlHeaders = TwitchApiHelper.getGQLHeaders(this@ExoPlayerService, prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true)),
                            channelLogin = channelLogin,
                            randomDeviceId = prefs().getBoolean(C.TOKEN_RANDOM_DEVICE_ID, true),
                            xDeviceId = prefs().getString(C.TOKEN_X_DEVICE_ID, "twitch-web-wall-mason"),
                            playerType = prefs().getString(C.TOKEN_PLAYER_TYPE, "site"),
                            supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                            proxyPlaybackAccessToken = prefs().getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false),
                            proxyHost = prefs().httpProxyHost(),
                            proxyPort = prefs().httpProxyPort(),
                            proxyUser = prefs().getString(C.PROXY_USER, null),
                            proxyPassword = prefs().getString(C.PROXY_PASSWORD, null),
                        )
                    } catch (e: Exception) {
                        null
                    }
                    playlistUrl = url
                }
            }
            val url = playlistUrl
            if (url != null) {
                player?.let { player ->
                    advanceLiveClipGeneration(clearDataSourceFactory = true, proxyMediaPlaylist = false)
                    val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                    val proxyHost = prefs().httpProxyHost()
                    val proxyPort = prefs().httpProxyPort()
                    val proxyUser = prefs().getString(C.PROXY_USER, null)
                    val proxyPassword = prefs().getString(C.PROXY_PASSWORD, null)
                    val dataSourceFactory = DefaultDataSource.Factory(
                                this@ExoPlayerService,
                                when {
                                    networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                                        val proxyMultivariantPlaylist = prefs().getBoolean(C.PROXY_MULTIVARIANT_PLAYLIST, false) && !proxyHost.isNullOrBlank() && proxyPort != null
                                        val proxyMediaPlaylist = !proxyHost.isNullOrBlank() && proxyPort != null
                                        val proxyClient = if (proxyMultivariantPlaylist || proxyMediaPlaylist) {
                                            val proxyHeaders = if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                                listOf(android.util.Pair("Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)))
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
                                                                NetworkUtils.proxyCandidates(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)), prefs().getBoolean(C.PROXY_ALLOW_DIRECT_FALLBACK, true))
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
                                                                NetworkUtils.proxyCandidates(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)), prefs().getBoolean(C.PROXY_ALLOW_DIRECT_FALLBACK, true))
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
                                                mapOf("Proxy-Authorization" to Credentials.basic(proxyUser, proxyPassword)).entries.toList()
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
                                                                override fun onBeforeTunnelRequest(request: org.chromium.net.Proxy.Callback.Request) {
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
                                                            return if (Regex(MULTIVARIANT_PLAYLIST_REGEX).matches(u.host)) {
                                                                NetworkUtils.proxyCandidates(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)), prefs().getBoolean(C.PROXY_ALLOW_DIRECT_FALLBACK, true))
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
                                                                NetworkUtils.proxyCandidates(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)), prefs().getBoolean(C.PROXY_ALLOW_DIRECT_FALLBACK, true))
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
                                                            return if (Regex(MULTIVARIANT_PLAYLIST_REGEX).matches(u.host)) {
                                                                NetworkUtils.proxyCandidates(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)), prefs().getBoolean(C.PROXY_ALLOW_DIRECT_FALLBACK, true))
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
                                                            return if (Regex(MEDIA_PLAYLIST_REGEX).matches(u.host)) {
                                                                NetworkUtils.proxyCandidates(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)), prefs().getBoolean(C.PROXY_ALLOW_DIRECT_FALLBACK, true))
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
                                        val headers = try {
                                            val json = JSONObject(it)
                                            hashMapOf<String, String>().apply {
                                                json.keys().forEach { key ->
                                                    put(key, json.optString(key))
                                                }
                                            }
                                        } catch (e: Exception) {
                                            null
                                        }
                                        if (headers != null) {
                                            setDefaultRequestProperties(headers)
                                        }
                                    }
                                }
                            )
                    hlsClipDataSourceFactory = dataSourceFactory
                    player.setMediaSource(
                        HlsMediaSource.Factory(dataSourceFactory).apply {
                            setPlaylistParserFactory(CustomHlsPlaylistParserFactory())
                            setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
                        }.createMediaSource(
                            MediaItem.Builder().apply {
                                setUri(url.toUri())
                                setMimeType(MimeTypes.APPLICATION_M3U8)
                                setLiveConfiguration(MediaItem.LiveConfiguration.Builder().apply {
                                    setTargetOffsetMs(
                                        if (prefs().getBoolean(C.PLAYER_LOW_LATENCY, C.DEFAULT_PLAYER_LOW_LATENCY)) {
                                            C.LOW_LATENCY_TARGET_OFFSET_MS
                                        } else {
                                            C.NORMAL_LATENCY_TARGET_OFFSET_MS
                                        }
                                    )
                                }.build())
                            }.build()
                        )
                    )
                    player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                    player.setPlaybackSpeed(1f)
                    player.prepare()
                    player.playWhenReady = !restorePauseState || !paused
                }
            } else {
                clearLiveClipState()
            }
        }
    }

    suspend fun startLiveRewind(vodId: String, positionMs: Long): Boolean = liveRewindTransitionMutex.withLock {
        val player = player ?: return@withLock false
        val oldVideoId = videoId
        val oldPlaylistUrl = playlistUrl
        val oldQualities = qualities
        val oldQuality = quality
        val oldBackupQualities = backupQualities
        val oldSavedPosition = savedPosition
        val oldLiveRewindPositionOverride = liveRewindPositionOverride
        val oldPaused = paused
        val wasPlaying = player.playWhenReady
        clearLiveRewindState()
        videoId = vodId
        playlistUrl = null
        qualities = null
        quality = null
        savedPosition = positionMs
        liveRewindPositionOverride = positionMs
        paused = !wasPlaying
        return try {
            loadVideo(restorePauseState = true)
            val loaded = !playlistUrl.isNullOrBlank()
            videoId = oldVideoId
            if (!loaded) {
                playlistUrl = oldPlaylistUrl
                qualities = oldQualities
                quality = oldQuality
                backupQualities = oldBackupQualities
            }
            savedPosition = oldSavedPosition
            liveRewindPositionOverride = oldLiveRewindPositionOverride
            paused = oldPaused
            if (loaded) {
                markLiveRewindActive(vodId)
            } else {
                clearLiveRewindState()
            }
            loaded
        } catch (_: Exception) {
            videoId = oldVideoId
            playlistUrl = oldPlaylistUrl
            qualities = oldQualities
            quality = oldQuality
            backupQualities = oldBackupQualities
            savedPosition = oldSavedPosition
            liveRewindPositionOverride = oldLiveRewindPositionOverride
            paused = oldPaused
            clearLiveRewindState()
            false
        }
    }

    suspend fun returnToLivePlayback(): Boolean = liveRewindTransitionMutex.withLock {
        val player = player ?: return@withLock false
        val wasPlaying = player.playWhenReady
        val rewindVodId = liveRewindVodId
        val oldPlaylistUrl = playlistUrl
        val oldQualities = qualities
        val oldQuality = quality
        val oldBackupQualities = backupQualities
        playlistUrl = null
        qualities = null
        quality = null
        backupQualities = null
        return try {
            loadStream(restorePauseState = false, restart = true).let {
                !playlistUrl.isNullOrBlank() && player.currentMediaItem != null
            }
        } catch (_: Exception) {
            false
        }.also {
            if (it) {
                clearLiveRewindState()
            } else {
                playlistUrl = oldPlaylistUrl
                qualities = oldQualities
                quality = oldQuality
                backupQualities = oldBackupQualities
                (rewindVodId ?: videoId)?.let(::markLiveRewindActive)
            }
            player.playWhenReady = wasPlaying
        }
    }

    private suspend fun loadVideo(restorePauseState: Boolean = false) {
        clearVodClipSource()
        videoId?.let { videoId ->
            val playbackPosition = liveRewindPositionOverride ?: if (prefs().getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
                videoId.toLongOrNull()?.let { xtraModule.playerRepository.getVideoPosition(it)?.position }
            } else {
                null
            } ?: savedPosition ?: 0
            if (qualities.isNullOrEmpty()) {
                val result = try {
                    xtraModule.playerRepository.loadVideoPlaylistUrl(
                        networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(this@ExoPlayerService, prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_VIDEO, true)),
                        videoId = videoId,
                        playerType = prefs().getString(C.TOKEN_PLAYER_TYPE_VIDEO, "channel_home_live"),
                        supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                    )
                } catch (e: Exception) {
                    null
                }
                if (result != null) {
                    playlistUrl = result.first
                    backupQualities = result.second
                }
            }
            val url = playlistUrl
            if (url != null) {
                player?.let { player ->
                    val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                    val dataSourceFactory = DefaultDataSource.Factory(
                        this@ExoPlayerService,
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
                    hlsClipDataSourceFactory = dataSourceFactory
                    player.setMediaSource(
                        HlsMediaSource.Factory(dataSourceFactory).apply {
                            setPlaylistParserFactory(CustomHlsPlaylistParserFactory())
                        }.createMediaSource(
                            MediaItem.fromUri(url)
                        )
                    )
                    player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                    player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                    player.prepare()
                    player.playWhenReady = !restorePauseState || !paused
                    player.seekTo(playbackPosition)
                }
            }
        }
    }

    private suspend fun updateVideoInfo() {
        val video = try {
            val response = xtraModule.graphQLRepository.loadQueryVideo(
                networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                headers = TwitchApiHelper.getGQLHeaders(this),
                id = videoId
            )
            response.data!!.let { item ->
                item.video?.let {
                    Video(
                        id = videoId,
                        channelId = it.owner?.id,
                        channelLogin = it.owner?.login,
                        channelName = it.owner?.displayName,
                        channelImageURL = it.owner?.profileImageURL,
                        gameId = it.game?.id,
                        gameSlug = it.game?.slug,
                        gameName = it.game?.displayName,
                        title = it.title,
                        thumbnailURL = it.previewThumbnailURL,
                        createdAt = it.createdAt?.toString(),
                        durationSeconds = it.lengthSeconds,
                        type = it.broadcastType?.toString(),
                        animatedPreviewURL = it.animatedPreviewURL,
                    )
                }
            }
        } catch (e: Exception) {
            val helixHeaders = TwitchApiHelper.getHelixHeaders(this)
            if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                try {
                    xtraModule.helixRepository.getVideos(
                        networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                        headers = helixHeaders,
                        ids = videoId?.let { listOf(it) }
                    ).data.firstOrNull()?.let {
                        Video(
                            id = it.id,
                            channelId = it.channelId,
                            channelLogin = it.channelLogin,
                            channelName = it.channelName,
                            title = it.title,
                            thumbnailURL = it.thumbnailURL,
                            createdAt = it.createdAt,
                            viewCount = it.viewCount,
                            durationSeconds = it.duration?.let { duration -> TwitchApiHelper.getDuration(duration) },
                        )
                    }
                } catch (e: Exception) {
                    null
                }
            } else null
        }
        if (video != null) {
            channelId = video.channelId
            channelLogin = video.channelLogin
            channelName = video.channelName
            channelImage = video.channelImage
            gameId = video.gameId
            gameSlug = video.gameSlug
            gameName = video.gameName
            title = video.title
            thumbnail = video.thumbnail
            createdAt = video.createdAt
            durationSeconds = video.durationSeconds
            videoType = video.type
            videoAnimatedPreviewURL = video.animatedPreviewURL
            updateMetadata()
            updateNotification()
            serviceListener?.updateVideoInfo()
        }
    }

    private suspend fun loadClip(restorePauseState: Boolean = false) {
        clipId?.let { clipId ->
            val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
            if (qualities.isNullOrEmpty()) {
                val list = try {
                    xtraModule.playerRepository.loadClipQualities(
                        networkLibrary = networkLibrary,
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(this@ExoPlayerService),
                        clipId = clipId,
                    )
                } catch (e: Exception) {
                    null
                }
                if (list != null) {
                    val supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264")?.split(',') ?: emptyList()
                    val filtered = list.filterNot {
                        it.codecs?.substringBefore('.').let { codec ->
                            (codec == "av01" && !supportedCodecs.contains("av1")) || ((codec == "hev1" || codec == "hvc1") && !supportedCodecs.contains("h265"))
                        }
                    }
                    qualities = filtered
                        .sortedByDescending {
                            it.bitrate
                        }
                        .sortedByDescending {
                            it.name?.substringAfter("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                        }
                        .sortedByDescending {
                            it.name?.substringBefore("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                        }
                        .toMutableList().apply {
                            add(VideoQuality(AUDIO_ONLY_QUALITY))
                        }
                    setDefaultQuality()
                }
            }
            serviceListener?.changePlayerMode()
            val url = quality?.url ?: qualities?.firstOrNull()?.url
            if (url != null) {
                player?.let { player ->
                    if (quality?.name == AUDIO_ONLY_QUALITY) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                            setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                        }.build()
                    }
                    player.setMediaSource(
                        ProgressiveMediaSource.Factory(
                            DefaultDataSource.Factory(
                                this@ExoPlayerService,
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
                            MediaItem.fromUri(url)
                        )
                    )
                    player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                    player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                    player.prepare()
                    player.playWhenReady = !restorePauseState || !paused
                    player.seekTo(savedPosition ?: 0)
                }
            }
        }
    }

    fun liveClipStatus(): LiveClipBufferManager.Status? = if (type == STREAM && hlsClipDataSourceFactory != null) {
        val maxDurationUs = configureLiveClipBuffer()
        liveClipBufferManager.status(maxDurationUs)
    } else {
        null
    }

    suspend fun awaitInitialRestore() {
        initialRestore.await()
    }

    fun prepareLiveClip(): Deferred<ClipPreparationRepository.PreparedLiveClip> {
        liveClipPreparation?.takeUnless { it.isCompleted }?.let { return it }
        val maxDurationUs = configureLiveClipBuffer()
        val snapshot = liveClipBufferManager.snapshot(maxDurationUs)
        val dataSourceFactory = hlsClipDataSourceFactory
        val preparation = lifecycleScope.async(Dispatchers.IO) {
            check(type == STREAM && dataSourceFactory != null && snapshot != null) {
                "There is not enough live video available for a clip"
            }
            check(snapshot.durationUs >= LiveClipBufferManager.MIN_CLIP_BUFFER_US) {
                "There is not enough live video available for a clip"
            }
            check(!snapshot.drmInitDataPresent) {
                "Clipping DRM-protected streams is not supported"
            }
            ClipPreparationRepository(
                dataSourceFactory = dataSourceFactory,
                rootDirectory = File(cacheDir, LIVE_CLIP_DIRECTORY),
            ).prepare(snapshot)
        }
        liveClipPreparation = preparation
        preparation.invokeOnCompletion {
            if (liveClipPreparation === preparation) {
                liveClipPreparation = null
            }
        }
        return preparation
    }

    data class VodClipDescriptor(
        val previewUri: String,
        val segmentDurationsUs: IntArray,
        val initialPositionUs: Long,
        val bitrateBitsPerSecond: Int?,
    )

    fun canCreateVodClip(): Boolean {
        if (type != VIDEO || hlsClipDataSourceFactory == null) return false
        val playlist = (player?.currentManifest as? HlsManifest)?.mediaPlaylist ?: return false
        if (playlist.protectionSchemes != null) return false
        if (playlist.segments.any { it.drmInitData != null }) return false
        if (playlist.segments.none { it.durationUs > 0L }) return false
        return player?.currentMediaItem?.localConfiguration?.uri != null || quality?.url != null
    }

    fun createVodClipDescriptor(): VodClipDescriptor? {
        if (type != VIDEO) return null
        val manifest = player?.currentManifest as? HlsManifest ?: return null
        val snapshot = HlsClipSnapshotMapper.fromManifest(manifest, generation = 0L)
        if (snapshot.segments.isEmpty() || snapshot.drmInitDataPresent) return null
        val previewUri = player?.currentMediaItem?.localConfiguration?.uri?.toString()
            ?: quality?.url
            ?: return null
        val segmentDurationsUs = IntArray(snapshot.segments.size) { index ->
            val durationUs = snapshot.segments[index].durationUs
            require(durationUs in 1L..Int.MAX_VALUE.toLong()) {
                "Unsupported HLS segment duration: $durationUs"
            }
            durationUs.toInt()
        }
        vodClipSnapshot = snapshot
        return VodClipDescriptor(
            previewUri = previewUri,
            segmentDurationsUs = segmentDurationsUs,
            initialPositionUs = (player?.currentPosition ?: 0L) * 1_000L,
            bitrateBitsPerSecond = quality?.bitrate,
        )
    }

    fun createVodClipPreviewMediaSource(uri: String): MediaSource {
        check(type == VIDEO)
        val factory = requireNotNull(hlsClipDataSourceFactory) {
            "VOD HLS data source is unavailable"
        }
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        return HlsMediaSource.Factory(factory)
            .apply {
                setPlaylistParserFactory(CustomHlsPlaylistParserFactory())
            }
            .createMediaSource(mediaItem)
    }

    fun estimateVodClipBytes(
        startIndex: Int,
        endIndexExclusive: Int,
        selectedDurationUs: Long,
    ): Long? {
        val snapshot = vodClipSnapshot ?: return null
        return ClipSizeEstimator.estimateBytes(
            selectedDurationUs = selectedDurationUs,
            segments = snapshot.segments,
            startIndex = startIndex,
            endIndexExclusive = endIndexExclusive,
            bitrateBitsPerSecond = quality?.bitrate,
        )
    }

    fun prepareVodClip(
        startIndex: Int,
        endIndexExclusive: Int,
    ): Deferred<ClipPreparationRepository.PreparedLiveClip> {
        vodClipPreparation?.takeUnless { it.isCompleted }?.let { return it }
        val source = requireNotNull(vodClipSnapshot) { "VOD clip source is no longer available" }
        val factory = requireNotNull(hlsClipDataSourceFactory) { "VOD HLS data source is unavailable" }
        require(startIndex in source.segments.indices)
        require(endIndexExclusive in (startIndex + 1)..source.segments.size)
        val selectedSegments = source.segments.subList(startIndex, endIndexExclusive)
        check(selectedSegments.none { it.hasGap }) {
            "The selected VOD range contains an unavailable HLS segment"
        }
        val selected = ClipSnapshot(source.generation, source.renditionId, selectedSegments.toList())
        check(!selected.drmInitDataPresent) { "DRM-protected VOD clipping is not supported" }
        val root = File(cacheDir, VOD_CLIP_DIRECTORY)
        val preparation = lifecycleScope.async(Dispatchers.IO) {
            val availableBytes = StatFs(cacheDir.absolutePath).availableBytes
            val safetyBytes = VOD_STORAGE_SAFETY_BYTES
            val estimatedBytes = ClipSizeEstimator.estimateBytes(
                selectedDurationUs = selected.segments.sumOf { it.durationUs },
                segments = selected.segments,
                startIndex = 0,
                endIndexExclusive = selected.segments.size,
                bitrateBitsPerSecond = quality?.bitrate,
            )
            val requiredBytes = estimatedBytes?.let { estimate ->
                estimate.coerceAtMost((Long.MAX_VALUE - safetyBytes) / 2L) * 2L + safetyBytes
            }
            check(requiredBytes == null || requiredBytes <= availableBytes) {
                "Not enough temporary storage to create this clip"
            }
            val maxPreparedBytes = ((availableBytes - safetyBytes).coerceAtLeast(0L) / 2L)
            check(maxPreparedBytes > 0L) { "Not enough temporary storage to create this clip" }
            ClipPreparationRepository(
                dataSourceFactory = factory,
                rootDirectory = root,
                maxBytes = maxPreparedBytes,
            ).prepare(selected)
        }
        vodClipPreparation = preparation
        preparation.invokeOnCompletion {
            if (vodClipPreparation === preparation) vodClipPreparation = null
        }
        return preparation
    }

    fun cancelVodClipPreparation() {
        vodClipPreparation?.cancel()
        vodClipPreparation = null
    }

    fun releaseVodClip(directoryPath: String?) {
        if (directoryPath.isNullOrBlank()) return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val root = File(cacheDir, VOD_CLIP_DIRECTORY).canonicalFile
                val target = File(directoryPath).canonicalFile
                if (target.parentFile == root) target.deleteRecursively()
            }
        }
    }

    fun clearVodClipSource() {
        cancelVodClipPreparation()
        vodClipSnapshot = null
    }

    private fun configureLiveClipBuffer(): Long {
        val maxDurationSeconds = prefs()
            .getString(
                C.CLIP_MAX_DURATION_SECONDS,
                LiveClipBufferManager.DEFAULT_CLIP_DURATION_SECONDS.toString(),
            )
            ?.toIntOrNull()
            ?.coerceIn(
                LiveClipBufferManager.MIN_CLIP_DURATION_SECONDS,
                LiveClipBufferManager.MAX_CLIP_DURATION_SECONDS,
            )
            ?: LiveClipBufferManager.DEFAULT_CLIP_DURATION_SECONDS
        val maxDurationUs = maxDurationSeconds * 1_000_000L
        liveClipBufferManager.setRetentionUs(maxDurationUs + LiveClipBufferManager.RETENTION_MARGIN_US)
        return maxDurationUs
    }

    fun cancelLiveClipPreparation() {
        liveClipPreparation?.cancel()
        liveClipPreparation = null
    }

    fun releaseLiveClip(directoryPath: String?) {
        if (directoryPath.isNullOrBlank()) return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val root = File(cacheDir, LIVE_CLIP_DIRECTORY).canonicalFile
                val target = File(directoryPath).canonicalFile
                if (target.parentFile == root) {
                    target.deleteRecursively()
                }
            }
        }
    }

    private fun advanceLiveClipGeneration(
        clearDataSourceFactory: Boolean = false,
        proxyMediaPlaylist: Boolean? = null,
    ) {
        cancelLiveClipPreparation()
        if (clearDataSourceFactory) {
            hlsClipDataSourceFactory = null
        }
        proxyMediaPlaylist?.let { this.proxyMediaPlaylist = it }
        liveClipBufferManager.startNewGeneration()
        if (type == STREAM) {
            serviceListener?.updateLiveClipStatus()
        }
    }

    private fun setProxyMediaPlaylist(value: Boolean) {
        if (proxyMediaPlaylist == value) return
        proxyMediaPlaylist = value
        advanceLiveClipGeneration()
    }

    private fun clearLiveClipState() {
        cancelLiveClipPreparation()
        clearVodClipSource()
        hlsClipDataSourceFactory = null
        liveClipBufferManager.reset()
    }

    fun retry(item: String) {
        when (item) {
            "refreshStream" -> {
                lifecycleScope.launch {
                    loadStream()
                }
            }
            "refreshVideo" -> {
                lifecycleScope.launch {
                    loadVideo()
                }
            }
            "refreshClip" -> {
                lifecycleScope.launch {
                    loadClip()
                }
            }
        }
    }

    private fun setQualityMediaItem(player: ExoPlayer, mediaItem: MediaItem, uri: String) {
        val updatedMediaItem = mediaItem.buildUpon().setUri(uri).build()
        val dataSourceFactory = hlsClipDataSourceFactory
        if ((type == STREAM || type == VIDEO) && dataSourceFactory != null) {
            player.setMediaSource(
                HlsMediaSource.Factory(dataSourceFactory).apply {
                    setPlaylistParserFactory(CustomHlsPlaylistParserFactory())
                    setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
                }.createMediaSource(updatedMediaItem)
            )
        } else {
            player.setMediaItem(updatedMediaItem)
        }
    }

    fun changeQuality(
        selectedQuality: VideoQuality?,
        resetLiveClipGeneration: Boolean = true,
        persistSavedQuality: Boolean = true,
    ) {
        val qualityChanged = quality?.name != selectedQuality?.name || quality?.url != selectedQuality?.url
        if (type == STREAM && qualityChanged && resetLiveClipGeneration) {
            advanceLiveClipGeneration()
        }
        if (type == VIDEO && qualityChanged) clearVodClipSource()
        previousQuality = quality
        quality = selectedQuality
        quality?.let { quality ->
            player?.let { player ->
                player.currentMediaItem?.let { mediaItem ->
                    when (quality.name) {
                        AUTO_QUALITY -> {
                            if (restorePlaylist) {
                                restorePlaylist = false
                                playlistUrl?.let { uri ->
                                    if (mediaItem.localConfiguration?.uri != uri.toUri()) {
                                        val position = player.currentPosition
                                        setQualityMediaItem(player, mediaItem, uri)
                                        player.prepare()
                                        player.seekTo(position)
                                    }
                                }
                            } else {
                                player.prepare()
                            }
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                            }.build()
                        }
                        AUDIO_ONLY_QUALITY -> {
                            setProxyMediaPlaylist(false)
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                            }.build()
                            quality.url?.let {
                                val position = player.currentPosition
                                if (qualities?.find { it.name == AUTO_QUALITY } != null) {
                                    restorePlaylist = true
                                }
                                setQualityMediaItem(player, mediaItem, it)
                                player.prepare()
                                player.seekTo(position)
                            }
                        }
                        CHAT_ONLY_QUALITY -> {
                            setProxyMediaPlaylist(false)
                            player.stop()
                        }
                        else -> {
                            if (qualities?.find { it.name == AUTO_QUALITY } != null) {
                                if (restorePlaylist) {
                                    restorePlaylist = false
                                    playlistUrl?.let { uri ->
                                        val position = player.currentPosition
                                        setQualityMediaItem(player, mediaItem, uri)
                                        player.prepare()
                                        player.seekTo(position)
                                    }
                                } else {
                                    player.prepare()
                                }
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                    if (!player.currentTracks.isEmpty) {
                                        player.currentTracks.groups.find { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }?.let { trackGroup ->
                                            val selectedQuality = quality.name?.split("p")
                                            val targetResolution = selectedQuality?.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull()
                                            val targetFps = selectedQuality?.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 30
                                            val targetBitrate = quality.bitrate
                                            if (trackGroup.mediaTrackGroup.length > 0) {
                                                if (targetResolution != null) {
                                                    val formats = mutableListOf<Pair<Int, Format>>()
                                                    for (i in 0 until trackGroup.mediaTrackGroup.length) {
                                                        formats.add(i to trackGroup.mediaTrackGroup.getFormat(i))
                                                    }
                                                    val list = formats
                                                        .sortedByDescending { it.second.bitrate }
                                                        .sortedByDescending { it.second.frameRate }
                                                        .sortedByDescending { it.second.height }
                                                    list.find {
                                                        (targetResolution == it.second.height
                                                                && targetFps >= floor(it.second.frameRate)
                                                                && (targetBitrate == null || targetBitrate >= it.second.bitrate))
                                                                || targetResolution > it.second.height
                                                                || it == list.last()
                                                    }?.first?.let { index ->
                                                        setOverrideForType(TrackSelectionOverride(trackGroup.mediaTrackGroup, index))
                                                    }
                                                } else {
                                                    setOverrideForType(TrackSelectionOverride(trackGroup.mediaTrackGroup, 0))
                                                }
                                            }
                                        }
                                    }
                                }.build()
                            } else {
                                player.currentMediaItem?.let { mediaItem ->
                                    quality.url?.let { qualityUri ->
                                        if (mediaItem.localConfiguration?.uri?.toString() != qualityUri) {
                                            val position = player.currentPosition
                                            setQualityMediaItem(player, mediaItem, qualityUri)
                                            player.prepare()
                                            player.seekTo(position)
                                        }
                                    }
                                }
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                }.build()
                            }
                        }
                    }
                    if (persistSavedQuality) {
                        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                        val cellular = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                        if ((!cellular && prefs().getString(C.PLAYER_DEFAULT_QUALITY, "saved") == "saved") || (cellular && prefs().getString(C.PLAYER_DEFAULT_CELLULAR_QUALITY, "saved") == "saved")) {
                            prefs().edit { putString(C.PLAYER_QUALITY, quality.name) }
                        }
                    }
                }
            }
        }
    }

    fun toggleSubtitles(enabled: Boolean) {
        player?.let { player ->
            if (enabled) {
                player.currentTracks.groups.find { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }?.let {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0))
                        .build()
                }
            } else {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
                    .build()
            }
        }
    }

    suspend fun checkPlaylist(networkLibrary: String?, url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val playlist = when {
                networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                    val response = suspendCancellableCoroutine { continuation ->
                        val timeout = NetworkUtils.HttpEngineTimeout()
                        val request = xtraModule.httpEngine.value!!.newUrlRequestBuilder(
                            url,
                            xtraModule.cronetExecutor.value,
                            NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                        ).build()
                        timeout.start(request, continuation)
                        request.start()
                        continuation.invokeOnCancellation {
                            request.cancel()
                            timeout.stop()
                        }
                    }
                    response.body.inputStream().use {
                        PlaylistUtils.parseMediaPlaylist(it)
                    }
                }
                networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                    val response = suspendCancellableCoroutine { continuation ->
                        val timeout = NetworkUtils.CronetTimeout()
                        val request = xtraModule.cronetEngine.value!!.newUrlRequestBuilder(
                            url,
                            NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                            xtraModule.cronetExecutor.value
                        ).build()
                        timeout.start(request, continuation)
                        request.start()
                        continuation.invokeOnCancellation {
                            request.cancel()
                            timeout.stop()
                        }
                    }
                    response.body.inputStream().use {
                        PlaylistUtils.parseMediaPlaylist(it)
                    }
                }
                else -> {
                    xtraModule.okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                        response.body.byteStream().use {
                            PlaylistUtils.parseMediaPlaylist(it)
                        }
                    }
                }
            }
            TwitchAdDetector.isAd(playlist)
        } catch (e: Exception) {
            false
        }
    }

    fun restartPlayer() {
        if (type == STREAM && liveRewindActive) return
        if (quality?.name != CHAT_ONLY_QUALITY) {
            lifecycleScope.launch {
                loadStream(restart = true)
            }
        }
    }

    private fun scheduleStreamRecovery() {
        if (!prefs().getBoolean(C.PLAYER_AUTO_RECOVER_STREAMS, true)
            || type != STREAM
            || liveRewindActive
            || player?.playWhenReady != true
        ) {
            return
        }
        streamRecoveryJob?.cancel()
        val delayMs = (1500L shl streamRecoveryAttempt.coerceAtMost(3)).coerceAtMost(12000L)
        streamRecoveryAttempt = (streamRecoveryAttempt + 1).coerceAtMost(3)
        streamRecoveryJob = lifecycleScope.launch {
            delay(delayMs)
            if (prefs().getBoolean(C.PLAYER_AUTO_RECOVER_STREAMS, true)
                && player?.playWhenReady == true
                && type == STREAM
                && !liveRewindActive
            ) {
                loadStream(restart = true)
            }
        }
    }

    fun startAudioOnly() {
        player?.let { player ->
            setProxyMediaPlaylist(false)
            if (quality?.name != AUDIO_ONLY_QUALITY) {
                if (type == STREAM) {
                    advanceLiveClipGeneration()
                }
                restoreQuality = true
                previousQuality = quality
                quality = qualities?.find { it.name == AUDIO_ONLY_QUALITY }
                quality?.let { quality ->
                    if (player.currentMediaItem != null) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                            setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                        }.build()
                    }
                }
            }
        }
    }

    fun stop(isInPIPMode: Boolean): Boolean {
        val player = player ?: return false
        setProxyMediaPlaylist(false)
        resumeWhenForeground = false
        if (isInPIPMode) {
            return false
        }
        if (prefs().getBoolean(C.SETTINGS_BACKGROUND_PLAYBACK, true)) {
            if (player.playWhenReady && quality?.name != AUDIO_ONLY_QUALITY && player.currentMediaItem != null) {
                videoOutputState.markDetachedForBackground()
                return true
            }
        } else {
            resumeWhenForeground = player.playWhenReady && player.playbackState != Player.STATE_ENDED
            player.pause()
        }
        return false
    }

    fun restoreVideoOutputIfNeeded(restore: () -> Boolean): Boolean {
        return videoOutputState.restoreIfNeeded(restore)
    }

    fun resumePlaybackIfNeeded() {
        if (!resumeWhenForeground) {
            return
        }
        resumeWhenForeground = false
        player?.let { player ->
            if (player.currentMediaItem != null) {
                if (player.playbackState == Player.STATE_IDLE) {
                    player.prepare()
                }
                player.playWhenReady = true
            }
        }
    }

    private fun updatePlaybackState() {
        player?.let { player ->
            updateViewingStats(
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
            )
            session?.setPlaybackState(
                PlaybackState.Builder().apply {
                    setState(
                        when (player.playbackState) {
                            Player.STATE_IDLE -> PlaybackState.STATE_NONE
                            Player.STATE_BUFFERING -> {
                                if (Util.shouldShowPlayButton(player)) {
                                    PlaybackState.STATE_PAUSED
                                } else {
                                    PlaybackState.STATE_BUFFERING
                                }
                            }
                            Player.STATE_READY -> {
                                if (Util.shouldShowPlayButton(player)) {
                                    PlaybackState.STATE_PAUSED
                                } else {
                                    PlaybackState.STATE_PLAYING
                                }
                            }
                            Player.STATE_ENDED -> PlaybackState.STATE_STOPPED
                            else -> PlaybackState.STATE_NONE
                        },
                        player.currentPosition,
                        if (player.isPlaying) {
                            player.playbackParameters.speed
                        } else {
                            0f
                        }
                    )
                    setBufferedPosition(player.bufferedPosition)
                    setActions(
                        (PlaybackState.ACTION_STOP
                                or PlaybackState.ACTION_PAUSE
                                or PlaybackState.ACTION_PLAY
                                or PlaybackState.ACTION_REWIND
                                or PlaybackState.ACTION_FAST_FORWARD
                                or PlaybackState.ACTION_SET_RATING
                                or PlaybackState.ACTION_PLAY_PAUSE
                                or PlaybackState.ACTION_SEEK_TO).let {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                (it or PlaybackState.ACTION_PREPARE).let {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        it or PlaybackState.ACTION_SET_PLAYBACK_SPEED
                                    } else {
                                        it
                                    }
                                }
                            } else {
                                it
                            }
                        }
                    )
                    addCustomAction(INTENT_REWIND, ContextCompat.getString(this@ExoPlayerService, R.string.rewind), androidx.media3.session.R.drawable.media3_icon_rewind)
                    addCustomAction(INTENT_FAST_FORWARD, ContextCompat.getString(this@ExoPlayerService, R.string.forward), androidx.media3.session.R.drawable.media3_icon_fast_forward)
                }.build()
            )
        }
    }

    private fun updateMetadata() {
        val url = channelImage
        val bitmap = if (!url.isNullOrBlank()) {
            if (url == artworkUri && cachedBitmap != null) {
                cachedBitmap
            } else {
                val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                artworkUri = url
                bitmapLoadJob?.cancel()
                bitmapLoadJob = lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val scheme = url.toUri().scheme
                        val response = if (scheme == "https" || scheme == "http") {
                            when {
                                networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        val timeout = NetworkUtils.HttpEngineTimeout()
                                        val request = xtraModule.httpEngine.value!!.newUrlRequestBuilder(
                                            url,
                                            xtraModule.cronetExecutor.value,
                                            NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                        ).build()
                                        timeout.start(request, continuation)
                                        request.start()
                                        continuation.invokeOnCancellation {
                                            request.cancel()
                                            timeout.stop()
                                        }
                                    }
                                    if (response.info.httpStatusCode in 200..299) {
                                        response.body
                                    } else null
                                }
                                networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        val timeout = NetworkUtils.CronetTimeout()
                                        val request = xtraModule.cronetEngine.value!!.newUrlRequestBuilder(
                                            url,
                                            NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                            xtraModule.cronetExecutor.value
                                        ).build()
                                        timeout.start(request, continuation)
                                        request.start()
                                        continuation.invokeOnCancellation {
                                            request.cancel()
                                            timeout.stop()
                                        }
                                    }
                                    if (response.info.httpStatusCode in 200..299) {
                                        response.body
                                    } else null
                                }
                                else -> {
                                    xtraModule.okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                        if (response.isSuccessful) {
                                            response.body.readBytesLimited()
                                        } else null
                                    }
                                }
                            }
                        } else {
                            FileInputStream(url).use {
                                it.readBytes()
                            }
                        }
                        if (response != null) {
                            val bitmap = BitmapFactory.decodeByteArray(response, 0, response.size)
                            if (bitmap != null) {
                                cachedBitmap = bitmap
                                withContext(Dispatchers.Main) {
                                    setMetadata(bitmap)
                                }
                            }
                        }
                    } catch (e: Exception) {

                    }
                }
                null
            }
        } else null
        setMetadata(bitmap)
    }

    private fun setMetadata(bitmap: Bitmap?) {
        player?.let { player ->
            session?.setMetadata(
                MediaMetadata.Builder().apply {
                    putText(MediaMetadata.METADATA_KEY_TITLE, title)
                    putText(MediaMetadata.METADATA_KEY_ARTIST, channelName)
                    if (bitmap != null) {
                        putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, bitmap)
                        putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
                    }
                    putLong(MediaMetadata.METADATA_KEY_DURATION, player.duration)
                }.build()
            )
        }
    }

    private fun updateNotification() {
        val url = channelImage
        val bitmap = if (!url.isNullOrBlank()) {
            if (url == artworkUri && cachedBitmap != null) {
                cachedBitmap
            } else {
                val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                artworkUri = url
                bitmapLoadJob?.cancel()
                bitmapLoadJob = lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val scheme = url.toUri().scheme
                        val response = if (scheme == "https" || scheme == "http") {
                            when {
                                networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        val timeout = NetworkUtils.HttpEngineTimeout()
                                        val request = xtraModule.httpEngine.value!!.newUrlRequestBuilder(
                                            url,
                                            xtraModule.cronetExecutor.value,
                                            NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                        ).build()
                                        timeout.start(request, continuation)
                                        request.start()
                                        continuation.invokeOnCancellation {
                                            request.cancel()
                                            timeout.stop()
                                        }
                                    }
                                    if (response.info.httpStatusCode in 200..299) {
                                        response.body
                                    } else null
                                }
                                networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        val timeout = NetworkUtils.CronetTimeout()
                                        val request = xtraModule.cronetEngine.value!!.newUrlRequestBuilder(
                                            url,
                                            NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                            xtraModule.cronetExecutor.value
                                        ).build()
                                        timeout.start(request, continuation)
                                        request.start()
                                        continuation.invokeOnCancellation {
                                            request.cancel()
                                            timeout.stop()
                                        }
                                    }
                                    if (response.info.httpStatusCode in 200..299) {
                                        response.body
                                    } else null
                                }
                                else -> {
                                    xtraModule.okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                        if (response.isSuccessful) {
                                            response.body.readBytesLimited()
                                        } else null
                                    }
                                }
                            }
                        } else {
                            FileInputStream(url).use {
                                it.readBytes()
                            }
                        }
                        if (response != null) {
                            val bitmap = BitmapFactory.decodeByteArray(response, 0, response.size)
                            if (bitmap != null) {
                                cachedBitmap = bitmap
                                withContext(Dispatchers.Main) {
                                    sendNotification(bitmap)
                                }
                            }
                        }
                    } catch (e: Exception) {

                    }
                }
                null
            }
        } else null
        sendNotification(bitmap)
    }

    private fun sendNotification(bitmap: Bitmap?) {
        player?.let { player ->
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, getString(R.string.notification_playback_channel_id))
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }.apply {
                setContentTitle(title)
                setContentText(channelName)
                setSmallIcon(R.drawable.notification_icon)
                if (bitmap != null) {
                    setLargeIcon(bitmap)
                }
                setGroup(GROUP_KEY)
                setVisibility(Notification.VISIBILITY_PUBLIC)
                setOngoing(false)
                setOnlyAlertOnce(true)
                if (player.isPlaying && player.playbackParameters.speed == 1f) {
                    setWhen(System.currentTimeMillis() - player.currentPosition)
                    setShowWhen(true)
                    setUsesChronometer(true)
                }
                setStyle(
                    Notification.MediaStyle()
                        .setMediaSession(session?.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2)
                )
                setContentIntent(
                    PendingIntent.getActivity(
                        this@ExoPlayerService,
                        REQUEST_CODE_RESUME,
                        Intent(this@ExoPlayerService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                            action = MainActivity.INTENT_OPEN_PLAYER
                        },
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
                addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this@ExoPlayerService, androidx.media3.session.R.drawable.media3_icon_rewind),
                        ContextCompat.getString(this@ExoPlayerService, R.string.rewind),
                        PendingIntent.getService(
                            this@ExoPlayerService,
                            REQUEST_CODE_REWIND,
                            Intent(this@ExoPlayerService, ExoPlayerService::class.java).apply {
                                action = INTENT_REWIND
                            },
                            PendingIntent.FLAG_IMMUTABLE
                        )
                    ).build()
                )
                if (Util.shouldShowPlayButton(player)) {
                    addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(this@ExoPlayerService, androidx.media3.session.R.drawable.media3_icon_play),
                            ContextCompat.getString(this@ExoPlayerService, R.string.resume),
                            PendingIntent.getService(
                                this@ExoPlayerService,
                                REQUEST_CODE_PLAY_PAUSE,
                                Intent(this@ExoPlayerService, ExoPlayerService::class.java).apply {
                                    action = INTENT_PLAY_PAUSE
                                },
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        ).build()
                    )
                } else {
                    addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(this@ExoPlayerService, androidx.media3.session.R.drawable.media3_icon_pause),
                            ContextCompat.getString(this@ExoPlayerService, R.string.pause),
                            PendingIntent.getService(
                                this@ExoPlayerService,
                                REQUEST_CODE_PLAY_PAUSE,
                                Intent(this@ExoPlayerService, ExoPlayerService::class.java).apply {
                                    action = INTENT_PLAY_PAUSE
                                },
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        ).build()
                    )
                }
                addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this@ExoPlayerService, androidx.media3.session.R.drawable.media3_icon_fast_forward),
                        ContextCompat.getString(this@ExoPlayerService, R.string.forward),
                        PendingIntent.getService(
                            this@ExoPlayerService,
                            REQUEST_CODE_FAST_FORWARD,
                            Intent(this@ExoPlayerService, ExoPlayerService::class.java).apply {
                                action = INTENT_FAST_FORWARD
                            },
                            PendingIntent.FLAG_IMMUTABLE
                        )
                    ).build()
                )
            }.build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    fun setSleepTimer(duration: Long): Long {
        val endTime = sleepTimerEndTime
        sleepTimer?.cancel()
        sleepTimerEndTime = 0L
        if (duration > 0L) {
            sleepTimer = Timer().apply {
                schedule(duration) {
                    Handler(Looper.getMainLooper()).post {
                        savePosition()
                        runAfterPlaybackPersistence {
                            player?.clearMediaItems()
                            player?.playWhenReady = false
                            stopSelf()
                        }
                    }
                }
            }
            sleepTimerEndTime = System.currentTimeMillis() + duration
        }
        return endTime
    }

    fun setStopServiceTimer(start: Boolean) {
        if (start) {
            if (stopServiceTimer == null && player?.isPlaying == false) {
                stopServiceTimer = Timer().apply {
                    schedule(600000) {
                        Handler(Looper.getMainLooper()).post {
                            runAfterPlaybackPersistence {
                                stopSelf()
                            }
                        }
                    }
                }
            }
        } else {
            stopServiceTimer?.cancel()
            stopServiceTimer = null
        }
    }

    fun toggleDynamicsProcessing(): Boolean {
        if (dynamicsProcessing?.enabled == true) {
            dynamicsProcessing?.enabled = false
        } else {
            if (dynamicsProcessing == null) {
                player?.audioSessionId?.let { reinitializeDynamicsProcessing(it) }
            } else {
                dynamicsProcessing?.enabled = true
            }
        }
        val enabled = dynamicsProcessing?.enabled == true
        prefs().edit { putBoolean(C.PLAYER_AUDIO_COMPRESSOR, enabled) }
        return enabled
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
        player?.let { player ->
            if (!player.currentTracks.isEmpty) {
                if (prefs().getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
                    when (type) {
                        VIDEO -> {
                            saveVideoPosition(player.currentPosition)
                        }
                        OFFLINE_VIDEO -> {
                            saveVideoPosition(player.currentPosition)
                        }
                    }
                }
                deletePlaybackStates()
            }
        }
    }

    private fun updateSavedPosition() {
        player?.let { player ->
            if (!player.currentTracks.isEmpty) {
                val currentPosition = player.currentPosition
                val savedPosition = lastSavedPosition
                if (savedPosition == null || currentPosition - savedPosition !in 0..2000) {
                    lastSavedPosition = currentPosition
                    if (prefs().getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
                        saveVideoPosition(currentPosition)
                    }
                    savePlaybackState(currentPosition, !player.playWhenReady)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            INTENT_REWIND -> player?.seekBack()
            INTENT_PLAY_PAUSE -> Util.handlePlayPauseButtonAction(player)
            INTENT_FAST_FORWARD -> player?.seekForward()
            INTENT_START -> create(restorePauseState = true)
            Intent.ACTION_MEDIA_BUTTON -> create(restorePauseState = false)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        if (intent.action == INTENT_START && !created) {
            create(restorePauseState = true)
        }
        return ServiceBinder()
    }

    inner class ServiceBinder : Binder() {
        fun getService() = this@ExoPlayerService
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition()
        val keepPlayback = player?.playWhenReady == true
                && player?.playbackState != Player.STATE_ENDED
                && prefs().getBoolean(C.PLAYER_KEEP_PLAYING_AFTER_TASK_REMOVED, true)
                && prefs().getBoolean(C.SETTINGS_BACKGROUND_PLAYBACK, true)
        if (keepPlayback) {
            return
        }
        player?.playWhenReady = false
        runAfterPlaybackPersistence {
            stopSelf()
        }
    }

    override fun onDestroy() {
        releaseViewingStats()
        clearLiveClipState()
        streamRecoveryJob?.cancel()
        streamRecoveryJob = null
        adAvoidanceJob?.cancel()
        adAvoidanceJob = null
        primaryStreamRestoreJob?.cancel()
        primaryStreamRestoreJob = null
        adController.reset()
        videoOutputState.clear()
        player?.release()
        session?.release()
        bitmapLoadJob?.cancel()
        notificationManager?.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    class CustomHlsPlaylistParserFactory: HlsPlaylistParserFactory {
        override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> {
            return HlsPlaylistParser()
        }

        override fun createPlaylistParser(multivariantPlaylist: HlsMultivariantPlaylist, previousMediaPlaylist: HlsMediaPlaylist?): ParsingLoadable.Parser<HlsPlaylist> {
            return HlsPlaylistParser(multivariantPlaylist, previousMediaPlaylist)
        }
    }

    companion object {
        private const val LIVE_CLIP_DIRECTORY = "live-clips"
        private const val VOD_CLIP_DIRECTORY = "vod-clips"
        private const val VOD_STORAGE_SAFETY_BYTES = 128L * 1024L * 1024L
        private const val AD_TAG = "XtraAd"

        const val MULTIVARIANT_PLAYLIST_REGEX = "^usher\\.ttvnw\\.net$"
        const val MEDIA_PLAYLIST_REGEX = "^(?:[a-z0-9-]+\\.playlist\\.(?:live-video|ttvnw)\\.net|video-weaver\\.[a-z0-9-]+\\.hls\\.ttvnw\\.net)$"

        private const val NOTIFICATION_ID = 1001
        private const val GROUP_KEY = "com.github.andreyasadchy.xtra.PLAYBACK_NOTIFICATIONS"

        private const val REQUEST_CODE_RESUME = 0
        private const val REQUEST_CODE_REWIND = 1
        private const val REQUEST_CODE_PLAY_PAUSE = 2
        private const val REQUEST_CODE_FAST_FORWARD = 3

        private const val INTENT_REWIND = "com.github.andreyasadchy.xtra.REWIND"
        private const val INTENT_PLAY_PAUSE = "com.github.andreyasadchy.xtra.PLAY_PAUSE"
        private const val INTENT_FAST_FORWARD = "com.github.andreyasadchy.xtra.FAST_FORWARD"
        const val INTENT_START = "com.github.andreyasadchy.xtra.START_PLAYBACK_SERVICE"
    }

    private fun logAd(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(AD_TAG, message)
        }
    }
}
