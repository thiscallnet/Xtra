package com.github.andreyasadchy.xtra.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.navigation.fragment.findNavController
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.common.logVideoSurfaceBinding
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.clip.ClipEditorDialogFragment
import com.github.andreyasadchy.xtra.ui.player.clip.ClipEditorRestorationState
import com.github.andreyasadchy.xtra.ui.player.clip.ClipPreparationRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.PlayerControlLayout
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

@OptIn(UnstableApi::class)
class ExoPlayerFragment : PlayerFragment() {

    override val supportsLiveClipping = true
    override var playbackService: ExoPlayerService? = null
    private var serviceConnection: ServiceConnection? = null
    private var playerListener: Player.Listener? = null
    private var serviceSetupJob: Job? = null
    private val updateProgressAction = Runnable { if (view != null) updateProgress() }
    private var clipPreparationJob: Job? = null
    private var clipPreparationSnackbar: Snackbar? = null
    private var livePlaybackBeforeClipEditor: Boolean? = null
    private var liveClipDirectoryPath: String? = null
    private var liveSurfaceRestoreListener: Player.Listener? = null
    private var liveSurfaceRestoreTimeout: Runnable? = null
    private var clipEditorCoverTimeout: Runnable? = null
    private var videoOutputCover: View? = null
    private val videoOutputOwner = VideoOutputOwner<Player, TextureView>(
        attachTarget = { currentPlayer, target -> currentPlayer.setVideoTextureView(target) },
        detachTarget = { currentPlayer, target -> currentPlayer.clearVideoTextureView(target) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        liveClipDirectoryPath = savedInstanceState?.getString(STATE_CLIP_DIRECTORY)
        livePlaybackBeforeClipEditor = savedInstanceState
            ?.takeIf { it.containsKey(STATE_CLIP_PLAYING) }
            ?.getBoolean(STATE_CLIP_PLAYING)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val outputCover = View(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.BLACK)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        videoOutputCover = outputCover
        // Keep the TextureView renderer in the moving player layout. The cover is a normal
        // view above it and remains visible until the player confirms a new decoded frame.
        binding.aspectRatioFrameLayout.addView(outputCover)
        binding.playerSurface.visibility = View.GONE
        binding.playerTextureView.visibility = View.VISIBLE
        childFragmentManager.setFragmentResultListener(
            ClipEditorDialogFragment.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, result ->
            closeClipEditor(result.getString(ClipEditorDialogFragment.RESULT_DIRECTORY))
        }
        childFragmentManager.setFragmentResultListener(
            ClipEditorDialogFragment.PREVIEW_READY_KEY,
            viewLifecycleOwner,
        ) { _, _ ->
            hideClipEditorTransitionCover()
        }
        if (childFragmentManager.findFragmentByTag(CLIP_EDITOR_TAG) is ClipEditorDialogFragment) {
            binding.clipEditorContainer.visibility = View.VISIBLE
            binding.clipEditorTransitionCover.visibility = View.VISIBLE
            scheduleClipEditorCoverFallback()
        }
    }

    override fun onStart() {
        super.onStart()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    clearPlayerError()
                }
                binding.bufferingIndicator.isVisible = playbackState == Player.STATE_BUFFERING
                val showPlayButton = Util.shouldShowPlayButton(playbackService?.player)
                binding.playerControls.playPause.contentDescription = getString(
                    if (showPlayButton) R.string.player_play else R.string.player_pause_action,
                )
                if (showPlayButton) {
                    binding.playerControls.playPause.setImageResource(R.drawable.baseline_play_arrow_black_48)
                    binding.playerControls.playPause.visibility = View.VISIBLE
                } else {
                    binding.playerControls.playPause.setImageResource(R.drawable.baseline_pause_black_48)
                    if (playbackService?.type == BasePlaybackService.STREAM && !requireContext().prefs().getBoolean(C.PLAYER_PAUSE, false)) {
                        binding.playerControls.playPause.visibility = View.GONE
                    }
                }
                setPipActions(!showPlayButton)
                updateProgress()
                controllerAutoHide = !showPlayButton
                if (useController) {
                    showController(show = playbackService?.type != BasePlaybackService.STREAM && playbackState == Player.STATE_ENDED)
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                binding.bufferingIndicator.isVisible = playbackService?.player?.playbackState == Player.STATE_BUFFERING
                val showPlayButton = Util.shouldShowPlayButton(playbackService?.player)
                binding.playerControls.playPause.contentDescription = getString(
                    if (showPlayButton) R.string.player_play else R.string.player_pause_action,
                )
                if (showPlayButton) {
                    binding.playerControls.playPause.setImageResource(R.drawable.baseline_play_arrow_black_48)
                    binding.playerControls.playPause.visibility = View.VISIBLE
                } else {
                    binding.playerControls.playPause.setImageResource(R.drawable.baseline_pause_black_48)
                    if (playbackService?.type == BasePlaybackService.STREAM && !requireContext().prefs().getBoolean(C.PLAYER_PAUSE, false)) {
                        binding.playerControls.playPause.visibility = View.GONE
                    }
                }
                setPipActions(!showPlayButton)
                updateProgress()
                controllerAutoHide = !showPlayButton
                if (useController) {
                    showController(show = playbackService?.type != BasePlaybackService.STREAM && playbackService?.player?.playbackState == Player.STATE_ENDED)
                }
            }

            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                val showPlayButton = Util.shouldShowPlayButton(playbackService?.player)
                binding.playerControls.playPause.contentDescription = getString(
                    if (showPlayButton) R.string.player_play else R.string.player_pause_action,
                )
                if (showPlayButton) {
                    binding.playerControls.playPause.setImageResource(R.drawable.baseline_play_arrow_black_48)
                    binding.playerControls.playPause.visibility = View.VISIBLE
                } else {
                    binding.playerControls.playPause.setImageResource(R.drawable.baseline_pause_black_48)
                    if (playbackService?.type == BasePlaybackService.STREAM && !requireContext().prefs().getBoolean(C.PLAYER_PAUSE, false)) {
                        binding.playerControls.playPause.visibility = View.GONE
                    }
                }
                val duration = playbackService?.player?.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0
                binding.playerControls.progressBar.setDuration(duration)
                binding.playerControls.duration.text = DateUtils.formatElapsedTime(duration / 1000)
                binding.playerControls.duration.contentDescription = getString(
                    R.string.player_duration,
                    binding.playerControls.duration.text,
                )
                updateProgress()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize != VideoSize.UNKNOWN && playbackService?.player?.let { it.playbackState != Player.STATE_IDLE } == true) {
                    val aspectRatio = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height
                    binding.aspectRatioFrameLayout.setAspectRatio(aspectRatio)
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                binding.subtitleView.setCues(cueGroup.cues)
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                val duration = playbackService?.player?.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0
                binding.playerControls.progressBar.setDuration(duration)
                binding.playerControls.duration.text = DateUtils.formatElapsedTime(duration / 1000)
                binding.playerControls.duration.contentDescription = getString(
                    R.string.player_duration,
                    binding.playerControls.duration.text,
                )
                updateProgress()
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    if (chatFragment?.context != null) { // TODO
                        chatFragment?.updatePosition(newPosition.positionMs)
                    }
                }
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                if (chatFragment?.context != null) { // TODO
                    chatFragment?.updateSpeed(playbackParameters.speed)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateProgress()
                if (canEnterPictureInPicture()) {
                    requireView().keepScreenOn = isPlaying
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                setSubtitlesButton()
                if (!tracks.isEmpty) {
                    chatFragment?.startReplayChatLoad()
                }
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                val duration = playbackService?.player?.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0
                binding.playerControls.progressBar.setDuration(duration)
                binding.playerControls.duration.text = DateUtils.formatElapsedTime(duration / 1000)
                binding.playerControls.duration.contentDescription = getString(
                    R.string.player_duration,
                    binding.playerControls.duration.text,
                )
                updateProgress()
            }

            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                if (isClipEditorVisible()) return
                if (parameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO)) {
                    setVideoOutputVisible(false)
                } else {
                    setVideoOutputVisible(true)
                }
            }

            override fun onRenderedFirstFrame() {
                logVideoSurfaceBinding("first_frame", playbackService?.player, binding.playerTextureView)
                hideVideoOutputCover()
            }
        }
        val serviceListener = object : ExoPlayerService.Listener {
            override fun started() {
                if (view != null) {
                    if (!started) {
                        if (isInitialized || !enableNetworkCheck) {
                            started = true
                            start()
                        }
                    } else {
                        chatFragment?.startReplayChatLoad()
                        if (playbackService?.restoreQuality == true) {
                            playbackService?.restoreQuality = false
                            changeQuality(playbackService?.previousQuality)
                        }
                    }
                }
            }

            override fun loaded() {
                if (view != null) {
                    with(binding.playerControls) {
                        quality.isEnabled = true
                        setQualityButtonColor(Color.WHITE)
                        download.isEnabled = true
                        download.setColorFilter(Color.WHITE)
                        audioOnly.isEnabled = true
                        audioOnly.setColorFilter(Color.WHITE)
                        setQualityText()
                    }
                }
            }

            override fun changePlayerMode() {
                if (view != null) {
                    this@ExoPlayerFragment.changePlayerMode()
                }
            }

            override fun updateQualityStatus() {
                if (view != null) {
                    setQualityText()
                }
            }

            override fun updateLiveClipStatus() {
                if (view != null) {
                    setLiveClipAvailability(playbackService?.liveClipStatus()?.available == true)
                }
            }

            override fun toast(resId: Int, duration: Int) {
                if (view != null) {
                    when (resId) {
                        R.string.player_error, R.string.proxy_error -> showPlayerError(resId) { restartPlayer() }
                        R.string.stream_ended, R.string.video_subscribers_only -> showPlayerError(resId)
                        else -> Snackbar.make(
                            binding.playerBackground,
                            resId,
                            if (duration == Toast.LENGTH_LONG) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT,
                        ).show()
                    }
                }
            }

            override fun updateVideoInfo() {
                if (view != null) {
                    with(binding.playerControls) {
                        val titleText = playbackService?.title
                        if (!titleText.isNullOrBlank() && requireContext().prefs().getBoolean(C.PLAYER_TITLE, true)) {
                            title.visibility = View.VISIBLE
                            title.text = titleText
                        }
                        val gameName = playbackService?.gameName
                        if (!gameName.isNullOrBlank() && requireContext().prefs().getBoolean(C.PLAYER_CATEGORY, true)) {
                            category.visibility = View.VISIBLE
                            category.text = gameName
                            category.contentDescription = getString(R.string.player_open_category, gameName)
                            category.setOnClickListener {
                                findNavController().navigate(GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                    gameId = playbackService?.gameId,
                                    gameSlug = playbackService?.gameSlug,
                                    gameName = gameName
                                ))
                                minimize()
                            }
                        }
                    }
                }
            }
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (view != null) {
                    val binder = service as ExoPlayerService.ServiceBinder
                    val connectedService = binder.getService()
                    val connectedServiceConnection = this
                    playbackService = connectedService
                    serviceSetupJob?.cancel()
                    serviceSetupJob = viewLifecycleOwner.lifecycleScope.launch {
                        connectedService.awaitInitialRestore()
                        if (view == null ||
                            !isAdded ||
                            !viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) ||
                            playbackService !== connectedService ||
                            serviceConnection !== connectedServiceConnection
                        ) return@launch

                        val editorRestored = restoreClipEditorIfNeeded()
                        connectedService.serviceListener = serviceListener
                        if (!editorRestored) {
                            val restored = connectedService.restoreVideoOutputIfNeeded {
                                val player = connectedService.player
                                if (player == null) {
                                    false
                                } else {
                                    showVideoOutputCover()
                                    setVideoOutputVisible(true)
                                    attachVideoOutput(player)
                                    true
                                }
                            }
                            if (!restored) {
                                showVideoOutputCover()
                                connectedService.player?.let { player ->
                                    if (!player.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO)) {
                                        setVideoOutputVisible(true)
                                        attachVideoOutput(player)
                                    }
                                }
                            }
                        } else {
                            pauseLiveClipPlayback()
                        }
                        connectedService.player?.addListener(listener)
                        playerListener = listener
                        val endTime = connectedService.setSleepTimer(-1)
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
                        connectedService.setStopServiceTimer(false)
                        if (!editorRestored) {
                            connectedService.resumePlaybackIfNeeded()
                        } else {
                            pauseLiveClipPlayback()
                        }
                        connectedService.player?.let { player ->
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
                                if (connectedService.type == BasePlaybackService.STREAM && !requireContext().prefs().getBoolean(C.PLAYER_PAUSE, false)) {
                                    binding.playerControls.playPause.visibility = View.GONE
                                }
                            }
                        }
                        if (connectedService.started) {
                            if (!started) {
                                if (isInitialized || !enableNetworkCheck) {
                                    started = true
                                    start()
                                }
                            } else {
                                chatFragment?.startReplayChatLoad()
                                if (connectedService.restoreQuality) {
                                    connectedService.restoreQuality = false
                                    changeQuality(connectedService.previousQuality)
                                }
                            }
                        }
                        connectedService.player?.let { player ->
                            setPipActions(player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE && player.playWhenReady)
                        }
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceSetupJob?.cancel()
                serviceSetupJob = null
                playbackService = null
            }
        }
        val intent = Intent(requireContext(), ExoPlayerService::class.java).apply {
            action = ExoPlayerService.INTENT_START
        }
        requireContext().startService(intent)
        requireContext().bindService(intent, connection, Context.BIND_AUTO_CREATE)
        serviceConnection = connection
    }

    override fun getCurrentPosition() = playbackService?.player?.currentPosition

    override fun getCurrentSpeed() = playbackService?.player?.playbackParameters?.speed

    override fun getCurrentVolume() = playbackService?.player?.volume

    override fun getTotalDuration() = (playbackService?.player?.currentManifest as? HlsManifest)?.mediaPlaylist?.durationUs?.div(1000)

    override fun playPause() {
        Util.handlePlayPauseButtonAction(playbackService?.player)
    }

    override fun rewind() {
        playbackService?.player?.seekBack()
    }

    override fun fastForward() {
        playbackService?.player?.seekForward()
    }

    override fun seek(position: Long) {
        playbackService?.player?.seekTo(position)
    }

    override fun seekToLivePosition() {
        playbackService?.player?.seekToDefaultPosition()
    }

    override fun requestLiveClipStatus() {
        setLiveClipAvailability(playbackService?.liveClipStatus()?.available == true)
    }

    override fun prepareLiveClip() {
        val service = playbackService ?: return
        if (clipPreparationJob?.isActive == true || childFragmentManager.findFragmentByTag(CLIP_EDITOR_TAG) != null) {
            return
        }
        clipDebug("editor open requested")
        binding.playerControls.clip.isEnabled = false
        clipPreparationSnackbar?.dismiss()
        clipPreparationSnackbar = Snackbar.make(
            binding.playerBackground,
            R.string.clip_editor_exporting,
            Snackbar.LENGTH_INDEFINITE,
        ).setAction(R.string.cancel) {
            service.cancelLiveClipPreparation()
            clipPreparationJob?.cancel()
        }.also { it.show() }
        clipPreparationJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val prepared = service.prepareLiveClip().await()
                clipPreparationSnackbar?.dismiss()
                clipPreparationSnackbar = null
                if (view == null || !isAdded) {
                    service.releaseLiveClip(prepared.directory.absolutePath)
                } else {
                    openClipEditor(prepared)
                }
            } catch (_: CancellationException) {
                clipPreparationSnackbar?.dismiss()
                clipPreparationSnackbar = null
            } catch (_: Throwable) {
                clipPreparationSnackbar?.dismiss()
                clipPreparationSnackbar = null
                if (view != null) {
                    Snackbar.make(binding.playerBackground, R.string.player_clip_prepare_failed, Snackbar.LENGTH_LONG).show()
                }
            } finally {
                clipPreparationJob = null
                requestLiveClipStatus()
            }
        }
    }

    private fun openClipEditor(prepared: ClipPreparationRepository.PreparedLiveClip) {
        if (childFragmentManager.findFragmentByTag(CLIP_EDITOR_TAG) != null) {
            playbackService?.releaseLiveClip(prepared.directory.absolutePath)
            return
        }
        if (!canOpenClipEditor()) {
            playbackService?.releaseLiveClip(prepared.directory.absolutePath)
            return
        }
        livePlaybackBeforeClipEditor = playbackService?.player?.playWhenReady == true
        liveClipDirectoryPath = prepared.directory.absolutePath
        clipDebug("editor entry cover visible playing=$livePlaybackBeforeClipEditor")
        binding.clipEditorTransitionCover.visibility = View.VISIBLE
        binding.clipEditorContainer.visibility = View.VISIBLE
        scheduleClipEditorCoverFallback()
        pauseLiveClipPlayback()
        try {
            childFragmentManager.beginTransaction()
                .replace(
                    R.id.clipEditorContainer,
                    ClipEditorDialogFragment.newInstance(
                        playlistPath = prepared.playlist.absolutePath,
                        directoryPath = prepared.directory.absolutePath,
                        boundariesUs = prepared.boundariesUs,
                        channelName = playbackService?.channelName,
                    ),
                    CLIP_EDITOR_TAG,
                )
                .commitNow()
        } catch (_: IllegalStateException) {
            playbackService?.releaseLiveClip(prepared.directory.absolutePath)
            liveClipDirectoryPath = null
            binding.clipEditorContainer.visibility = View.GONE
            clipEditorCoverTimeout?.let(binding.root::removeCallbacks)
            clipEditorCoverTimeout = null
            restoreLiveClipPlayback()
        }
    }

    private fun canOpenClipEditor(): Boolean =
        view != null &&
            isAdded &&
            viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            !childFragmentManager.isStateSaved

    private fun pauseLiveClipPlayback() {
        val livePlayer = playbackService?.player
        livePlayer?.playWhenReady = false
        livePlayer?.pause()
        clipDebug("live player paused")
        setVideoOutputVisible(false)
        clipDebug("live surface hidden parentVisible=${binding.playerTextureView.visibility == View.VISIBLE}")
        detachVideoOutput(livePlayer)
        clipDebug("live surface cleared parentVisible=${binding.playerTextureView.visibility == View.VISIBLE}")
        binding.playerLayout.visibility = View.GONE
    }

    private fun isClipEditorVisible(): Boolean = view != null && binding.clipEditorContainer.isVisible

    private fun closeClipEditor(directoryPath: String?) {
        if (binding.clipEditorContainer.visibility != View.VISIBLE && liveClipDirectoryPath == null) return
        clipDebug("editor close requested")
        binding.clipEditorTransitionCover.visibility = View.VISIBLE
        binding.clipEditorContainer.visibility = View.GONE
        clipEditorCoverTimeout?.let(binding.root::removeCallbacks)
        clipEditorCoverTimeout = null
        val directoryToRelease = directoryPath ?: liveClipDirectoryPath
        binding.root.post {
            playbackService?.releaseLiveClip(directoryToRelease)
        }
        liveClipDirectoryPath = null
        restoreLiveClipPlayback()
    }

    private fun restoreClipEditorIfNeeded(): Boolean {
        val editor = childFragmentManager.findFragmentByTag(CLIP_EDITOR_TAG) as? ClipEditorDialogFragment
        val restoration = ClipEditorRestorationState(
            savedDirectoryPath = liveClipDirectoryPath,
            childDirectoryPath = editor?.preparedDirectoryPath,
        )
        restoration.staleParentDirectoryPath?.let { playbackService?.releaseLiveClip(it) }
        val directoryPath = restoration.directoryPath
        val validEditor = playbackService?.type == BasePlaybackService.STREAM &&
            restoration.shouldRestoreEditor &&
            directoryPath != null &&
            File(directoryPath).isDirectory &&
            File(directoryPath, "clip.json").isFile
        if (!validEditor) {
            restoration.orphanDirectoryPath?.let { playbackService?.releaseLiveClip(it) }
            if (editor != null) {
                childFragmentManager.beginTransaction()
                    .remove(editor)
                    .commitNowAllowingStateLoss()
            }
            liveClipDirectoryPath = null
            livePlaybackBeforeClipEditor = null
            binding.clipEditorContainer.visibility = View.GONE
            binding.clipEditorTransitionCover.visibility = View.GONE
            clipEditorCoverTimeout?.let(binding.root::removeCallbacks)
            clipEditorCoverTimeout = null
            return false
        }
        liveClipDirectoryPath = directoryPath
        binding.clipEditorContainer.visibility = View.VISIBLE
        binding.clipEditorTransitionCover.visibility = View.VISIBLE
        scheduleClipEditorCoverFallback()
        return true
    }

    private fun scheduleClipEditorCoverFallback() {
        clipEditorCoverTimeout?.let(binding.root::removeCallbacks)
        val timeout = Runnable {
            if (binding.clipEditorContainer.visibility == View.VISIBLE) {
                binding.clipEditorTransitionCover.visibility = View.GONE
            }
        }
        clipEditorCoverTimeout = timeout
        binding.root.postDelayed(timeout, CLIP_EDITOR_COVER_TIMEOUT_MS)
    }

    private fun hideClipEditorTransitionCover() {
        if (binding.clipEditorContainer.visibility == View.VISIBLE) {
            clipEditorCoverTimeout?.let(binding.root::removeCallbacks)
            clipEditorCoverTimeout = null
            binding.clipEditorTransitionCover.visibility = View.GONE
            clipDebug("editor preview first frame cover hidden")
        }
    }

    private fun restoreLiveClipPlayback() {
        val livePlayer = playbackService?.player
        if (livePlayer == null) {
            livePlaybackBeforeClipEditor = null
            binding.clipEditorTransitionCover.visibility = View.GONE
            return
        }
        liveSurfaceRestoreTimeout?.let(binding.root::removeCallbacks)
        liveSurfaceRestoreListener?.let(livePlayer::removeListener)
        val resumePlayback = livePlaybackBeforeClipEditor == true
        val firstFrameListener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                logVideoSurfaceBinding("first_frame", livePlayer, binding.playerTextureView)
                finishLiveSurfaceRestore(livePlayer)
            }
        }
        liveSurfaceRestoreListener = firstFrameListener
        livePlayer.addListener(firstFrameListener)
        binding.playerLayout.visibility = View.VISIBLE
        showVideoOutputCover()
        setVideoOutputVisible(true)
        clipDebug("live surface reattach")
        attachVideoOutput(livePlayer)
        if (resumePlayback) {
            livePlayer.seekToDefaultPosition()
            livePlayer.playWhenReady = true
        } else {
            livePlayer.playWhenReady = false
        }
        val timeout = Runnable { finishLiveSurfaceRestore(livePlayer) }
        liveSurfaceRestoreTimeout = timeout
        binding.root.postDelayed(timeout, LIVE_SURFACE_RESTORE_TIMEOUT_MS)
    }

    private fun finishLiveSurfaceRestore(livePlayer: Player) {
        liveSurfaceRestoreTimeout?.let(binding.root::removeCallbacks)
        liveSurfaceRestoreTimeout = null
        liveSurfaceRestoreListener?.let(livePlayer::removeListener)
        liveSurfaceRestoreListener = null
        livePlaybackBeforeClipEditor = null
        binding.clipEditorTransitionCover.visibility = View.GONE
        clipDebug("live first frame/timeout cover hidden")
    }

    override fun setPlaybackSpeed(speed: Float) {
        playbackService?.player?.setPlaybackSpeed(speed)
    }

    override fun changeVolume(volume: Float) {
        playbackService?.player?.volume = volume
    }

    override fun updateProgress() {
        with(binding.playerControls) {
            if (root.isVisible && !progressBar.isPressed) {
                val currentPosition = playbackService?.player?.currentPosition ?: 0
                position.text = DateUtils.formatElapsedTime(currentPosition / 1000)
                position.contentDescription = getString(R.string.player_position, position.text)
                progressBar.setPosition(currentPosition)
                progressBar.setBufferedPosition(playbackService?.player?.bufferedPosition ?: 0)
                root.removeCallbacks(updateProgressAction)
                playbackService?.player?.let { player ->
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

    override fun restartPlayer() {
        playbackService?.restartPlayer()
    }

    override fun toggleAudioCompressor() {
        val enabled = playbackService?.toggleDynamicsProcessing()
        if (enabled == true) {
            binding.playerControls.audioCompressor.setImageResource(R.drawable.baseline_audio_compressor_on_24dp)
        } else {
            binding.playerControls.audioCompressor.setImageResource(R.drawable.baseline_audio_compressor_off_24dp)
        }
    }

    override fun setSubtitlesButton() {
        with(binding.playerControls) {
            val textTracks = playbackService?.player?.currentTracks?.groups?.find { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
            if (textTracks != null) {
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
        playbackService?.toggleSubtitles(enabled)
    }

    override fun showPlaylistTags(mediaPlaylist: Boolean) {
        val tags = if (mediaPlaylist) {
            (playbackService?.player?.currentManifest as? HlsManifest)?.mediaPlaylist?.tags?.toTypedArray()
        } else {
            (playbackService?.player?.currentManifest as? HlsManifest)?.multivariantPlaylist?.tags?.toTypedArray()
        }?.joinToString("\n")
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

    override fun changeQuality(selectedQuality: VideoQuality?) {
        playbackService?.changeQuality(selectedQuality)
    }

    override fun startAudioOnly() {
        if (playbackService != null) {
            playbackService?.startAudioOnly()
            playbackService?.setSleepTimer((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
            playbackService?.setStopServiceTimer(true)
        }
        playerListener?.let { playbackService?.player?.removeListener(it) }
        playerListener = null
        playbackService?.serviceListener = null
        serviceConnection?.let { requireContext().unbindService(it) }
        serviceConnection = null
        playbackService = null
    }

    override fun close(deleteStates: Boolean) {
        detachVideoOutput()
        playbackService?.cancelLiveClipPreparation()
        liveClipDirectoryPath?.let { playbackService?.releaseLiveClip(it) }
        liveClipDirectoryPath = null
        playbackService?.player?.pause()
        playbackService?.player?.stop()
        if (deleteStates) {
            viewModel.deletePlaybackStates()
        }
        playerListener?.let { playbackService?.player?.removeListener(it) }
        playerListener = null
        playbackService?.serviceListener = null
        serviceConnection?.let { requireContext().unbindService(it) }
        serviceConnection = null
        playbackService?.stopSelf()
        playbackService = null
    }

    override fun retry(item: String) {
        playbackService?.retry(item)
    }

    override fun onStop() {
        serviceSetupJob?.cancel()
        serviceSetupJob = null
        super.onStop()
        if (playbackService != null) {
            val isInPIPMode = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> requireActivity().isInPictureInPictureMode
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> !useController && isMaximized
                else -> false
            }
            if (view != null && binding.clipEditorContainer.isVisible) {
                playbackService?.player?.playWhenReady = false
                playbackService?.player?.pause()
            } else {
                val videoDetachedForBackground = playbackService?.stop(isInPIPMode) == true
                if (view != null && !isInPIPMode) {
                    detachVideoOutput(playbackService?.player)
                    if (videoDetachedForBackground) {
                        setVideoOutputVisible(false)
                    }
                }
            }
            playbackService?.setSleepTimer((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
            playbackService?.setStopServiceTimer(true)
        }
        binding.playerControls.root.removeCallbacks(updateProgressAction)
        playerListener?.let { playbackService?.player?.removeListener(it) }
        playerListener = null
        playbackService?.serviceListener = null
        serviceConnection?.let { requireContext().unbindService(it) }
        serviceConnection = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_CLIP_DIRECTORY, liveClipDirectoryPath)
        livePlaybackBeforeClipEditor?.let { outState.putBoolean(STATE_CLIP_PLAYING, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        detachVideoOutput()
        clipDebug("parent editor view destroyed")
        serviceSetupJob?.cancel()
        serviceSetupJob = null
        clipPreparationSnackbar?.dismiss()
        clipPreparationSnackbar = null
        clipPreparationJob?.cancel()
        clipPreparationJob = null
        clipEditorCoverTimeout?.let { binding.root.removeCallbacks(it) }
        clipEditorCoverTimeout = null
        liveSurfaceRestoreTimeout?.let { binding.root.removeCallbacks(it) }
        liveSurfaceRestoreTimeout = null
        liveSurfaceRestoreListener?.let { listener -> playbackService?.player?.removeListener(listener) }
        liveSurfaceRestoreListener = null
        super.onDestroyView()
    }

    private fun attachVideoOutput(currentPlayer: Player) {
        videoOutputOwner.attach(currentPlayer, binding.playerTextureView)
        logVideoSurfaceBinding("attach", currentPlayer, binding.playerTextureView)
    }

    private fun detachVideoOutput(currentPlayer: Player? = videoOutputOwner.attachedPlayer()) {
        if (currentPlayer == null) return
        if (videoOutputOwner.attachedPlayer() === currentPlayer) {
            logVideoSurfaceBinding("detach", currentPlayer, binding.playerTextureView)
            videoOutputOwner.clear()
        }
    }

    private fun setVideoOutputVisible(visible: Boolean) {
        binding.playerTextureView.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            showVideoOutputCover()
        }
    }

    private fun showVideoOutputCover() {
        videoOutputCover?.visibility = View.VISIBLE
    }

    private fun hideVideoOutputCover() {
        videoOutputCover?.visibility = View.GONE
    }
    override fun onDestroy() {
        playbackService?.cancelLiveClipPreparation()
        super.onDestroy()
        playbackService = null
    }

    override fun onNetworkRestored() {
        if (isResumed) {
            if (playbackService?.type == BasePlaybackService.STREAM) {
                if (playbackService?.player?.playWhenReady == true) {
                    restartPlayer()
                }
            } else {
                playbackService?.player?.prepare()
            }
        }
    }

    override fun onNetworkLost() {
        // Keep the timeline alive so ExoPlayer can buffer and retry after a transient loss.
    }

    companion object {
        private const val CLIP_EDITOR_TAG = "liveClipEditor"
        private const val STATE_CLIP_DIRECTORY = "liveClipDirectory"
        private const val STATE_CLIP_PLAYING = "liveClipPlaying"
        private const val LIVE_SURFACE_RESTORE_TIMEOUT_MS = 4_000L
        private const val CLIP_EDITOR_COVER_TIMEOUT_MS = 5_000L
        private const val CLIP_LOG_TAG = "XtraClipPlayer"
    }

    private fun clipDebug(message: String) {
        if (BuildConfig.DEBUG && Log.isLoggable(CLIP_LOG_TAG, Log.DEBUG)) {
            Log.d(CLIP_LOG_TAG, "[CLIP-UI] $message")
        }
    }
}
