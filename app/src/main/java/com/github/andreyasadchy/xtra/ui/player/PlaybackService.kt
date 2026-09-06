package com.github.andreyasadchy.xtra.ui.player

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
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
import androidx.media3.common.Format
import androidx.media3.common.C as Media3C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.XtraModule
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.model.stats.ViewingPlaybackMetadata
import com.github.andreyasadchy.xtra.model.stats.mergeViewingCategoryPatch
import com.github.andreyasadchy.xtra.player.hls.TwitchHlsDiagnosticsSink
import com.github.andreyasadchy.xtra.player.hls.TwitchHlsPlaylistParserFactory
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
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import java.util.Timer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    lateinit var xtraModule: XtraModule

    private var mediaSession: MediaSession? = null
    private var playbackPlayer: ExoPlayer? = null
    private val diagnostics = PlaybackVideoDiagnosticsStore()
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
    private var streamStartupTrace: StreamStartupTrace? = null
    private var liveRewindActive = false
    private var liveRewindVodId: String? = null
    private var liveRewindTransitioning = false

    override fun onCreate() {
        super.onCreate()
        xtraModule = (application as XtraApp).xtraModule
        val player = xtraModule.streamMedia3Runtime.buildPlaybackPlayer(this) {
            setAudioAttributes(AudioAttributes.DEFAULT, prefs().getBoolean(C.PLAYER_AUDIO_FOCUS, false))
            setHandleAudioBecomingNoisy(true)
            setSeekBackIncrementMs((prefs().getString(C.PLAYER_REWIND, "10")?.toLongOrNull() ?: 10) * 1000)
            setSeekForwardIncrementMs((prefs().getString(C.PLAYER_FORWARD, "10")?.toLongOrNull() ?: 10) * 1000)
        }
        playbackPlayer = player
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
                    streamStartupTrace?.let { xtraModule.streamPreviewCoordinator.onFullscreenPlaybackFailed() }
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
                        streamStartupTrace?.markReady()
                        backgroundRecoveryTimer?.cancel()
                        backgroundRecoveryTimer = null
                        backgroundRecoveryAttempt = 0
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    diagnostics.resetForNewMedia()
                    if (mediaItem != null) {
                        diagnostics.update {
                            it.copy(
                                contentProtocol = when (viewingContentType) {
                                    ViewingPlaybackMetadata.CONTENT_TYPE_LIVE,
                                    ViewingPlaybackMetadata.CONTENT_TYPE_VOD -> "HLS"
                                    ViewingPlaybackMetadata.CONTENT_TYPE_CLIP -> "Progressive"
                                    else -> null
                                },
                                isLiveContent = viewingContentType == ViewingPlaybackMetadata.CONTENT_TYPE_LIVE,
                                lowLatencyRequested = viewingContentType == ViewingPlaybackMetadata.CONTENT_TYPE_LIVE &&
                                    prefs().getBoolean(C.PLAYER_LOW_LATENCY, C.DEFAULT_PLAYER_LOW_LATENCY),
                            )
                        }
                    }
                    xtraModule.streamMedia3Runtime.setPrimaryPlaybackMediaItem(mediaItem)
                    syncTwitchHlsDiagnostics(player)
                }

                override fun onRenderedFirstFrame() {
                    streamStartupTrace?.markFirstFrame()
                    streamStartupTrace?.let { xtraModule.streamPreviewCoordinator.onFullscreenPlaybackFirstFrame(it.channelLogin) }
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
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                val classification = classifyVideoDecoder(decoderName)
                diagnostics.update {
                    it.copy(
                        videoDecoderName = decoderName,
                        videoDecoderHardwareAccelerated = classification.hardwareAccelerated,
                    )
                }
                if (BuildConfig.PERF_DIAGNOSTICS) {
                    Log.i(
                        PERF_TAG,
                        "primary videoDecoder=$decoderName " +
                            "hardware=${classification.hardwareAccelerated} " +
                            "initMs=$initializationDurationMs",
                    )
                }
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                diagnostics.recordDroppedVideoFrames(droppedFrames)
                if (BuildConfig.PERF_DIAGNOSTICS) {
                    Log.i(PERF_TAG, "primary droppedFrames=$droppedFrames elapsedMs=$elapsedMs")
                }
            }

            override fun onDownstreamFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                mediaLoadData: MediaLoadData,
            ) {
                val format = mediaLoadData.trackFormat ?: return
                when (mediaLoadData.trackType) {
                    Media3C.TRACK_TYPE_VIDEO -> diagnostics.update {
                        it.copy(
                            selectedVideoWidth = format.width.takeIf { value -> value > 0 },
                            selectedVideoHeight = format.height.takeIf { value -> value > 0 },
                            videoFrameRate = format.frameRate.takeIf { value -> value > 0f },
                            videoBitrate = firstPositiveBitrate(
                                format.averageBitrate,
                                format.peakBitrate,
                                format.bitrate,
                            ),
                            videoCodec = format.codecs,
                            videoMimeType = format.sampleMimeType,
                        )
                    }
                    Media3C.TRACK_TYPE_AUDIO -> diagnostics.update {
                        it.copy(
                            audioCodec = format.codecs,
                            audioMimeType = format.sampleMimeType,
                        )
                    }
                }
                if (BuildConfig.PERF_DIAGNOSTICS && mediaLoadData.trackType == Media3C.TRACK_TYPE_VIDEO) {
                    Log.i(
                        PERF_TAG,
                        "primary videoFormat=${format.width}x${format.height} " +
                            "fps=${format.frameRate} bitrate=${format.bitrate} " +
                            "mime=${format.sampleMimeType} codecs=${format.codecs}",
                    )
                }
            }

            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long,
            ) {
                diagnostics.update {
                    it.copy(
                        bandwidthEstimateBitsPerSecond = bitrateEstimate.takeIf { value -> value > 0L },
                    )
                }
            }

            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
            ) {
                diagnostics.recordLoad(mediaLoadData.dataType, loadEventInfo.bytesLoaded)
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                diagnostics.update {
                    it.copy(
                        audioCodec = format.codecs,
                        audioMimeType = format.sampleMimeType,
                    )
                }
            }
        })
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
                        if (!isTrustedController(controller)) return connectionResult
                        val sessionCommands = connectionResult.availableSessionCommands.buildUpon().apply {
                            add(SessionCommand(START_STREAM, Bundle.EMPTY))
                            add(SessionCommand(START_LIVE_REWIND, Bundle.EMPTY))
                            add(SessionCommand(GET_LIVE_REWIND_STATE, Bundle.EMPTY))
                            add(SessionCommand(UPDATE_VIEWING_METADATA, Bundle.EMPTY))
                            add(SessionCommand(START_VIDEO, Bundle.EMPTY))
                            add(SessionCommand(START_CLIP, Bundle.EMPTY))
                            add(SessionCommand(START_OFFLINE_VIDEO, Bundle.EMPTY))
                            add(SessionCommand(TOGGLE_DYNAMICS_PROCESSING, Bundle.EMPTY))
                            add(SessionCommand(TOGGLE_PROXY, Bundle.EMPTY))
                            add(SessionCommand(SET_BACKGROUND_PLAYBACK, Bundle.EMPTY))
                            add(SessionCommand(SET_SLEEP_TIMER, Bundle.EMPTY))
                            add(SessionCommand(GET_SLEEP_TIMER, Bundle.EMPTY))
                            add(SessionCommand(CHECK_ADS, Bundle.EMPTY))
                            add(SessionCommand(GET_QUALITIES, Bundle.EMPTY))
                            add(SessionCommand(GET_DURATION, Bundle.EMPTY))
                            add(SessionCommand(GET_ERROR_CODE, Bundle.EMPTY))
                            add(SessionCommand(GET_MEDIA_PLAYLIST, Bundle.EMPTY))
                            add(SessionCommand(GET_MULTIVARIANT_PLAYLIST, Bundle.EMPTY))
                            add(SessionCommand(GET_VIDEO_INFO, Bundle.EMPTY))
                        }.build()
                        val playerCommands = connectionResult.availablePlayerCommands.buildUpon()
                            .apply {
                                if (player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) {
                                    add(Player.COMMAND_SET_VIDEO_SURFACE)
                                }
                                if (player.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) {
                                    add(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
                                }
                                if (player.isCommandAvailable(Player.COMMAND_GET_TRACKS)) {
                                    add(Player.COMMAND_GET_TRACKS)
                                }
                            }
                            .build()
                        return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands)
                    }

                    override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                        if (!isTrustedController(controller)) {
                            return Futures.immediateFuture(SessionResult(SessionError.ERROR_UNKNOWN))
                        }
                        return when (customCommand.customAction) {
                            UPDATE_VIEWING_METADATA -> {
                                handleViewingMetadataCommand(customCommand.customExtras, session.player)
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            START_STREAM -> {
                                liveRewindTransitioning = true
                                val result = try {
                                    startLiveStream(player, customCommand.customExtras)
                                } catch (_: Exception) {
                                    liveRewindTransitioning = false
                                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_UNKNOWN))
                                }
                                result.addListener({
                                    val succeeded = runCatching {
                                        result.get().resultCode == SessionResult.RESULT_SUCCESS
                                    }.getOrDefault(false)
                                    if (succeeded) {
                                        liveRewindActive = false
                                        liveRewindVodId = null
                                    }
                                    liveRewindTransitioning = false
                                }, MoreExecutors.directExecutor())
                                return result
                            }
                            START_LIVE_REWIND -> {
                                val uri = customCommand.customExtras.getString(URI)?.toUri()
                                    ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                                val vodId = customCommand.customExtras.getString(REWIND_VIDEO_ID)
                                    ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                                liveRewindTransitioning = true
                                try {
                                    player.setMediaSource(createVodMediaSource(uri))
                                    player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                    player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                    player.prepare()
                                    player.playWhenReady = customCommand.customExtras.getBoolean(PLAY_WHEN_READY, true)
                                    player.seekTo(customCommand.customExtras.getLong(PLAYBACK_POSITION))
                                    liveRewindVodId = vodId
                                    liveRewindActive = true
                                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                                } catch (_: Exception) {
                                    Futures.immediateFuture(SessionResult(SessionError.ERROR_UNKNOWN))
                                } finally {
                                    liveRewindTransitioning = false
                                }
                            }
                            GET_LIVE_REWIND_STATE -> {
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putBoolean(LIVE_REWIND_ACTIVE, liveRewindActive)
                                    putBoolean(LIVE_REWIND_TRANSITIONING, liveRewindTransitioning)
                                    putString(REWIND_VIDEO_ID, liveRewindVodId)
                                }))
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
                                        setPlaylistParserFactory(twitchHlsPlaylistParserFactory())
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
                                xtraModule.streamMedia3Runtime.setPrimaryPlaybackMediaItem(null)
                                session.player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                session.player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                session.player.prepare()
                                session.player.playWhenReady = true
                                session.player.seekTo(position)
                                saveResumptionState(
                                    PlaybackState(
                                        type = BasePlaybackService.VIDEO,
                                        videoId = newId?.toString(),
                                        channelName = channelName,
                                        channelImage = channelLogo,
                                        title = title,
                                        playlistUrl = uri,
                                        position = position,
                                    ),
                                )
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
                                xtraModule.streamMedia3Runtime.setPrimaryPlaybackMediaItem(null)
                                session.player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                session.player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                session.player.prepare()
                                session.player.playWhenReady = true
                                saveResumptionState(
                                    PlaybackState(
                                        type = BasePlaybackService.CLIP,
                                        channelName = channelName,
                                        channelImage = channelLogo,
                                        title = title,
                                        playlistUrl = uri,
                                    ),
                                )
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
                                xtraModule.streamMedia3Runtime.setPrimaryPlaybackMediaItem(null)
                                session.player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                session.player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                session.player.prepare()
                                session.player.playWhenReady = true
                                session.player.seekTo(position)
                                saveResumptionState(
                                    PlaybackState(
                                        type = BasePlaybackService.OFFLINE_VIDEO,
                                        offlineVideoId = newId,
                                        channelName = channelName,
                                        channelImage = channelLogo,
                                        title = title,
                                        playlistUrl = uri,
                                        position = position,
                                    ),
                                )
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
                                xtraModule.streamMedia3Runtime.setProxyMediaPlaylist(
                                    session.player.currentMediaItem?.mediaId,
                                    proxyMediaPlaylist,
                                )
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
                                sleepTimer = null
                                sleepTimerEndTime = 0L
                                if (duration > 0L) {
                                    sleepTimer = Timer().apply {
                                        schedule(duration) {
                                            Handler(Looper.getMainLooper()).post {
                                                savePosition()
                                                runAfterPlaybackPersistence {
                                                    mediaSession?.player?.clearMediaItems()
                                                    xtraModule.streamMedia3Runtime.setPrimaryPlaybackMediaItem(null)
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
                            GET_SLEEP_TIMER -> {
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putLong(RESULT, sleepTimerEndTime)
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
                            GET_VIDEO_INFO -> {
                                Futures.immediateFuture(
                                    SessionResult(
                                        SessionResult.RESULT_SUCCESS,
                                        videoDiagnosticsSnapshot(session.player).toBundle(),
                                    )
                                )
                            }
                            else -> super.onCustomCommand(session, controller, customCommand, args)
                        }
                    }

                    override fun onPlaybackResumption(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo,
                        isForPlay: Boolean,
                    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                        val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val savedState = xtraModule.playbackPersistence
                                    .getPlaybackStatesAndWait()
                                    .firstOrNull()
                                val mediaItem = if (savedState == null) {
                                    null
                                } else {
                                    createResumptionMediaItem(savedState)
                                }
                                val resumptionPosition = savedState?.let {
                                    resolveResumptionPosition(it)
                                } ?: 0L
                                if (savedState != null &&
                                    shouldConsumeResumptionState(isForPlay, mediaItem != null)
                                ) {
                                    xtraModule.playbackPersistence.takePlaybackState()
                                    withContext(Dispatchers.Main.immediate) {
                                        restoreServiceStateForResumption(
                                            state = savedState,
                                            position = resumptionPosition,
                                            player = session.player,
                                        )
                                    }
                                }
                                result.set(
                                    MediaSession.MediaItemsWithStartPosition(
                                        mediaItem?.let { listOf(it) } ?: emptyList(),
                                        0,
                                        resumptionPosition,
                                    ),
                                )
                            } catch (throwable: Throwable) {
                                result.setException(throwable)
                            }
                        }
                        return result
                    }
                }
            )
        }.build()
    }

    private suspend fun createResumptionMediaItem(state: PlaybackState): MediaItem? {
        val uri = if (state.type == BasePlaybackService.STREAM) {
            // Twitch playlist URLs expire. Resolve by channel first and retain
            // the stored URL only as an offline/failure fallback.
            resolveResumptionStreamUri(state) ?: state.playlistUrl
        } else {
            state.playlistUrl ?: state.videoUrl
        }?.takeIf { it.isNotBlank() }
        ?: return null
        return MediaItem.Builder()
            .setUri(uri.toUri())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(state.title)
                    .setArtist(state.channelName)
                    .setArtworkUri(state.channelImage?.toUri())
                    .build(),
            )
            .build()
    }

    private suspend fun resolveResumptionStreamUri(state: PlaybackState): String? {
        if (state.type != BasePlaybackService.STREAM) return null
        val channelLogin = state.channelLogin ?: return null
        return runCatching {
            xtraModule.playerRepository.loadStreamPlaylistUrl(
                context = this,
                networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                gqlHeaders = TwitchApiHelper.getGQLHeaders(
                    this,
                    prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true),
                ),
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
        }.getOrNull()
    }

    /**
     * Restores the service-owned state that normal START_* commands establish.
     * This is deliberately called only for an actual playback resumption.
     */
    private suspend fun resolveResumptionPosition(state: PlaybackState): Long {
        return when (state.type) {
            BasePlaybackService.VIDEO -> state.videoId?.toLongOrNull()
                ?.let { xtraModule.playerRepository.getVideoPosition(it)?.position }
            BasePlaybackService.OFFLINE_VIDEO -> state.offlineVideoId
                ?.let { xtraModule.offlineVideosRepository.getById(it)?.lastWatchPosition }
            else -> null
        } ?: state.position ?: 0L
    }

    private fun restoreServiceStateForResumption(
        state: PlaybackState,
        position: Long,
        player: Player,
    ) {
        finishViewingStats()

        videoId = null
        offlineVideoId = null
        when (state.type) {
            BasePlaybackService.VIDEO -> {
                videoId = state.videoId?.toLongOrNull()
            }
            BasePlaybackService.OFFLINE_VIDEO -> {
                offlineVideoId = state.offlineVideoId
            }
        }
        lastSavedPosition = position
        player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
        player.setPlaybackSpeed(
            resumptionPlaybackSpeed(
                playbackType = state.type,
                configuredSpeed = prefs().getFloat(C.PLAYER_SPEED, 1f),
            ),
        )

        viewingChannelId = state.channelId
        viewingChannelLogin = state.channelLogin
        viewingChannelName = state.channelName
        viewingChannelImage = state.channelImage
        viewingCategoryId = state.gameId
        viewingCategoryName = state.gameName
        viewingCategoryImage = null
        viewingTitle = state.title
        viewingContentType = when (state.type) {
            BasePlaybackService.STREAM -> ViewingPlaybackMetadata.CONTENT_TYPE_LIVE
            BasePlaybackService.VIDEO -> ViewingPlaybackMetadata.CONTENT_TYPE_VOD
            BasePlaybackService.CLIP -> ViewingPlaybackMetadata.CONTENT_TYPE_CLIP
            BasePlaybackService.OFFLINE_VIDEO -> ViewingPlaybackMetadata.CONTENT_TYPE_OFFLINE_VIDEO
            else -> null
        }
        viewingContentId = when (state.type) {
            BasePlaybackService.STREAM -> state.streamId
            BasePlaybackService.VIDEO -> state.videoId
            BasePlaybackService.CLIP -> state.clipId
            BasePlaybackService.OFFLINE_VIDEO -> state.offlineVideoId?.toString()
            else -> null
        }

        if (BuildConfig.DEBUG) {
            Log.d(
                "PlaybackResumption",
                "restored type=${state.type} videoId=$videoId offlineVideoId=$offlineVideoId " +
                    "position=${state.position ?: 0L}",
            )
        }
    }

    private fun saveResumptionState(state: PlaybackState) {
        xtraModule.playbackPersistence.savePlaybackState(state)
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

    private fun startLiveStream(player: ExoPlayer, extras: Bundle): ListenableFuture<SessionResult> {
        backgroundPlayback = false
        val uri = extras.getString(URI)?.takeIf { it.isNotBlank() }
        val channelLogin = extras.getString(CHANNEL_LOGIN)?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val title = extras.getString(TITLE)
        val channelName = extras.getString(CHANNEL_NAME)
        val channelLogo = extras.getString(CHANNEL_LOGO)
        setViewingMetadata(
            ViewingPlaybackMetadata.CONTENT_TYPE_LIVE,
            extras.getString(STREAM_ID),
            extras,
        )
        videoId = null
        offlineVideoId = null
        if (uri == null) {
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        }

        val streamStartElapsedMs = SystemClock.elapsedRealtime()
        val runtime = xtraModule.streamMedia3Runtime
        val login = channelLogin ?: "unknown"
        val previewAlreadyPlaying = channelLogin?.let { xtraModule.streamPreviewCoordinator.isPreviewing(it) } == true
        val mediaItem = runtime.createLiveMediaItem(login, uri, title, channelName, channelLogo)
        val preloaded = channelLogin?.let { runtime.getPreloadedMediaSource(it, uri) }
        val urlWarm = extras.getBoolean(URL_WARM, false) || preloaded != null
        proxyMediaPlaylist = false
        runtime.setProxyMediaPlaylist(mediaItem.mediaId, false)
        streamStartupTrace = StreamStartupTrace(
            channelLogin = login,
            tappedAtMs = extras.getLong(TAP_ELAPSED_MS, -1L).takeIf { it > 0L } ?: streamStartElapsedMs,
            streamStartElapsedMs = streamStartElapsedMs,
            urlAvailableElapsedMs = extras.getLong(URL_AVAILABLE_ELAPSED_MS, -1L).takeIf { it > 0L },
            tapSource = if (extras.getLong(TAP_ELAPSED_MS, -1L) > 0L) "card" else "service",
            mediaLabel = when {
                previewAlreadyPlaying -> "PREVIEW_ALREADY_PLAYING"
                preloaded == null && extras.getBoolean(URL_WARM, false) -> "URL_WARM"
                preloaded == null -> "COLD"
                preloaded.targetStage == androidx.media3.exoplayer.source.preload.DefaultPreloadManager.PreloadStatus.STAGE_SPECIFIED_RANGE_LOADED -> "SAMPLES_WARM"
                preloaded.targetStage == androidx.media3.exoplayer.source.preload.DefaultPreloadManager.PreloadStatus.STAGE_TRACKS_SELECTED -> "TRACKS_SELECTED"
                else -> "SOURCE_PREPARED"
            },
            mediaAgeMs = preloaded?.mediaAgeMs,
            urlWarm = urlWarm,
            previewAlreadyPlaying = previewAlreadyPlaying,
        )
        if (BuildConfig.DEBUG) {
            Log.d(
                "StreamStartup",
                "StreamStartup channel=$login url=${if (urlWarm) "warm" else "cold"} media=${streamStartupTrace?.mediaLabel} " +
                    "mediaAgeMs=${preloaded?.mediaAgeMs ?: -1} preview=$previewAlreadyPlaying " +
                    "tapSource=${streamStartupTrace?.tapSource} tapToUrlAvailableMs=${streamStartupTrace?.tapToUrlAvailableMs() ?: -1} " +
                    "tapToStartStreamMs=${streamStartupTrace?.tapToStartStreamMs() ?: -1}",
            )
        }
        player.setMediaSource(
            preloaded?.mediaSource ?: runtime.createLiveMediaSource(mediaItem)
        )
        runtime.setPrimaryPlaybackMediaItem(preloaded?.mediaItem)
        player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
        player.setPlaybackSpeed(1f)
        player.prepare()
        streamStartupTrace?.prepareCalledAtMs = SystemClock.elapsedRealtime()
        player.playWhenReady = extras.getBoolean(PLAY_WHEN_READY, true)
        saveResumptionState(
            PlaybackState(
                type = BasePlaybackService.STREAM,
                streamId = extras.getString(STREAM_ID),
                channelLogin = login,
                channelName = channelName,
                channelImage = channelLogo,
                title = title,
                playlistUrl = uri,
                paused = !player.playWhenReady,
            ),
        )
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    private class StreamStartupTrace(
        val channelLogin: String,
        private val tappedAtMs: Long,
        val mediaLabel: String,
        private val mediaAgeMs: Long?,
        private val urlWarm: Boolean,
        private val previewAlreadyPlaying: Boolean,
        private val streamStartElapsedMs: Long,
        private val urlAvailableElapsedMs: Long?,
        val tapSource: String,
    ) {
        var prepareCalledAtMs: Long? = null
        private var readyLogged = false
        private var firstFrameLogged = false

        fun tapToUrlAvailableMs(): Long? = urlAvailableElapsedMs?.minus(tappedAtMs)

        fun tapToStartStreamMs(): Long = streamStartElapsedMs - tappedAtMs

        fun markReady() {
            if (readyLogged || !BuildConfig.DEBUG) return
            readyLogged = true
            val now = SystemClock.elapsedRealtime()
            Log.d(
                "StreamStartup",
                "StreamStartup channel=$channelLogin url=${if (urlWarm) "warm" else "cold"} media=$mediaLabel mediaAgeMs=${mediaAgeMs ?: -1} " +
                    "preview=$previewAlreadyPlaying tapSource=$tapSource tapToUrlAvailableMs=${tapToUrlAvailableMs() ?: -1} " +
                    "tapToStartStreamMs=${tapToStartStreamMs()} tapToReadyMs=${now - tappedAtMs} " +
                    "prepareToReadyMs=${prepareCalledAtMs?.let { now - it } ?: -1}",
            )
        }

        fun markFirstFrame() {
            if (firstFrameLogged || !BuildConfig.DEBUG) return
            firstFrameLogged = true
            val now = SystemClock.elapsedRealtime()
            Log.d(
                "StreamStartup",
                "StreamStartup channel=$channelLogin url=${if (urlWarm) "warm" else "cold"} media=$mediaLabel mediaAgeMs=${mediaAgeMs ?: -1} " +
                    "preview=$previewAlreadyPlaying tapSource=$tapSource tapToUrlAvailableMs=${tapToUrlAvailableMs() ?: -1} " +
                    "tapToStartStreamMs=${tapToStartStreamMs()} tapToFirstFrameMs=${now - tappedAtMs}",
            )
        }
    }

    private fun isTrustedController(controller: MediaSession.ControllerInfo): Boolean =
        controller.uid == Process.myUid() && controller.packageName == packageName

    private fun createVodMediaSource(uri: android.net.Uri): MediaSource =
        HlsMediaSource.Factory(
            DefaultDataSource.Factory(
                this@PlaybackService,
                when {
                    prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP) == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                        HttpEngineDataSource.Factory(xtraModule.httpEngine.value, xtraModule.cronetExecutor.value, false, false, null, null, null) { false }
                    }
                    prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP) == C.CRONET && xtraModule.cronetEngine.value != null -> {
                        CronetDataSource.Factory(xtraModule.cronetEngine.value, xtraModule.cronetExecutor.value, false, false, null, null, null) { false }
                    }
                    else -> {
                        OkHttpDataSource.Factory(xtraModule.okHttpClient.value, null) { false }
                    }
                },
            ),
        ).apply {
            setPlaylistParserFactory(twitchHlsPlaylistParserFactory())
        }.createMediaSource(MediaItem.Builder().setUri(uri).build())

    private fun twitchHlsPlaylistParserFactory(): TwitchHlsPlaylistParserFactory =
        TwitchHlsPlaylistParserFactory(
            lowLatencyEnabled = false,
            diagnostics = TwitchHlsDiagnosticsSink { hlsDiagnostics, parsed ->
                if (parsed is androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist) {
                    diagnostics.recordTwitchHlsPlaylist(hlsDiagnostics, parsed)
                }
            },
        )

    private fun syncTwitchHlsDiagnostics(player: Player) {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        xtraModule.streamMedia3Runtime.hlsDiagnosticsFor(mediaId)?.let {
            diagnostics.recordTwitchHlsDiagnostics(it)
        }
    }

    private fun videoDiagnosticsSnapshot(player: Player): PlaybackVideoInfo {
        syncTwitchHlsDiagnostics(player)
        return diagnostics.snapshot(player)
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
        val keepPlayback = player?.playWhenReady == true
                && player.playbackState != Player.STATE_ENDED
                && prefs().getBoolean(C.PLAYER_KEEP_PLAYING_AFTER_TASK_REMOVED, true)
                && prefs().getBoolean(C.SETTINGS_BACKGROUND_PLAYBACK, true)
        if (keepPlayback) {
            backgroundPlayback = true
            return
        }
        player?.clearMediaItems()
        xtraModule.streamMedia3Runtime.setPrimaryPlaybackMediaItem(null)
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
        if (::xtraModule.isInitialized) xtraModule.streamMedia3Runtime.releasePlaybackPlayer(playbackPlayer)
        playbackPlayer = null
        super.onDestroy()
    }

    companion object {
        private const val PERF_TAG = "PlaybackPerf"
        const val START_STREAM = "startStream"
        const val START_LIVE_REWIND = "startLiveRewind"
        const val GET_LIVE_REWIND_STATE = "getLiveRewindState"
        const val UPDATE_VIEWING_METADATA = "updateViewingMetadata"
        const val START_VIDEO = "startVideo"
        const val START_CLIP = "startClip"
        const val START_OFFLINE_VIDEO = "startOfflineVideo"
        const val TOGGLE_DYNAMICS_PROCESSING = "toggleDynamicsProcessing"
        const val TOGGLE_PROXY = "toggleProxy"
        const val SET_BACKGROUND_PLAYBACK = "setBackgroundPlayback"
        const val SET_SLEEP_TIMER = "setSleepTimer"
        const val GET_SLEEP_TIMER = "getSleepTimer"
        const val CHECK_ADS = "checkAds"
        const val GET_QUALITIES = "getQualities"
        const val GET_DURATION = "getDuration"
        const val GET_ERROR_CODE = "getErrorCode"
        const val GET_MEDIA_PLAYLIST = "getMediaPlaylist"
        const val GET_MULTIVARIANT_PLAYLIST = "getMultivariantPlaylist"
        const val GET_VIDEO_INFO = "getVideoInfo"

        const val RESULT = "result"
        const val URI = "uri"
        const val URL_WARM = "urlWarm"
        const val TAP_ELAPSED_MS = "tapElapsedMs"
        const val URL_AVAILABLE_ELAPSED_MS = "urlAvailableElapsedMs"
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
        const val REWIND_VIDEO_ID = "rewindVideoId"
        const val LIVE_REWIND_ACTIVE = "liveRewindActive"
        const val LIVE_REWIND_TRANSITIONING = "liveRewindTransitioning"
        const val BACKGROUND_PLAYBACK = "backgroundPlayback"
        const val DURATION = "duration"
        const val NAMES = "names"
        const val CODECS = "codecs"
        const val BITRATES = "bitrates"
        const val URLS = "urls"

        const val REQUEST_CODE_RESUME = 2
    }
}
