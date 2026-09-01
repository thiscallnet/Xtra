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
import android.view.SurfaceView
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.common.logVideoSurfaceBinding
import com.github.andreyasadchy.xtra.ui.common.logVideoTracks
import com.github.andreyasadchy.xtra.ui.download.DownloadDialog
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.PlayerControlLayout
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.httpProxyHost
import com.github.andreyasadchy.xtra.util.httpProxyPort
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.shouldAvoidTwitchAds
import com.github.andreyasadchy.xtra.util.isTelevision
import com.google.android.material.snackbar.Snackbar
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
    private var streamRecoveryJob: Job? = null
    private var streamRecoveryAttempt = 0
    private var adAvoidanceJob: Job? = null
    private var primaryStreamRestoreJob: Job? = null
    private var qualityRetryJob: Job? = null
    private var qualityRetryAttempts = 0
    private var qualityRequestInFlight = false
    private var qualityRequestGeneration = 0
    private val pendingQualityCallbacks = mutableListOf<() -> Unit>()
    private var nativeCues: List<Cue> = emptyList()
    private var shownLiveCaptionError: String? = null
    private val updateProgressAction = Runnable { if (view != null) updateProgress() }
    private val videoOutputOwner = VideoOutputOwner<Player, SurfaceView>(
        attachTarget = { currentPlayer, target -> currentPlayer.setVideoSurfaceView(target) },
        detachTarget = { currentPlayer, target -> currentPlayer.clearVideoSurfaceView(target) },
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    xtraModule.liveCaptionManager.state.collect { state ->
                        val nextCaptionText = if (videoType == STREAM && state.enabled) state.text else ""
                        updateLiveCaption(nextCaptionText, state.lineShiftToken)
                        if (videoType == STREAM) {
                            binding.playerControls.liveCaptions.setImageResource(
                                if (state.enabled) {
                                    androidx.media3.ui.R.drawable.exo_ic_subtitle_on
                                } else {
                                    androidx.media3.ui.R.drawable.exo_ic_subtitle_off
                                },
                            )
                            binding.playerControls.liveCaptions.contentDescription = getString(
                                if (state.enabled) R.string.disable_live_captions else R.string.enable_live_captions,
                            )
                        }
                        if (state.error.isNullOrBlank()) {
                            shownLiveCaptionError = null
                        } else if (state.error != shownLiveCaptionError) {
                            shownLiveCaptionError = state.error
                            Snackbar.make(
                                binding.root,
                                getString(R.string.live_captions_error, state.error),
                                Snackbar.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }

        binding.playerControls.liveCaptions.apply {
            visibility = if (videoType == STREAM) View.VISIBLE else View.GONE
            setOnClickListener {
                showController(force = true)
                toggleLiveCaptions()
            }
            setOnLongClickListener {
                showController(force = true)
                openLiveCaptionSettings()
                true
            }
        }

        binding.playerTextureView.visibility = View.GONE
        binding.playerSurface.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            binding.playerSurface.setSurfaceLifecycle(
                SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT,
            )
        }
        logVideoSurfaceBinding("on_view_created", player, binding.playerSurface)
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
        logVideoSurfaceBinding("on_start", player, binding.playerSurface)
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
            logVideoSurfaceBinding("controller_connected", controller, binding.playerSurface)
            val listener = object : Player.Listener {

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        onLiveRewindPlaybackError()
                    }
                    if (playbackState == Player.STATE_READY) {
                        streamRecoveryJob?.cancel()
                        streamRecoveryJob = null
                        streamRecoveryAttempt = 0
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
                        if (videoType == STREAM && !requireContext().isTelevision() && !requireContext().prefs().getBoolean(C.PLAYER_PAUSE, false)) {
                            binding.playerControls.playPause.visibility = View.GONE
                        }
                    }
                    setPipActions(!showPlayButton)
                    updateProgress()
                    controllerAutoHide = !requireContext().isTelevision() && !showPlayButton
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
                        if (videoType == STREAM && !requireContext().isTelevision() && !requireContext().prefs().getBoolean(C.PLAYER_PAUSE, false)) {
                            binding.playerControls.playPause.visibility = View.GONE
                        }
                    }
                    setPipActions(!showPlayButton)
                    updateProgress()
                    controllerAutoHide = !requireContext().isTelevision() && !showPlayButton
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
                        if (videoType == STREAM && !requireContext().isTelevision() && !requireContext().prefs().getBoolean(C.PLAYER_PAUSE, false)) {
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
                    updateProgress()
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize != VideoSize.UNKNOWN && player?.let { it.playbackState != Player.STATE_IDLE } == true) {
                        val aspectRatio = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height
                        binding.aspectRatioFrameLayout.setAspectRatio(aspectRatio)
                    }
                }

                override fun onCues(cueGroup: CueGroup) {
                    nativeCues = cueGroup.cues
                    renderSubtitleOverlay()
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
                    logVideoTracks(
                        reason = "Media3Fragment.onTracksChanged",
                        player = player,
                    )
                    if (!tracks.isEmpty && !viewModel.loaded.value) {
                        viewModel.loaded.value = true
                        toggleSubtitles(requireContext().prefs().getBoolean(C.PLAYER_SUBTITLES_ENABLED, false))
                    }
                    setSubtitlesButton()
                    if (!tracks.isEmpty) {
                        if (viewModel.qualities.isNullOrEmpty() || viewModel.updateQualities) {
                            requestQualities()
                        }
                        if (viewModel.qualities?.find { it.name == AUTO_QUALITY } != null
                            && viewModel.quality?.name != AUDIO_ONLY_QUALITY
                            && !viewModel.hidden) {
                            changeQuality(viewModel.quality, persistSavedQuality = false)
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
                    if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && !timeline.isEmpty && viewModel.qualities?.find { it.name == AUTO_QUALITY } != null) {
                        viewModel.updateQualities = viewModel.quality?.name != AUDIO_ONLY_QUALITY
                    }
                    if (viewModel.qualities.isNullOrEmpty() || viewModel.updateQualities) {
                        requestQualities()
                    }
                    if (videoType == STREAM && !isLiveRewindActiveOrSwitching()) {
                        val avoidAds = requireContext().prefs().shouldAvoidTwitchAds()
                        val suppressAds = avoidAds
                        val useProxy = requireContext().prefs().httpProxyHost() != null
                                && requireContext().prefs().httpProxyPort() != null
                        if (suppressAds || useProxy) {
                            player?.sendCustomCommand(
                                SessionCommand(PlaybackService.CHECK_ADS, Bundle.EMPTY),
                                Bundle.EMPTY
                            )?.let { result ->
                                result.addListener({
                                    if (!isAdded || view == null || isLiveRewindActiveOrSwitching()) {
                                        return@addListener
                                    }
                                    if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                                        val playingAds = result.get().extras.getBoolean(PlaybackService.RESULT)
                                        val oldValue = viewModel.playingAds
                                        viewModel.playingAds = playingAds
                                        setQualityText()
                                        if (playingAds) {
                                            if (avoidAds) {
                                                if (adAvoidanceJob?.isActive != true) {
                                                    val playerTypes = viewModel.playerTypesForAd(
                                                        requireContext().prefs().getString(C.TOKEN_PLAYER_TYPE, "site")
                                                    )
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
                                            viewModel.onCleanAdPlaylist()
                                            restoreAdPlayback()
                                            schedulePrimaryStreamRestore()
                                        }
                                    }
                                }, ContextCompat.getMainExecutor(requireContext()))
                            }
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(tag, "Player error", error)
                    if (onLiveRewindPlaybackError()) return
                    if (isLiveRewindActiveOrSwitching()) return
                    when (videoType) {
                        STREAM -> {
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
                                                    scheduleStreamRecovery()
                                                }
                                                else -> {
                                                    showPlayerError(R.string.player_error) { restartPlayer() }
                                                    scheduleStreamRecovery()
                                                }
                                            }
                                        } else {
                                            showPlayerError(R.string.connection_error) { restartPlayer() }
                                            scheduleStreamRecovery()
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

                override fun onRenderedFirstFrame() {
                    logVideoSurfaceBinding("first_frame", controller, binding.playerSurface)
                }
            }
            val restored = viewModel.videoOutputState.restoreIfNeeded {
                binding.playerSurface.visibility = View.VISIBLE
                attachVideoOutput(controller)
                true
            }
            if (!restored) {
                attachVideoOutput(controller)
            }
            controller.addListener(listener)
            playerListener = listener
            // A listener added after the controller is already prepared does
            // not receive an initial onTracksChanged callback. Retry any
            // quality request that arrived while the controller was connecting.
            if (controller.currentMediaItem != null) {
                requestQualities()
            }
            controller.sendCustomCommand(
                SessionCommand(
                    PlaybackService.SET_BACKGROUND_PLAYBACK,
                    Bundle().apply { putBoolean(PlaybackService.BACKGROUND_PLAYBACK, false) }
                ), Bundle.EMPTY
            )
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
                        player.prepare()
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
                    if (videoType == STREAM && !requireContext().isTelevision() && !requireContext().prefs().getBoolean(C.PLAYER_PAUSE, false)) {
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

    private fun scheduleStreamRecovery() {
        val context = context ?: return
        if (!isAdded || view == null
            || !context.prefs().getBoolean(C.PLAYER_AUTO_RECOVER_STREAMS, true)
            || videoType != STREAM
            || isLiveRewindActiveOrSwitching()
            || player?.playWhenReady != true
        ) {
            return
        }
        streamRecoveryJob?.cancel()
        val delayMs = (1500L shl streamRecoveryAttempt.coerceAtMost(3)).coerceAtMost(12000L)
        streamRecoveryAttempt = (streamRecoveryAttempt + 1).coerceAtMost(3)
        streamRecoveryJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(delayMs)
            if (context.prefs().getBoolean(C.PLAYER_AUTO_RECOVER_STREAMS, true)
                && player?.playWhenReady == true
                && isAdded
                && view != null
            ) {
                try {
                    restartPlayer()
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun initialize() {
        if (player != null && !viewModel.started) {
            startPlayer()
        }
        super.initialize()
    }

    private fun suppressAdPlayback() {
        if (!viewModel.hidden) {
            viewModel.hidden = true
            player?.let { player ->
                if (viewModel.quality?.name != AUDIO_ONLY_QUALITY) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                        setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                    }.build()
                    binding.playerSurface.visibility = View.GONE
                }
                player.volume = 0f
            }
            Snackbar.make(binding.playerBackground, R.string.waiting_ads, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun restoreAdPlayback() {
        if (viewModel.hidden) {
            viewModel.hidden = false
            player?.let { player ->
                if (viewModel.quality?.name != AUDIO_ONLY_QUALITY) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                        setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                    }.build()
                    binding.playerSurface.visibility = View.VISIBLE
                }
                player.volume = requireContext().prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
            }
        }
    }

    private fun fallbackFromAd(useProxy: Boolean, suppressAds: Boolean) {
        if (viewModel.usingProxy) {
            player?.sendCustomCommand(
                SessionCommand(
                    PlaybackService.TOGGLE_PROXY, Bundle().apply {
                        putBoolean(PlaybackService.USING_PROXY, false)
                    }
                ), Bundle.EMPTY
            )
            viewModel.usingProxy = false
            viewModel.stopProxy = true
            return
        }
        val playlist = viewModel.quality?.url
        if (!viewModel.stopProxy && !playlist.isNullOrBlank() && useProxy) {
            player?.sendCustomCommand(
                SessionCommand(
                    PlaybackService.TOGGLE_PROXY, Bundle().apply {
                        putBoolean(PlaybackService.USING_PROXY, true)
                    }
                ), Bundle.EMPTY
            )
            viewModel.usingProxy = true
            viewLifecycleOwner.lifecycleScope.launch {
                for (i in 0 until 10) {
                    delay(10.seconds)
                    if (!viewModel.checkPlaylist(requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP), playlist)) {
                        break
                    }
                }
                player?.sendCustomCommand(
                    SessionCommand(
                        PlaybackService.TOGGLE_PROXY, Bundle().apply {
                            putBoolean(PlaybackService.USING_PROXY, false)
                        }
                    ), Bundle.EMPTY
                )
                viewModel.usingProxy = false
            }
        } else if (suppressAds) {
            suppressAdPlayback()
        }
    }

    private fun tryAlternateStream(playerTypes: List<String>, useProxy: Boolean) {
        if (isLiveRewindActiveOrSwitching()) return
        val channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN) ?: run {
            fallbackFromAd(useProxy, suppressAds = true)
            return
        }
        adAvoidanceJob = viewLifecycleOwner.lifecycleScope.launch {
            val candidate = try {
                viewModel.loadCleanStreamPlaylistUrl(channelLogin, playerTypes)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (candidate != null && isAdded && view != null && !isLiveRewindActiveOrSwitching()) {
                primaryStreamRestoreJob?.cancel()
                primaryStreamRestoreJob = null
                viewModel.usingAlternateStream = true
                setQualityText()
                viewModel.qualities = null
                viewModel.updateQualities = true
                viewModel.usingProxy = false
                try {
                    sendStreamToService(candidate.url, player?.playWhenReady)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    viewModel.usingAlternateStream = false
                    setQualityText()
                    fallbackFromAd(useProxy, suppressAds = true)
                }
            } else if (!isLiveRewindActiveOrSwitching()) {
                fallbackFromAd(useProxy, suppressAds = true)
            }
        }
    }

    private fun schedulePrimaryStreamRestore() {
        if (isLiveRewindActiveOrSwitching() || !viewModel.usingAlternateStream || primaryStreamRestoreJob?.isActive == true) {
            return
        }
        val channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN) ?: return
        val primaryPlayerType = requireContext().prefs().getString(C.TOKEN_PLAYER_TYPE, "site") ?: "site"
        primaryStreamRestoreJob = viewLifecycleOwner.lifecycleScope.launch {
            while (viewModel.usingAlternateStream && !isLiveRewindActiveOrSwitching() && isAdded && view != null) {
                val candidate = try {
                    viewModel.loadCleanStreamPlaylistUrl(
                        channelLogin = channelLogin,
                        playerTypes = listOf(primaryPlayerType),
                        requireVerifiedClean = true,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (candidate?.verifiedClean == true && isAdded && view != null) {
                    try {
                        viewModel.qualities = null
                        viewModel.updateQualities = true
                        viewModel.usingProxy = false
                        sendStreamToService(candidate.url, player?.playWhenReady)
                        viewModel.usingAlternateStream = false
                        setQualityText()
                        viewModel.resetAdController()
                        viewModel.playingAds = false
                        restoreAdPlayback()
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Keep the alternate source active and retry on the next pass.
                    }
                }
                delay(10.seconds)
            }
        }
    }

    override fun startStream(url: String?) {
        startStreamInternal(url, null)
    }

    private fun startStreamInternal(url: String?, playWhenReady: Boolean?): ListenableFuture<SessionResult>? {
        clearPlayerError()
        adAvoidanceJob?.cancel()
        adAvoidanceJob = null
        primaryStreamRestoreJob?.cancel()
        primaryStreamRestoreJob = null
        viewModel.usingAlternateStream = false
        viewModel.resetAdController()
        viewModel.playingAds = false
        viewModel.qualities = null
        viewModel.quality = null
        viewModel.updateQualities = true
        setQualityText()
        return sendStreamToService(url, playWhenReady)
    }

    private fun sendStreamToService(url: String?, playWhenReady: Boolean? = null): ListenableFuture<SessionResult>? {
        return player?.sendCustomCommand(
            SessionCommand(
                PlaybackService.START_STREAM, Bundle().apply {
                    putString(PlaybackService.URI, url)
                    playWhenReady?.let { putBoolean(PlaybackService.PLAY_WHEN_READY, it) }
                    putString(PlaybackService.STREAM_ID, requireArguments().getString(KEY_STREAM_ID))
                    putString(PlaybackService.CHANNEL_ID, requireArguments().getString(KEY_CHANNEL_ID))
                    putString(PlaybackService.CHANNEL_LOGIN, requireArguments().getString(KEY_CHANNEL_LOGIN))
                    putString(PlaybackService.TITLE, requireArguments().getString(KEY_TITLE))
                    putString(PlaybackService.CHANNEL_NAME, requireArguments().getString(KEY_CHANNEL_NAME))
                    putString(PlaybackService.CHANNEL_LOGO, requireArguments().getString(KEY_CHANNEL_IMAGE))
                    putBoolean(PlaybackService.URL_WARM, viewModel.streamUrlWarm.value)
                    requireArguments().getLong(KEY_TAP_ELAPSED_MS, -1L).takeIf { it > 0L }?.let {
                        putLong(PlaybackService.TAP_ELAPSED_MS, it)
                    }
                    viewModel.streamUrlAvailableElapsedMs?.let {
                        putLong(PlaybackService.URL_AVAILABLE_ELAPSED_MS, it)
                    }
                    putString(PlaybackService.GAME_ID, requireArguments().getString(KEY_GAME_ID))
                    putString(PlaybackService.GAME_NAME, requireArguments().getString(KEY_GAME_NAME))
                }
            ), Bundle.EMPTY
        )
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

    override suspend fun startLiveRewind(vodId: String, positionMs: Long): Boolean {
        val controller = player ?: return false
        val url = try {
            viewModel.loadRewindVideoPlaylistUrl(vodId)
        } catch (_: Exception) {
            null
        } ?: return false
        adAvoidanceJob?.cancel()
        adAvoidanceJob = null
        primaryStreamRestoreJob?.cancel()
        primaryStreamRestoreJob = null
        viewModel.usingAlternateStream = false
        viewModel.resetAdController()
        viewModel.playingAds = false
        viewModel.qualities = null
        viewModel.quality = null
        viewModel.updateQualities = true
        val result = controller.sendCustomCommand(
            SessionCommand(
                PlaybackService.START_LIVE_REWIND,
                Bundle().apply {
                    putString(PlaybackService.URI, url)
                    putLong(PlaybackService.PLAYBACK_POSITION, positionMs)
                    putBoolean(PlaybackService.PLAY_WHEN_READY, controller.playWhenReady)
                    putString(PlaybackService.REWIND_VIDEO_ID, vodId)
                },
            ),
            Bundle.EMPTY,
        )
        return withContext(Dispatchers.IO) {
            runCatching { result.get().resultCode == SessionResult.RESULT_SUCCESS }.getOrDefault(false)
        }
    }

    override suspend fun returnToLivePlayback(): Boolean {
        val controller = player ?: return false
        val wasPlaying = controller.playWhenReady
        val login = requireArguments().getString(KEY_CHANNEL_LOGIN) ?: return false
        val proxyUrl = requireContext().prefs().getString(C.PLAYER_PROXY_URL, "")
        val url = if (viewModel.useCustomProxy && !proxyUrl.isNullOrBlank()) {
            proxyUrl.replace("\$channel", login)
        } else {
            try {
                viewModel.loadFreshStreamPlaylistUrl(login)
            } catch (_: Exception) {
                null
            }
        } ?: return false
        val result = startStreamInternal(url, wasPlaying) ?: return false
        return withContext(Dispatchers.IO) {
            runCatching { result.get().resultCode == SessionResult.RESULT_SUCCESS }.getOrDefault(false)
        }
    }

    override suspend fun getLiveRewindVodId(): String? {
        val result = player?.sendCustomCommand(
            SessionCommand(PlaybackService.GET_LIVE_REWIND_STATE, Bundle.EMPTY),
            Bundle.EMPTY,
        ) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                result.get().takeIf { it.resultCode == SessionResult.RESULT_SUCCESS }
                    ?.extras?.takeIf { it.getBoolean(PlaybackService.LIVE_REWIND_ACTIVE) }
                    ?.getString(PlaybackService.REWIND_VIDEO_ID)
            }.getOrNull()
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
    }

    override fun changeVolume(volume: Float) {
        player?.volume = volume
    }

    override fun updateProgress() {
        if (isLiveRewindAvailable()) {
            updateLiveRewindProgress()
            return
        }
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
                subtitles.setOnClickListener(null)
                subtitles.visibility = View.GONE
            }
            (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setSubtitles(textTracks)
            PlayerControlLayout.applyToPlayer(requireContext(), binding)
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

    override fun changeQuality(selectedQuality: VideoQuality?, persistSavedQuality: Boolean) {
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
                    if (persistSavedQuality) {
                        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                        val cellular = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                        if ((!cellular && requireContext().prefs().getString(C.PLAYER_DEFAULT_QUALITY, "saved") == "saved") || (cellular && requireContext().prefs().getString(C.PLAYER_DEFAULT_CELLULAR_QUALITY, "saved") == "saved")) {
                            requireContext().prefs().edit { putString(C.PLAYER_QUALITY, quality.name) }
                        }
                    }
                }
            }
        }
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
                    SessionCommand(
                        PlaybackService.SET_BACKGROUND_PLAYBACK,
                        Bundle().apply { putBoolean(PlaybackService.BACKGROUND_PLAYBACK, true) }
                    ), Bundle.EMPTY
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
        releaseV2ChatSession()
        savePosition()
        val controller = player
        controller?.pause()
        controller?.stop()
        if (controller?.mediaItemCount ?: 0 > 0) {
            controller?.removeMediaItem(0)
        }
        releaseController(controller)
    }

    private fun releaseController(controller: MediaController? = player) {
        qualityRequestGeneration++
        qualityRequestInFlight = false
        streamRecoveryJob?.cancel()
        streamRecoveryJob = null
        streamRecoveryAttempt = 0
        adAvoidanceJob?.cancel()
        adAvoidanceJob = null
        if (view != null) {
            detachVideoOutput()
        }
        playerListener?.let { controller?.removeListener(it) }
        playerListener = null
        val future = controllerFuture
        controllerFuture = null
        future?.let { MediaController.releaseFuture(it) }
    }

    override fun ensureQualities(onReady: () -> Unit) {
        if (getQualities().isNullOrEmpty()) {
            requestQualities(onReady)
        } else {
            onReady()
        }
    }

    private fun requestQualities(onReady: (() -> Unit)? = null) {
        onReady?.let { pendingQualityCallbacks += it }
        if (qualityRequestInFlight) return

        val currentPlayer = player ?: run {
            // Keep the request pending until the controller is connected. A quality
            // tap during service startup must not be lost.
            return
        }
        qualityRequestInFlight = true
        val requestGeneration = qualityRequestGeneration
        val requestedPlayer = currentPlayer
        val result = currentPlayer.sendCustomCommand(
            SessionCommand(PlaybackService.GET_QUALITIES, Bundle.EMPTY),
            Bundle.EMPTY,
        )
        result.addListener({
            // The command future is not lifecycle-bound. A controller can be
            // released, or the fragment view can be destroyed, before the
            // service responds. Do not let an old response touch a new view.
            if (requestGeneration != qualityRequestGeneration ||
                requestedPlayer !== player ||
                !isAdded ||
                view == null
            ) {
                return@addListener
            }
            val response = runCatching { result.get() }.getOrNull()
            if (response?.resultCode == SessionResult.RESULT_SUCCESS) {
                val extras = response.extras
                val names = extras.getStringArray(PlaybackService.NAMES)
                val codecs = extras.getStringArray(PlaybackService.CODECS)
                val bitrates = extras.getStringArray(PlaybackService.BITRATES)
                val urls = extras.getStringArray(PlaybackService.URLS)
                val list = if (names != null && codecs != null && bitrates != null && urls != null) {
                    names.mapIndexed { index, name ->
                        VideoQuality(
                            name,
                            codecs.getOrNull(index).takeIf { it != "null" },
                            bitrates.getOrNull(index).takeIf { it != "null" }?.toIntOrNull(),
                            urls.getOrNull(index),
                        )
                    }
                } else {
                    null
                }
                if (!list.isNullOrEmpty()) {
                    viewModel.qualities = list.asSequence()
                        .sortedByDescending { it.bitrate }
                        .sortedByDescending {
                            it.name?.substringAfter("p", "")?.takeWhile { value -> value.isDigit() }?.toIntOrNull()
                        }
                        .sortedByDescending {
                            it.name?.substringBefore("p", "")?.takeWhile { value -> value.isDigit() }?.toIntOrNull()
                        }
                        .toMutableList()
                        .apply {
                            add(0, VideoQuality(AUTO_QUALITY))
                            find { it.name.equals("source", true) }?.let { source ->
                                remove(source)
                                add(1, VideoQuality(SOURCE_QUALITY, source.codecs, source.bitrate, source.url))
                            }
                            val audio = find { it.name?.startsWith("audio", true) == true }
                            audio?.let { remove(it) }
                            add(VideoQuality(AUDIO_ONLY_QUALITY, audio?.codecs, audio?.bitrate, audio?.url))
                        }
                    viewModel.updateQualities = false
                    setDefaultQuality()
                    changePlayerMode()
                    if (viewModel.quality?.name == AUDIO_ONLY_QUALITY) {
                        changeQuality(viewModel.quality, persistSavedQuality = false)
                    }
                    setQualityText()
                    qualityRetryAttempts = 0
                }
            }
            qualityRequestInFlight = false
            // The service can answer before the HLS multivariant playlist is
            // available. Keep callbacks pending; onTracksChanged/onTimelineChanged
            // will retry and only a non-empty list may open the dialog.
            if (!viewModel.qualities.isNullOrEmpty()) {
                val callbacks = pendingQualityCallbacks.toList()
                pendingQualityCallbacks.clear()
                callbacks.forEach { it() }
            } else if (pendingQualityCallbacks.isNotEmpty() && qualityRetryAttempts < MAX_QUALITY_RETRY_ATTEMPTS) {
                qualityRetryAttempts++
                qualityRetryJob?.cancel()
                qualityRetryJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(QUALITY_RETRY_DELAY_MS)
                    qualityRetryJob = null
                    if (view != null && player != null && viewModel.qualities.isNullOrEmpty()) {
                        requestQualities()
                    }
                }
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    override fun onStop() {
        logVideoSurfaceBinding("on_stop", player, view?.findViewById(R.id.playerSurface))
        super.onStop()
        val isInPIPMode = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> requireActivity().isInPictureInPictureMode
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> !useController && isMaximized
            else -> false
        }
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
                if (requireContext().prefs().getBoolean(C.SETTINGS_BACKGROUND_PLAYBACK, true)) {
                    if (!isInPIPMode && player.playWhenReady && viewModel.quality?.name != AUDIO_ONLY_QUALITY) {
                        if (player.currentMediaItem != null) {
                            viewModel.videoOutputState.markDetachedForBackground()
                            binding.playerSurface.visibility = View.GONE
                        }
                    }
                } else {
                    viewModel.resume = player.playWhenReady && player.playbackState != Player.STATE_ENDED
                    player.pause()
                }
                player.sendCustomCommand(
                    SessionCommand(
                        PlaybackService.SET_BACKGROUND_PLAYBACK,
                        Bundle().apply { putBoolean(PlaybackService.BACKGROUND_PLAYBACK, true) }
                    ), Bundle.EMPTY
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
        if (!isInPIPMode) {
            releaseController()
        }
    }

    override fun onNetworkRestored() {
        if (isResumed) {
            if (videoType == STREAM && !isLiveRewindActiveOrSwitching()) {
                if (player?.playWhenReady == true) {
                    restartPlayer()
                }
            } else {
                player?.prepare()
            }
        }
    }

    override fun onNetworkLost() {
        // ExoPlayer keeps the media timeline and retries loading as connectivity returns.
        // Stopping here discards that state and makes a temporary network loss look like a
        // user stop, especially when the fragment is about to be backgrounded.
    }

    override fun onDestroyView() {
        qualityRequestGeneration++
        qualityRequestInFlight = false
        nativeCues = emptyList()
        shownLiveCaptionError = null
        qualityRetryJob?.cancel()
        qualityRetryJob = null
        qualityRetryAttempts = 0
        pendingQualityCallbacks.clear()
        binding.liveCaptionView.clearCaption()
        logVideoSurfaceBinding("on_destroy_view", player, view?.findViewById(R.id.playerSurface))
        detachVideoOutput()
        super.onDestroyView()
    }

    private fun renderSubtitleOverlay() {
        // Live captions have their own fixed-size view. Keeping SubtitleView owned by
        // native cues prevents every partial result from changing the cue window geometry.
        binding.subtitleView.setCues(nativeCues)
    }

    /** Updates only the text inside the fixed caption container. */
    private fun updateLiveCaption(text: String, lineShiftToken: Long) {
        binding.liveCaptionView.submitCaption(text, lineShiftToken)
    }

    private fun attachVideoOutput(currentPlayer: Player) {
        videoOutputOwner.attach(currentPlayer, binding.playerSurface)
        logVideoSurfaceBinding("attach", currentPlayer, binding.playerSurface)
    }

    private fun detachVideoOutput() {
        val currentPlayer = videoOutputOwner.attachedPlayer() ?: return
        logVideoSurfaceBinding("detach", currentPlayer, binding.playerSurface)
        videoOutputOwner.clear()
    }

    companion object {
        private const val QUALITY_RETRY_DELAY_MS = 500L
        private const val MAX_QUALITY_RETRY_ATTEMPTS = 10

        fun newInstance(item: Stream, tapElapsedMs: Long? = null): Media3Fragment {
            return Media3Fragment().apply {
                arguments = getStreamArguments(item, tapElapsedMs)
            }
        }

        fun newInstance(item: Video, offset: Long?, ignoreSavedPosition: Boolean): Media3Fragment {
            return Media3Fragment().apply {
                arguments = getVideoArguments(item, offset, ignoreSavedPosition)
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
    }
}
