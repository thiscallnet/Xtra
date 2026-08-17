package com.github.andreyasadchy.xtra.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.download.DownloadDialog
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.floor
import kotlin.time.Duration.Companion.milliseconds

@OptIn(UnstableApi::class)
class Media3Fragment : Media3PlayerFragment() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val player: MediaController?
        get() = controllerFuture?.let {
            if (it.isDone && !it.isCancelled) {
                runCatching { it.get() }.getOrNull()
            } else {
                null
            }
        }
    private var playerListener: Player.Listener? = null
    private var serviceStateSynchronized = false
    private val updateProgressAction = Runnable { if (view != null) updateProgress() }

    override fun onPresentationChanged(presentation: PlayerPresentation) {
        player?.sendCustomCommand(
            SessionCommand(PlaybackService.SET_PRESENTATION, Bundle.EMPTY),
            Bundle().apply { putString(PlaybackService.PRESENTATION, presentation.name) },
        )
    }

    override fun onViewingMetadataChanged(title: String?, gameId: String?, gameName: String?) {
        if (videoType != STREAM) return
        player?.sendCustomCommand(
            SessionCommand(
                PlaybackService.UPDATE_VIEWING_METADATA,
                Bundle().apply {
                    putString(PlaybackService.STREAM_ID, requireArguments().getString(KEY_STREAM_ID))
                    // Category identity is a pair. Keep an incomplete refresh
                    // from combining a new name with an old ID (or vice versa).
                    if (gameId != null && gameName != null) {
                        putString(PlaybackService.GAME_ID, gameId)
                        putString(PlaybackService.GAME_NAME, gameName)
                    }
                    title?.let { putString(PlaybackService.TITLE, it) }
                },
            ),
            Bundle.EMPTY,
        )
    }

    override fun onStart() {
        super.onStart()
        cancelPipTransitionPending()
        serviceStateSynchronized = false
        controllerFuture?.let { MediaController.releaseFuture(it) }
        val future = MediaController.Builder(
            requireContext(),
            SessionToken(
                requireContext(),
                ComponentName(requireContext(), PlaybackService::class.java)
            )
        ).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future || future.isCancelled) {
                return@addListener
            }
            val controller = runCatching { future.get() }.getOrNull()
            if (controller == null || view == null || !isAdded) {
                controllerFuture = null
                MediaController.releaseFuture(future)
                return@addListener
            }
            controller.setVideoSurfaceView(binding.playerSurface)
            Log.d("Media3Fragment", "Attached surface to MediaController")
            val listener = object : Player.Listener {

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        clearPlayerError()
                    }
                    binding.bufferingIndicator.isVisible = playbackState == Player.STATE_BUFFERING
                    val showPlayButton = Util.shouldShowPlayButton(player)
                    binding.playerControls.playPause.contentDescription = getString(
                        if (showPlayButton) R.string.player_play else R.string.player_pause_action,
                    )
                    if (showPlayButton) {
                        binding.playerControls.playPause.setImageResource(R.drawable.baseline_play_arrow_black_48)
                        binding.playerControls.playPause.visibility = View.VISIBLE
                    } else {
                        binding.playerControls.playPause.setImageResource(R.drawable.baseline_pause_black_48)
                        if (videoType == STREAM && !requireContext().prefs().getBoolean(C.PLAYER_PAUSE, false)) {
                            binding.playerControls.playPause.visibility = View.GONE
                        }
                    }
                    setPipActions(!showPlayButton)
                    updateMiniPlaybackButton(showPlayButton)
                    updateLiveEdgeStatus()
                    updateProgress()
                    controllerAutoHide = !showPlayButton
                    if (videoType != STREAM && useController) {
                        showController()
                    }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    binding.bufferingIndicator.isVisible = player?.playbackState == Player.STATE_BUFFERING
                    val showPlayButton = Util.shouldShowPlayButton(player)
                    binding.playerControls.playPause.contentDescription = getString(
                        if (showPlayButton) R.string.player_play else R.string.player_pause_action,
                    )
                    if (showPlayButton) {
                        binding.playerControls.playPause.setImageResource(R.drawable.baseline_play_arrow_black_48)
                        binding.playerControls.playPause.visibility = View.VISIBLE
                    } else {
                        binding.playerControls.playPause.setImageResource(R.drawable.baseline_pause_black_48)
                        if (videoType == STREAM && !requireContext().prefs().getBoolean(C.PLAYER_PAUSE, false)) {
                            binding.playerControls.playPause.visibility = View.GONE
                        }
                    }
                    setPipActions(!showPlayButton)
                    updateMiniPlaybackButton(showPlayButton)
                    updateLiveEdgeStatus()
                    updateProgress()
                    controllerAutoHide = !showPlayButton
                    if (videoType != STREAM && useController) {
                        showController()
                    }
                }

                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                    val showPlayButton = Util.shouldShowPlayButton(player)
                    binding.playerControls.playPause.contentDescription = getString(
                        if (showPlayButton) R.string.player_play else R.string.player_pause_action,
                    )
                    if (showPlayButton) {
                        binding.playerControls.playPause.setImageResource(R.drawable.baseline_play_arrow_black_48)
                        binding.playerControls.playPause.visibility = View.VISIBLE
                    } else {
                        binding.playerControls.playPause.setImageResource(R.drawable.baseline_pause_black_48)
                        if (videoType == STREAM && !requireContext().prefs().getBoolean(C.PLAYER_PAUSE, false)) {
                            binding.playerControls.playPause.visibility = View.GONE
                        }
                    }
                    val duration = player?.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0
                    binding.playerControls.progressBar.setDuration(duration)
                    binding.playerControls.duration.text = DateUtils.formatElapsedTime(duration / 1000)
                    binding.playerControls.duration.contentDescription = getString(
                        R.string.player_duration,
                        binding.playerControls.duration.text,
                    )
                    updateMiniPlaybackButton(showPlayButton)
                    updateLiveEdgeStatus()
                    updateProgress()
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize != VideoSize.UNKNOWN && player?.let { it.playbackState != Player.STATE_IDLE } == true) {
                        val aspectRatio = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height
                        binding.aspectRatioFrameLayout.setAspectRatio(aspectRatio)
                    }
                }

                override fun onCues(cueGroup: CueGroup) {
                    binding.subtitleView.setCues(cueGroup.cues)
                }

                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    val duration = player?.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0
                    binding.playerControls.progressBar.setDuration(duration)
                    binding.playerControls.duration.text = DateUtils.formatElapsedTime(duration / 1000)
                    binding.playerControls.duration.contentDescription = getString(
                        R.string.player_duration,
                        binding.playerControls.duration.text,
                    )
                    updateProgress()
                    updateLiveEdgeStatus()
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                        chatFragment?.updatePosition(newPosition.positionMs)
                    }
                }

                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    chatFragment?.updateSpeed(playbackParameters.speed)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateProgress()
                    if (canEnterPictureInPicture()) {
                        requireView().keepScreenOn = isPlaying
                    }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    if (!tracks.isEmpty && !viewModel.loaded.value) {
                        viewModel.loaded.value = true
                        toggleSubtitles(requireContext().prefs().getBoolean(C.PLAYER_SUBTITLES_ENABLED, false))
                    }
                    setSubtitlesButton()
                    if (!tracks.isEmpty) {
                        if (viewModel.qualities?.find { it.name == AUTO_QUALITY } != null
                            && viewModel.quality?.name != AUDIO_ONLY_QUALITY
                            && !viewModel.hidden) {
                            changeQuality(viewModel.quality)
                        }
                        chatFragment?.startReplayChatLoad()
                    }
                }

                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    val duration = player?.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0
                    binding.playerControls.progressBar.setDuration(duration)
                    binding.playerControls.duration.text = DateUtils.formatElapsedTime(duration / 1000)
                    binding.playerControls.duration.contentDescription = getString(
                        R.string.player_duration,
                        binding.playerControls.duration.text,
                    )
                    updateProgress()
                    updateLiveEdgeStatus()
                    if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && !timeline.isEmpty && viewModel.qualities?.find { it.name == AUTO_QUALITY } != null) {
                        viewModel.updateQualities = viewModel.quality?.name != AUDIO_ONLY_QUALITY
                    }
                    if (viewModel.qualities.isNullOrEmpty() || viewModel.updateQualities) {
                        player?.sendCustomCommand(
                            SessionCommand(PlaybackService.GET_QUALITIES, Bundle.EMPTY),
                            Bundle.EMPTY
                        )?.let { result ->
                            result.addListener({
                                if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                                    val rawList = result.get().extras.getStringArray(PlaybackService.NAMES)?.let { names ->
                                        result.get().extras.getStringArray(PlaybackService.CODECS)?.let { codecs ->
                                            result.get().extras.getStringArray(PlaybackService.BITRATES)?.let { bitrates ->
                                                result.get().extras.getStringArray(PlaybackService.URLS)?.let { urls ->
                                                    names.mapIndexed { index, name ->
                                                        VideoQuality(name, codecs.getOrNull(index).takeIf { it != "null" }, bitrates.getOrNull(index).takeIf { it != "null" }?.toIntOrNull(), urls.getOrNull(index))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    val list = rawList?.takeIf { it.isNotEmpty() }?.let(::normalizePlaybackQualities)
                                    if (!list.isNullOrEmpty()) {
                                        viewModel.qualities = list
                                        if (serviceStateSynchronized && viewModel.quality == null) {
                                            setDefaultQuality()
                                        }
                                        changePlayerMode()
                                        if (viewModel.quality?.name == AUDIO_ONLY_QUALITY) {
                                            changeQuality(viewModel.quality)
                                        }
                                    }
                                    if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) {
                                        viewModel.updateQualities = false
                                    }
                                }
                            }, MoreExecutors.directExecutor())
                        }
                    }
                    if (videoType == STREAM) {
                        requestServicePlaybackState(controller)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(tag, "Player error", error)
                    when (videoType) {
                        STREAM -> {
                            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                                // PlaybackService seeks the live item back to its
                                // default position and prepares it again. Do not
                                // flash a generic error while that recovery runs.
                                clearPlayerError()
                                updateLiveEdgeStatus()
                                return
                            }
                            player?.sendCustomCommand(
                                SessionCommand(PlaybackService.GET_ERROR_CODE, Bundle.EMPTY),
                                Bundle.EMPTY
                            )?.let { result ->
                                result.addListener({
                                    if (!isAdded || view == null) {
                                        return@addListener
                                    }
                                    if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                                        val responseCode = result.get().extras.getInt(PlaybackService.RESULT)
                                        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                                        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                                        val isNetworkAvailable = networkCapabilities != null
                                                && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                                && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                                        if (isNetworkAvailable) {
                                            when {
                                                responseCode == 404 -> {
                                                    showPlayerError(R.string.stream_ended)
                                                }
                                                viewModel.useCustomProxy && responseCode >= 400 -> {
                                                    showPlayerError(R.string.proxy_error) { restartPlayer() }
                                                    viewModel.useCustomProxy = false
                                                }
                                                else -> {
                                                    showPlayerError(R.string.player_error) { restartPlayer() }
                                                }
                                            }
                                        } else {
                                            showPlayerError(R.string.connection_error) { restartPlayer() }
                                        }
                                    }
                                }, ContextCompat.getMainExecutor(requireContext()))
                            }
                        }
                        VIDEO -> {
                            player?.sendCustomCommand(
                                SessionCommand(PlaybackService.GET_ERROR_CODE, Bundle.EMPTY),
                                Bundle.EMPTY
                            )?.let { result ->
                                result.addListener({
                                    if (!isAdded || view == null) {
                                        return@addListener
                                    }
                                    if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                                        val responseCode = result.get().extras.getInt(PlaybackService.RESULT)
                                        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                                        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                                        val isNetworkAvailable = networkCapabilities != null
                                                && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                                && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                                        if (isNetworkAvailable) {
                                            when {
                                                viewModel.shouldRetry && responseCode != 0 -> {
                                                    viewModel.shouldRetry = false
                                                    clearPlayerError()
                                                    playVideo(true, player?.currentPosition)
                                                }
                                                responseCode == 403 -> {
                                                    showPlayerError(R.string.video_subscribers_only)
                                                }
                                                else -> {
                                                    showPlayerError(R.string.player_error) { restartPlayer() }
                                                    viewLifecycleOwner.lifecycleScope.launch {
                                                        delay(1500.milliseconds)
                                                        try {
                                                            player?.prepare()
                                                        } catch (e: Exception) {
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            showPlayerError(R.string.connection_error) { restartPlayer() }
                                        }
                                    }
                                }, ContextCompat.getMainExecutor(requireContext()))
                            }
                        }
                        else -> {
                            showPlayerError(R.string.player_error) {
                                player?.let {
                                    try {
                                        it.prepare()
                                        it.playWhenReady = true
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        }
                    }
                }
            }
            controller.addListener(listener)
            playerListener = listener
            controller.sendCustomCommand(
                SessionCommand(PlaybackService.SET_PRESENTATION, Bundle.EMPTY),
                Bundle().apply {
                    putString(
                        PlaybackService.PRESENTATION,
                        if (isMaximized) PlayerPresentation.FULL.name else PlayerPresentation.MINI.name,
                    )
                },
            )
            if (controller.currentMediaItem?.mediaId == expectedMediaId()) {
                viewModel.started = true
                viewModel.loaded.value = !controller.currentTracks.isEmpty
            }
            requestServicePlaybackState(controller)
            if (controller.currentMediaItem != null && controller.playbackState == Player.STATE_IDLE) {
                controller.prepare()
            }
            if (viewModel.restoreQuality) {
                viewModel.restoreQuality = false
                changeQuality(viewModel.previousQuality)
            }
            player?.sendCustomCommand(
                SessionCommand(
                    PlaybackService.SET_SLEEP_TIMER, Bundle().apply {
                        putLong(PlaybackService.DURATION, -1L)
                    }
                ), Bundle.EMPTY
            )?.let { result ->
                result.addListener({
                    if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                        val endTime = result.get().extras.getLong(PlaybackService.RESULT)
                        if (endTime > 0L) {
                            val duration = endTime - System.currentTimeMillis()
                            if (duration > 0L) {
                                (activity as? MainActivity)?.setSleepTimer(duration)
                            } else {
                                minimize()
                                close()
                                (activity as? MainActivity)?.closePlayer()
                            }
                        }
                    }
                }, MoreExecutors.directExecutor())
            }
            if (viewModel.resume) {
                viewModel.resume = false
                player?.let { player ->
                    if (player.playbackState != Player.STATE_ENDED) {
                        player.playWhenReady = true
                    }
                }
            }
            player?.let { player ->
                if (viewModel.loaded.value && player.currentMediaItem == null) {
                    viewModel.started = false
                }
                if (viewModel.started && player.currentMediaItem != null) {
                    chatFragment?.startReplayChatLoad()
                }
                if (canEnterPictureInPicture()) {
                    requireView().keepScreenOn = player.isPlaying
                }
                updateProgress()
                val showPlayButton = Util.shouldShowPlayButton(player)
                binding.playerControls.playPause.contentDescription = getString(
                    if (showPlayButton) R.string.player_play else R.string.player_pause_action,
                )
                if (showPlayButton) {
                    binding.playerControls.playPause.setImageResource(R.drawable.baseline_play_arrow_black_48)
                    binding.playerControls.playPause.visibility = View.VISIBLE
                } else {
                    binding.playerControls.playPause.setImageResource(R.drawable.baseline_pause_black_48)
                    if (videoType == STREAM && !requireContext().prefs().getBoolean(C.PLAYER_PAUSE, false)) {
                        binding.playerControls.playPause.visibility = View.GONE
                    }
                }
            }
            if ((isInitialized || !enableNetworkCheck) && !viewModel.started) {
                startPlayer()
            }
            player?.let { player ->
                setPipActions(player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE && player.playWhenReady)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    override fun initialize() {
        if (player != null && !viewModel.started) {
            startPlayer()
        }
        super.initialize()
    }

    override fun startStream(url: String?) {
        clearPlayerError()
        setQualityText()
        sendStreamToService(url)
    }

    private fun playbackMetadataExtras(): Bundle {
        return Bundle().apply {
            putString(PlaybackService.GAME_ID, requireArguments().getString(KEY_GAME_ID))
            putString(PlaybackService.GAME_SLUG, requireArguments().getString(KEY_GAME_SLUG))
            putString(PlaybackService.GAME_NAME, requireArguments().getString(KEY_GAME_NAME))
            putString(PlaybackService.THUMBNAIL, requireArguments().getString(KEY_THUMBNAIL))
            putString(
                PlaybackService.CREATED_AT,
                requireArguments().getString(KEY_CREATED_AT) ?: requireArguments().getString(KEY_STARTED_AT),
            )
            putInt(PlaybackService.VIEWER_COUNT, requireArguments().getInt(KEY_VIEWER_COUNT, -1))
            putInt(PlaybackService.DURATION_SECONDS, requireArguments().getInt(KEY_DURATION_SECONDS, 0))
            putString(PlaybackService.VIDEO_TYPE, requireArguments().getString(KEY_VIDEO_TYPE))
            putInt(PlaybackService.VIDEO_OFFSET_SECONDS, requireArguments().getInt(KEY_VIDEO_OFFSET_SECONDS, -1))
            putString(PlaybackService.VIDEO_CREATED_AT, requireArguments().getString(KEY_VIDEO_CREATED_AT))
            putString(PlaybackService.VIDEO_ANIMATED_PREVIEW, requireArguments().getString(KEY_VIDEO_ANIMATED_PREVIEW))
            putBoolean(PlaybackService.USE_CUSTOM_PROXY, viewModel.useCustomProxy)
            putString(PlaybackService.VIDEO_ID, requireArguments().getString(KEY_VIDEO_ID))
            putBoolean(PlaybackService.SKIP_ACCESS_TOKEN, viewModel.skipAccessToken)
        }
    }

    private fun requestServicePlaybackState(controller: MediaController? = player) {
        controller ?: return
        controller.sendCustomCommand(
            SessionCommand(PlaybackService.GET_PLAYBACK_STATE, Bundle.EMPTY),
            Bundle.EMPTY,
        ).let { result ->
            result.addListener({
                if (!isAdded || view == null) return@addListener
                serviceStateSynchronized = true
                if (result.get().resultCode != SessionResult.RESULT_SUCCESS) return@addListener
                val extras = result.get().extras
                viewModel.playingAds = extras.getBoolean(PlaybackService.PLAYING_ADS)
                viewModel.hidden = extras.getBoolean(PlaybackService.HIDDEN_FOR_AD)
                viewModel.usingAlternateStream = extras.getBoolean(PlaybackService.USING_ALTERNATE_STREAM)
                viewModel.usingProxy = extras.getBoolean(PlaybackService.USING_PROXY)
                viewModel.useCustomProxy = extras.getBoolean(PlaybackService.USE_CUSTOM_PROXY)
                viewModel.skipAccessToken = extras.getBoolean(PlaybackService.SKIP_ACCESS_TOKEN)
                viewModel.restoreQuality = extras.getBoolean(PlaybackService.RESTORE_QUALITY)
                viewModel.restorePlaylist = extras.getBoolean(PlaybackService.RESTORE_PLAYLIST)
                viewModel.playlistUrl = extras.getString(PlaybackService.PLAYLIST_URL)?.toUri()
                viewModel.qualities = extras.getString(PlaybackService.QUALITIES)?.let { value ->
                    runCatching { Json.decodeFromString<List<VideoQuality>>(value) }
                        .getOrNull()
                        ?.let(::normalizePlaybackQualities)
                }
                viewModel.quality = extras.getString(PlaybackService.QUALITY)?.let { value ->
                    runCatching { Json.decodeFromString<VideoQuality>(value) }.getOrNull()
                }
                viewModel.previousQuality = extras.getString(PlaybackService.PREVIOUS_QUALITY)?.let { value ->
                    runCatching { Json.decodeFromString<VideoQuality>(value) }.getOrNull()
                }
                if (viewModel.hidden) {
                    binding.playerSurface.visibility = View.GONE
                } else {
                    binding.playerSurface.visibility = if (viewModel.quality?.name == AUDIO_ONLY_QUALITY) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
                }
                setQualityText()
                updateMiniPlayerChrome()
            }, MoreExecutors.directExecutor())
        }
    }

    private fun syncPlaybackStateToService() {
        player?.sendCustomCommand(
            SessionCommand(PlaybackService.SYNC_PLAYBACK_STATE, Bundle.EMPTY),
            Bundle().apply {
                viewModel.quality?.let { putString(PlaybackService.QUALITY, Json.encodeToString(it)) }
                viewModel.previousQuality?.let { putString(PlaybackService.PREVIOUS_QUALITY, Json.encodeToString(it)) }
                putBoolean(PlaybackService.RESTORE_QUALITY, viewModel.restoreQuality)
                viewModel.playlistUrl?.let { putString(PlaybackService.PLAYLIST_URL, it.toString()) }
                putBoolean(PlaybackService.RESTORE_PLAYLIST, viewModel.restorePlaylist)
                putBoolean(PlaybackService.USE_CUSTOM_PROXY, viewModel.useCustomProxy)
                putBoolean(PlaybackService.SKIP_ACCESS_TOKEN, viewModel.skipAccessToken)
            },
        )
    }

    private fun sendStreamToService(url: String?, playWhenReady: Boolean? = null) {
        player?.sendCustomCommand(
            SessionCommand(
                PlaybackService.START_STREAM, Bundle().apply {
                    putAll(playbackMetadataExtras())
                    putString(PlaybackService.URI, url)
                    playWhenReady?.let { putBoolean(PlaybackService.PLAY_WHEN_READY, it) }
                    putString(PlaybackService.STREAM_ID, requireArguments().getString(KEY_STREAM_ID))
                    putString(PlaybackService.CHANNEL_ID, requireArguments().getString(KEY_CHANNEL_ID))
                    putString(PlaybackService.CHANNEL_LOGIN, requireArguments().getString(KEY_CHANNEL_LOGIN))
                    putString(PlaybackService.TITLE, requireArguments().getString(KEY_TITLE))
                    putString(PlaybackService.CHANNEL_NAME, requireArguments().getString(KEY_CHANNEL_NAME))
                    putString(PlaybackService.CHANNEL_LOGO, requireArguments().getString(KEY_CHANNEL_IMAGE))
                    putString(PlaybackService.GAME_ID, requireArguments().getString(KEY_GAME_ID))
                    putString(PlaybackService.GAME_NAME, requireArguments().getString(KEY_GAME_NAME))
                }
            ), Bundle.EMPTY
        )
    }

    private fun expectedMediaId(): String? {
        return when (videoType) {
            STREAM -> "stream:${requireArguments().getString(KEY_STREAM_ID).orEmpty()}"
            VIDEO -> "video:${requireArguments().getString(KEY_VIDEO_ID)?.toLongOrNull() ?: 0L}"
            CLIP -> "clip:${requireArguments().getString(KEY_CLIP_ID).orEmpty()}"
            OFFLINE_VIDEO -> "offline:${requireArguments().getInt(KEY_OFFLINE_VIDEO_ID)}"
            else -> null
        }
    }

    override fun startVideo(url: String?, playbackPosition: Long?, multivariantPlaylist: Boolean) {
        clearPlayerError()
        player?.let { player ->
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
            }.build()
            binding.playerSurface.visibility = View.VISIBLE
            player.sendCustomCommand(
                SessionCommand(
                    PlaybackService.START_VIDEO, Bundle().apply {
                        putAll(playbackMetadataExtras())
                        putString(PlaybackService.URI, url)
                        putLong(PlaybackService.PLAYBACK_POSITION, playbackPosition ?: 0)
                        putLong(PlaybackService.VIDEO_ID, requireArguments().getString(KEY_VIDEO_ID)?.toLongOrNull() ?: 0)
                        putString(PlaybackService.CHANNEL_ID, requireArguments().getString(KEY_CHANNEL_ID))
                        putString(PlaybackService.CHANNEL_LOGIN, requireArguments().getString(KEY_CHANNEL_LOGIN))
                        putString(PlaybackService.TITLE, requireArguments().getString(KEY_TITLE))
                        putString(PlaybackService.CHANNEL_NAME, requireArguments().getString(KEY_CHANNEL_NAME))
                        putString(PlaybackService.CHANNEL_LOGO, requireArguments().getString(KEY_CHANNEL_IMAGE))
                        putString(PlaybackService.GAME_ID, requireArguments().getString(KEY_GAME_ID))
                        putString(PlaybackService.GAME_NAME, requireArguments().getString(KEY_GAME_NAME))
                    }
                ), Bundle.EMPTY
            )
        }
    }

    override fun startClip(url: String?) {
        clearPlayerError()
        player?.let { player ->
            if (viewModel.quality?.name == AUDIO_ONLY_QUALITY) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                }.build()
                binding.playerSurface.visibility = View.GONE
            } else {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                }.build()
                binding.playerSurface.visibility = View.VISIBLE
            }
            player.sendCustomCommand(
                SessionCommand(
                    PlaybackService.START_CLIP, Bundle().apply {
                        putAll(playbackMetadataExtras())
                        putString(PlaybackService.URI, url)
                        putString(PlaybackService.CLIP_ID, requireArguments().getString(KEY_CLIP_ID))
                        putString(PlaybackService.CHANNEL_ID, requireArguments().getString(KEY_CHANNEL_ID))
                        putString(PlaybackService.CHANNEL_LOGIN, requireArguments().getString(KEY_CHANNEL_LOGIN))
                        putString(PlaybackService.TITLE, requireArguments().getString(KEY_TITLE))
                        putString(PlaybackService.CHANNEL_NAME, requireArguments().getString(KEY_CHANNEL_NAME))
                        putString(PlaybackService.CHANNEL_LOGO, requireArguments().getString(KEY_CHANNEL_IMAGE))
                        putString(PlaybackService.GAME_ID, requireArguments().getString(KEY_GAME_ID))
                        putString(PlaybackService.GAME_NAME, requireArguments().getString(KEY_GAME_NAME))
                    }
                ), Bundle.EMPTY
            )
        }
    }

    override fun startOfflineVideo(url: String?, position: Long) {
        clearPlayerError()
        player?.let { player ->
            if (viewModel.quality?.name == AUDIO_ONLY_QUALITY) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                }.build()
                binding.playerSurface.visibility = View.GONE
            } else {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                }.build()
                binding.playerSurface.visibility = View.VISIBLE
            }
            player.sendCustomCommand(
                SessionCommand(
                    PlaybackService.START_OFFLINE_VIDEO, Bundle().apply {
                        putAll(playbackMetadataExtras())
                        putString(PlaybackService.URI, url)
                        putInt(PlaybackService.VIDEO_ID, requireArguments().getInt(KEY_OFFLINE_VIDEO_ID))
                        putLong(PlaybackService.PLAYBACK_POSITION, position)
                        putString(PlaybackService.CHANNEL_ID, requireArguments().getString(KEY_CHANNEL_ID))
                        putString(PlaybackService.CHANNEL_LOGIN, requireArguments().getString(KEY_CHANNEL_LOGIN))
                        putString(PlaybackService.TITLE, requireArguments().getString(KEY_TITLE))
                        putString(PlaybackService.CHANNEL_NAME, requireArguments().getString(KEY_CHANNEL_NAME))
                        putString(PlaybackService.CHANNEL_LOGO, requireArguments().getString(KEY_CHANNEL_IMAGE))
                        putString(PlaybackService.GAME_ID, requireArguments().getString(KEY_GAME_ID))
                        putString(PlaybackService.GAME_NAME, requireArguments().getString(KEY_GAME_NAME))
                    }
                ), Bundle.EMPTY
            )
        }
    }

    override fun getCurrentPosition() = player?.currentPosition

    private fun updateMiniPlaybackButton(showPlayButton: Boolean = Util.shouldShowPlayButton(player)) {
        if (view == null) return
        binding.miniPlayPause.setImageResource(
            if (showPlayButton) R.drawable.baseline_play_arrow_black_48 else R.drawable.baseline_pause_black_48,
        )
        binding.miniPlayPause.contentDescription = getString(
            if (showPlayButton) R.string.player_play else R.string.player_pause_action,
        )
        updateMiniPlayerChrome()
    }

    private fun updateLiveEdgeStatus() {
        if (view == null || videoType != STREAM) {
            if (view != null) {
                binding.playerControls.liveStatus.visibility = View.GONE
                binding.miniLiveStatus.visibility = View.GONE
            }
            return
        }
        val controller = player
        if (controller?.isCurrentMediaItemLive != true) {
            binding.playerControls.liveStatus.visibility = View.GONE
            binding.miniLiveStatus.visibility = View.GONE
            return
        }
        val liveOffset = controller.currentLiveOffset
        val atLiveEdge = liveOffset == androidx.media3.common.C.TIME_UNSET || liveOffset <= LIVE_EDGE_TOLERANCE_MS
        val liveText = getString(if (atLiveEdge) R.string.player_live_edge else R.string.player_live_behind)
        with(binding.playerControls.liveStatus) {
            visibility = View.VISIBLE
            text = liveText
            contentDescription = getString(R.string.player_seek_live)
            setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (atLiveEdge) R.color.liveStreamRed else android.R.color.darker_gray,
                )
            )
            setOnClickListener {
                if (!atLiveEdge) seekToLivePosition()
            }
        }
        binding.miniLiveStatus.apply {
            visibility = if (!isMaximized && !inPictureInPicture) View.VISIBLE else View.GONE
            text = liveText
            contentDescription = getString(R.string.player_seek_live)
            setOnClickListener {
                if (!atLiveEdge) seekToLivePosition()
            }
            setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (atLiveEdge) R.color.liveStreamRed else android.R.color.darker_gray,
                )
            )
        }
    }

    override fun isSeekable(): Boolean {
        return videoType != STREAM && player?.isCurrentMediaItemSeekable == true &&
            player?.duration?.let { it > 0L && it != androidx.media3.common.C.TIME_UNSET } == true
    }

    override fun canEnterPictureInPicture(): Boolean {
        val videoSize = player?.videoSize ?: VideoSize.UNKNOWN
        return super.canEnterPictureInPicture() &&
            player?.currentMediaItem != null &&
            videoSize.width > 0 &&
            videoSize.height > 0
    }

    override fun getCurrentSpeed() = player?.playbackParameters?.speed

    override fun getCurrentVolume() = player?.volume

    override fun playPause() {
        Util.handlePlayPauseButtonAction(player)
    }

    override fun rewind() {
        player?.seekBack()
    }

    override fun fastForward() {
        player?.seekForward()
    }

    override fun seek(position: Long) {
        player?.seekTo(position)
    }

    override fun seekToLivePosition() {
        player?.seekToDefaultPosition()
    }

    override fun setPlaybackSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
    }

    override fun changeVolume(volume: Float) {
        player?.volume = volume
    }

    override fun updateProgress() {
        updateLiveEdgeStatus()
        with(binding.playerControls) {
            if (root.isVisible && !progressBar.isPressed) {
                val currentPosition = player?.currentPosition ?: 0
                position.text = DateUtils.formatElapsedTime(currentPosition / 1000)
                position.contentDescription = getString(R.string.player_position, position.text)
                progressBar.setPosition(currentPosition)
                progressBar.setBufferedPosition(player?.bufferedPosition ?: 0)
                root.removeCallbacks(updateProgressAction)
                player?.let { player ->
                    if (player.isPlaying) {
                        val speed = player.playbackParameters.speed
                        val delay = if (speed > 0f) {
                            (progressBar.preferredUpdateDelay / speed).toLong().coerceIn(200L..1000L)
                        } else {
                            1000
                        }
                        root.postDelayed(updateProgressAction, delay)
                    }
                }
            }
        }
    }

    override fun toggleAudioCompressor() {
        player?.sendCustomCommand(
            SessionCommand(
                PlaybackService.TOGGLE_DYNAMICS_PROCESSING,
                Bundle.EMPTY
            ), Bundle.EMPTY
        )?.let { result ->
            result.addListener({
                if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                    val state = result.get().extras.getBoolean(PlaybackService.RESULT)
                    if (state) {
                        binding.playerControls.audioCompressor.setImageResource(R.drawable.baseline_audio_compressor_on_24dp)
                    } else {
                        binding.playerControls.audioCompressor.setImageResource(R.drawable.baseline_audio_compressor_off_24dp)
                    }
                }
            }, MoreExecutors.directExecutor())
        }
    }

    override fun setSubtitlesButton() {
        with(binding.playerControls) {
            val textTracks = player?.currentTracks?.groups?.find { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
            if (textTracks != null && requireContext().prefs().getBoolean(C.PLAYER_SUBTITLES, false)) {
                subtitles.visibility = View.VISIBLE
                if (textTracks.isSelected) {
                    subtitles.setImageResource(androidx.media3.ui.R.drawable.exo_ic_subtitle_on)
                    subtitles.setOnClickListener {
                        showController(force = true)
                        toggleSubtitles(false)
                        requireContext().prefs().edit { putBoolean(C.PLAYER_SUBTITLES_ENABLED, false) }
                    }
                } else {
                    subtitles.setImageResource(androidx.media3.ui.R.drawable.exo_ic_subtitle_off)
                    subtitles.setOnClickListener {
                        showController(force = true)
                        toggleSubtitles(true)
                        requireContext().prefs().edit { putBoolean(C.PLAYER_SUBTITLES_ENABLED, true) }
                    }
                }
            } else {
                subtitles.visibility = View.GONE
            }
            (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setSubtitles(textTracks)
        }
    }

    override fun toggleSubtitles(enabled: Boolean) {
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

    override fun showPlaylistTags(mediaPlaylist: Boolean) {
        player?.sendCustomCommand(
            SessionCommand(
                if (mediaPlaylist) {
                    PlaybackService.GET_MEDIA_PLAYLIST
                } else {
                    PlaybackService.GET_MULTIVARIANT_PLAYLIST
                },
                Bundle.EMPTY
            ), Bundle.EMPTY
        )?.let { result ->
            result.addListener({
                if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                    val tags = result.get().extras.getStringArray(PlaybackService.RESULT)?.joinToString("\n")
                    if (!tags.isNullOrBlank()) {
                        requireContext().getAlertDialogBuilder().apply {
                            setView(NestedScrollView(context).apply {
                                addView(HorizontalScrollView(context).apply {
                                    addView(TextView(context).apply {
                                        text = tags
                                        textSize = 12F
                                        setTextIsSelectable(true)
                                    })
                                })
                            })
                            setNegativeButton(R.string.copy_clip) { _, _ ->
                                val clipboard = ContextCompat.getSystemService(requireContext(), ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(ClipData.newPlainText("label", tags))
                            }
                            setPositiveButton(android.R.string.ok, null)
                        }.show()
                    }
                }
            }, MoreExecutors.directExecutor())
        }
    }

    override fun changeQuality(selectedQuality: VideoQuality?) {
        viewModel.previousQuality = viewModel.quality
        viewModel.quality = selectedQuality
        viewModel.quality?.let { quality ->
            player?.let { player ->
                player.currentMediaItem?.let { mediaItem ->
                    when (quality.name) {
                        AUTO_QUALITY -> {
                            viewModel.playlistUrl?.let { uri ->
                                if (mediaItem.localConfiguration?.uri != uri) {
                                    val position = player.currentPosition
                                    player.setMediaItem(mediaItem.buildUpon().setUri(uri).build())
                                    player.prepare()
                                    player.seekTo(position)
                                }
                                viewModel.playlistUrl = null
                            } ?: player.prepare()
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                            }.build()
                            binding.playerSurface.visibility = View.VISIBLE
                        }
                        AUDIO_ONLY_QUALITY -> {
                            if (viewModel.usingProxy) {
                                player.sendCustomCommand(
                                    SessionCommand(
                                        PlaybackService.TOGGLE_PROXY, Bundle().apply {
                                            putBoolean(PlaybackService.USING_PROXY, false)
                                        }
                                    ), Bundle.EMPTY
                                )
                                viewModel.usingProxy = false
                            }
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                            }.build()
                            binding.playerSurface.visibility = View.GONE
                            quality.url?.let {
                                val position = player.currentPosition
                                if (viewModel.qualities?.find { it.name == AUTO_QUALITY } != null) {
                                    viewModel.playlistUrl = mediaItem.localConfiguration?.uri
                                }
                                player.setMediaItem(mediaItem.buildUpon().setUri(it).build())
                                player.prepare()
                                player.seekTo(position)
                            }
                        }
                        CHAT_ONLY_QUALITY -> {
                            if (viewModel.usingProxy) {
                                player.sendCustomCommand(
                                    SessionCommand(
                                        PlaybackService.TOGGLE_PROXY, Bundle().apply {
                                            putBoolean(PlaybackService.USING_PROXY, false)
                                        }
                                    ), Bundle.EMPTY
                                )
                                viewModel.usingProxy = false
                            }
                            player.stop()
                        }
                        else -> {
                            if (viewModel.qualities?.find { it.name == AUTO_QUALITY } != null) {
                                viewModel.playlistUrl?.let { uri ->
                                    player.currentMediaItem?.let {
                                        val position = player.currentPosition
                                        player.setMediaItem(it.buildUpon().setUri(uri).build())
                                        player.prepare()
                                        player.seekTo(position)
                                        viewModel.playlistUrl = null
                                    }
                                } ?: player.prepare()
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                    binding.playerSurface.visibility = View.VISIBLE
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
                                player.currentMediaItem?.let {
                                    if (it.localConfiguration?.uri?.toString() != quality.url) {
                                        val position = player.currentPosition
                                        player.setMediaItem(it.buildUpon().setUri(quality.url).build())
                                        player.prepare()
                                        player.seekTo(position)
                                    }
                                }
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                }.build()
                                binding.playerSurface.visibility = View.VISIBLE
                            }
                        }
                    }
                    val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                    val cellular = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                    if ((!cellular && requireContext().prefs().getString(C.PLAYER_DEFAULT_QUALITY, "saved") == "saved") || (cellular && requireContext().prefs().getString(C.PLAYER_DEFAULT_CELLULAR_QUALITY, "saved") == "saved")) {
                        requireContext().prefs().edit { putString(C.PLAYER_QUALITY, quality.name) }
                    }
                }
            }
        }
        syncPlaybackStateToService()
    }

    override fun startAudioOnly() {
        player?.let { player ->
            if (player.isConnected) {
                savePosition()
                if (viewModel.usingProxy) {
                    player.sendCustomCommand(
                        SessionCommand(
                            PlaybackService.TOGGLE_PROXY, Bundle().apply {
                                putBoolean(PlaybackService.USING_PROXY, false)
                            }
                        ), Bundle.EMPTY
                    )
                    viewModel.usingProxy = false
                }
                if (viewModel.quality?.name != AUDIO_ONLY_QUALITY) {
                    viewModel.restoreQuality = true
                    viewModel.previousQuality = viewModel.quality
                    viewModel.quality = viewModel.qualities?.find { it.name == AUDIO_ONLY_QUALITY }
                    viewModel.quality?.let {
                        if (player.currentMediaItem != null) {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                            }.build()
                            binding.playerSurface.visibility = View.GONE
                        }
                    }
                }
                player.sendCustomCommand(
                    SessionCommand(PlaybackService.SET_PRESENTATION, Bundle.EMPTY),
                    Bundle().apply { putString(PlaybackService.PRESENTATION, PlayerPresentation.BACKGROUND.name) },
                )
                player.sendCustomCommand(
                    SessionCommand(
                        PlaybackService.SET_SLEEP_TIMER, Bundle().apply {
                            putLong(PlaybackService.DURATION, (activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
                        }
                    ), Bundle.EMPTY
                )
                syncPlaybackStateToService()
            }
        }
        releaseController()
    }

    override fun downloadVideo() {
        player?.sendCustomCommand(
            SessionCommand(PlaybackService.GET_DURATION, Bundle.EMPTY),
            Bundle.EMPTY
        )?.let { result ->
            result.addListener({
                if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                    val totalDuration = result.get().extras.getLong(PlaybackService.RESULT)
                    val qualities = viewModel.qualities?.filter { !it.url.isNullOrBlank() }
                    DownloadDialog.newVideoInstance(
                        id = requireArguments().getString(KEY_VIDEO_ID),
                        channelId = requireArguments().getString(KEY_CHANNEL_ID),
                        channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
                        channelName = requireArguments().getString(KEY_CHANNEL_NAME),
                        channelImage = requireArguments().getString(KEY_CHANNEL_IMAGE),
                        gameId = requireArguments().getString(KEY_GAME_ID),
                        gameSlug = requireArguments().getString(KEY_GAME_SLUG),
                        gameName = requireArguments().getString(KEY_GAME_NAME),
                        title = requireArguments().getString(KEY_TITLE),
                        thumbnail = requireArguments().getString(KEY_THUMBNAIL),
                        createdAt = requireArguments().getString(KEY_CREATED_AT),
                        durationSeconds = requireArguments().getInt(KEY_DURATION_SECONDS),
                        type = requireArguments().getString(KEY_VIDEO_TYPE),
                        animatedPreviewUrl = requireArguments().getString(KEY_VIDEO_ANIMATED_PREVIEW),
                        totalDuration = totalDuration,
                        currentPosition = getCurrentPosition(),
                        qualityNames = qualities?.map { it.name.toString() }?.toTypedArray(),
                        qualityCodecs = qualities?.map { it.codecs.toString() }?.toTypedArray(),
                        qualityBitrates = qualities?.map { it.bitrate.toString() }?.toTypedArray(),
                        qualityUrls = qualities?.map { it.url.toString() }?.toTypedArray(),
                    ).show(childFragmentManager, null)
                }
            }, MoreExecutors.directExecutor())
        }
    }

    override fun close() {
        savePosition()
        val controller = player
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.CLEAR_PLAYBACK, Bundle.EMPTY),
            Bundle.EMPTY,
        )
        controller?.pause()
        controller?.stop()
        if (controller?.mediaItemCount ?: 0 > 0) {
            controller?.removeMediaItem(0)
        }
        releaseController(controller)
    }

    private fun releaseController(controller: MediaController? = player) {
        if (controller != null) {
            Log.d("Media3Fragment", "Detaching MediaController; playback remains service-owned")
        }
        if (view != null) {
            controller?.clearVideoSurfaceView(binding.playerSurface)
        }
        playerListener?.let { controller?.removeListener(it) }
        playerListener = null
        val future = controllerFuture
        controllerFuture = null
        future?.let { MediaController.releaseFuture(it) }
    }

    override fun onStop() {
        // Restore a held VOD 2x gesture before detaching the controller. Once
        // the controller is released there is no UI-side player reference on
        // which to undo the temporary speed.
        restoreTemporarySpeed()
        val isInPictureInPicture = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                runCatching { requireActivity().isInPictureInPictureMode }.getOrDefault(false)
            }
            else -> false
        }
        val shouldPreservePipConnection = isInPictureInPicture || isPipTransitionPending()
        player?.let { player ->
            if (player.isConnected) {
                savePosition()
                val keepBackgroundPlayback = requireContext().prefs().getBoolean(C.SETTINGS_BACKGROUND_PLAYBACK, true)
                if (!keepBackgroundPlayback) {
                    viewModel.resume = player.playWhenReady && player.playbackState != Player.STATE_ENDED
                    player.pause()
                }
                player.sendCustomCommand(
                    SessionCommand(
                        PlaybackService.SET_PRESENTATION,
                        Bundle.EMPTY,
                    ), Bundle().apply {
                        putString(
                            PlaybackService.PRESENTATION,
                            if (shouldPreservePipConnection) {
                                PlayerPresentation.PIP.name
                            } else {
                                PlayerPresentation.BACKGROUND.name
                            },
                        )
                    }
                )
                player.sendCustomCommand(
                    SessionCommand(
                        PlaybackService.SET_SLEEP_TIMER, Bundle().apply {
                            putLong(PlaybackService.DURATION, (activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
                        }
                    ), Bundle.EMPTY
                )
            }
        }
        binding.playerControls.root.removeCallbacks(updateProgressAction)
        if (!shouldPreservePipConnection) {
            releaseController()
        }
        super.onStop()
    }

    override fun onDestroyView() {
        restoreTemporarySpeed()
        super.onDestroyView()
    }

    override fun onPipTransitionTimedOut() {
        releaseController()
    }

    override fun onNetworkRestored() {
        // PlaybackService owns recovery. Re-preparing here would race its
        // backoff and can replace a healthy live playlist after a short loss.
    }

    override fun onNetworkLost() {
        // ExoPlayer keeps the media timeline and retries loading as connectivity returns.
        // Stopping here discards that state and makes a temporary network loss look like a
        // user stop, especially when the fragment is about to be backgrounded.
    }

    companion object {
        private const val LIVE_EDGE_TOLERANCE_MS = 5_000L

        fun newInstance(item: Stream): Media3Fragment {
            return Media3Fragment().apply {
                arguments = getStreamArguments(item)
            }
        }

        fun newInstance(item: Video, offset: Long?, ignoreSavedPosition: Boolean, videoUrl: String? = null): Media3Fragment {
            return Media3Fragment().apply {
                arguments = getVideoArguments(item, offset, ignoreSavedPosition).apply {
                    putString(KEY_URL, videoUrl)
                }
            }
        }

        fun newInstance(item: Clip): Media3Fragment {
            return Media3Fragment().apply {
                arguments = getClipArguments(item)
            }
        }

        fun newInstance(item: OfflineVideo): Media3Fragment {
            return Media3Fragment().apply {
                arguments = getOfflineVideoArguments(item)
            }
        }

        fun newInstance(item: PlaybackState): Media3Fragment {
            return Media3Fragment().apply {
                arguments = getPlaybackStateArguments(item)
            }
        }
    }
}
