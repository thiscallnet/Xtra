package com.github.andreyasadchy.xtra.ui.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.trackPipAnimationHintView
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.media3.common.C as Media3C
import androidx.media3.common.Player
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.TimeBar
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.FragmentPlayerBinding
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.RadioButtonDialogFragment
import com.github.andreyasadchy.xtra.ui.common.formatStreamUptime
import com.github.andreyasadchy.xtra.ui.common.parseStreamStartedAtMs
import com.github.andreyasadchy.xtra.ui.download.DownloadDialog
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.captions.MoonshineModelState
import com.github.andreyasadchy.xtra.ui.tv.TvFocusHelper
import com.github.andreyasadchy.xtra.ui.tv.applyTvChatPresentation
import com.github.andreyasadchy.xtra.ui.tv.tvChatMode
import com.github.andreyasadchy.xtra.ui.tv.TvPlayerCommand
import com.github.andreyasadchy.xtra.ui.tv.TvRemoteKeyHandler
import com.github.andreyasadchy.xtra.ui.tv.tvPlayerCommand
import com.github.andreyasadchy.xtra.util.isTelevision
import com.github.andreyasadchy.xtra.ui.player.PlayerViewModel.Companion.PlayerViewModelFactory
import com.github.andreyasadchy.xtra.ui.settings.EXTRA_SETTINGS_SCREEN
import com.github.andreyasadchy.xtra.ui.settings.EXTRA_SETTINGS_HIGHLIGHT_PREFERENCE
import com.github.andreyasadchy.xtra.ui.settings.SETTINGS_SCREEN_LIVE_CAPTIONS
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.isKeyboardShown
import com.github.andreyasadchy.xtra.util.isChatEnabled
import com.github.andreyasadchy.xtra.ui.player.hud.HudElementId
import com.github.andreyasadchy.xtra.ui.player.hud.HudOrientation
import com.github.andreyasadchy.xtra.ui.player.hud.PlayerHudVisibilityController
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@OptIn(UnstableApi::class)
abstract class PlayerFragment : BaseNetworkFragment(), RadioButtonDialogFragment.OnSortOptionChanged, TvRemoteKeyHandler {

    private var _binding: FragmentPlayerBinding? = null
    protected val binding get() = _binding!!
    protected val viewModel: PlayerViewModel by viewModels { PlayerViewModelFactory }
    protected var chatFragment: ChatFragment? = null
    protected open val playbackService: BasePlaybackService? = null
    protected open fun liveBufferHealthPlayer(): Player? = null
    protected val xtraModule
        get() = (requireContext().applicationContext as XtraApp).xtraModule
    protected open val supportsLiveCaptions: Boolean = false
    protected var started = false

    private var nativeSubtitleCues: List<Cue> = emptyList()
    private val liveBufferHealthTrend = LiveBufferHealthTrend()
    private var hasEstablishedLiveBufferHealth = false
    private var lastLiveBufferHealthOffsetMs: Long? = null

    private var isPortrait = false
    protected var isMaximized = true
    private var isChatOpen = true
    private var isKeyboardShown = false
    private var keyboardLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var resizeMode = 0
    private var chatWidthLandscape = 0
    private var phoneChatOverlayGesture: PhoneChatOverlayGestureController? = null

    private var activePointerId = -1
    private var lastX = 0f
    private var lastY = 0f
    private var velocityTracker: VelocityTracker? = null
    private var isTap = false
    private var tapEventTime = 0L
    private var startTranslationX = 0f
    private var startTranslationY = 0f
    private var statusBarSwipe = false
    private var chatStatusBarSwipe = false
    private var chatTouchActive = false
    private var isAnimating = false
    private var moveAnimation: ViewPropertyAnimator? = null
    protected var useController = true
    private val hudVisibility = PlayerHudVisibilityController(
        rootProvider = { _binding?.playerControls?.root },
        televisionProvider = { _binding?.root?.context?.isTelevision() == true },
    )
    protected var controllerAutoHide: Boolean
        get() = hudVisibility.autoHideEnabled
        set(value) { hudVisibility.autoHideEnabled = value }
    private var controllerHideOnTouch: Boolean
        get() = hudVisibility.hideOnTouch
        set(value) { hudVisibility.hideOnTouch = value }
    private var isInteractionLocked = false
    private var interactionLockBackCallback: OnBackPressedCallback? = null
    private val controllerHideAction = hudVisibility.hideRunnable()
    private val controllerIsAnimating: Boolean get() = hudVisibility.isAnimating
    private var backgroundColor: Int? = null
    private var backgroundVisible = false
    private var pipPlaying = false
    private var loadedChannelAvatarUrl: String? = null
    private var liveRewindVod: LiveRewindVod? = null
    private var liveRewindStreamId: String? = null
    private var liveRewindStreamCreatedAt: String? = null
    private var livePlaybackMode: LivePlaybackMode = LivePlaybackMode.Live
    private var liveRewindScrubPositionMs: Long? = null
    private var liveRewindDiscoveryJob: Job? = null
    private var liveRewindTickerJob: Job? = null
    private var streamUptimeTickerJob: Job? = null
    private var liveRewindSwitchJob: Job? = null
    private var liveCaptionModelVerificationJob: Job? = null
    private var liveRewindSessionGeneration = 0L
    private var liveRewindSwitchGeneration = 0L
    private var liveRewindFrozenEdgeMs: Long? = null
    private var pausedLivePositionMs: Long? = null
    private var liveRewindStreamWasLive = false
    private var streamUptimeWasLive = false
    private var liveRewindStreamOffline = false
    private var pendingLiveSession: LiveRewindSession? = null
    private var liveRewindSwitching = false
    private var liveRewindReturningLive = false
    private var liveRewindPendingVodId: String? = null
    private var liveRewindPendingTargetMs: Long? = null
    private val liveTapSeekAccumulator = LiveTapSeekAccumulator()
    private val liveTapSeekCommitAction = Runnable { commitLiveTapSeek() }
    private val liveTapSeekFeedbackHideAction = Runnable { hideLiveTapSeekFeedback() }
    private val liveTapSeekDoubleTapState = LiveTapSeekDoubleTapState()
    private var lastTvFocusedControl: View? = null
    private var pendingTvFocusRequest: Runnable? = null

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (requireContext().isTelevision()) {
                if (binding.playerControls.root.isVisible) {
                    hideController(force = true)
                    binding.dragView.requestFocus()
                } else {
                    (activity as? MainActivity)?.closePlayer() ?: close()
                }
            } else minimize()
        }
    }

    override fun handleTvKeyEvent(event: android.view.KeyEvent): Boolean {
        if (!requireContext().isTelevision()) return false
        if (binding.playerControls.root.isVisible) {
            val isTvFocusRecoveryKey = event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ||
                event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN ||
                event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                event.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            if (isTvFocusRecoveryKey && !hasTvFocusInside(binding.playerControls.root)) {
                val preferred = lastTvFocusedControl?.let(::activeTvControl)
                    ?: activeTvControl(binding.playerControls.playPause)
                if (preferred == null) {
                    if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        binding.dragView.requestFocus()
                    }
                    return true
                }
                if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    preferred.requestFocus()
                    lastTvFocusedControl = preferred
                }
                if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                    event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ||
                    event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP ||
                    event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                ) {
                    configureTvPlayerActionFocus()
                    if (routeTvDirectionalKey(event, preferred)) return true
                }
                return true
            }
            configureTvPlayerActionFocus()
            if (routeTvDirectionalKey(event)) return true
        }
        if (binding.playerControls.root.isVisible && binding.dragView.hasFocus()) {
            val promotesPrimary = event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                event.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER ||
                event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
            if (promotesPrimary) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    activeTvControl(binding.playerControls.playPause)?.requestFocus()
                        ?: binding.dragView.requestFocus()
                }
                return true
            }
        }
        val command = tvPlayerCommand(event.keyCode, binding.playerControls.root.isVisible) ?: return false
        if (event.action != android.view.KeyEvent.ACTION_DOWN) return true
        if (event.repeatCount > 0) return true
        when (command) {
            TvPlayerCommand.SeekBack -> { rewind(); showController(force = true); requestTvControlFocus(binding.playerControls.rewind) }
            TvPlayerCommand.SeekForward -> { fastForward(); showController(force = true); requestTvControlFocus(binding.playerControls.fastForward) }
            TvPlayerCommand.ShowControls -> { showController(force = true); requestTvControlFocus(binding.playerControls.playPause) }
        }
        return true
    }

    private fun activeTvControl(control: View): View? = control.takeIf {
        it.isAttachedToWindow && it.isShown && it.isEnabled && binding.playerControls.root.isElementActive(it)
    }

    private fun requestTvControlFocus(control: View) {
        lastTvFocusedControl = control
        // The controller fades in asynchronously. Requesting focus in the same
        // frame as showController() can be rejected while its root is still GONE.
        val root = binding.playerControls.root
        pendingTvFocusRequest?.let(root::removeCallbacks)
        val request = object : Runnable {
            override fun run() {
                if (pendingTvFocusRequest !== this) return
                pendingTvFocusRequest = null
                if (!isAdded || _binding == null || !root.isVisible) return
                // A newer remote navigation decision wins over this delayed
                // controller-visibility workaround.
                if (lastTvFocusedControl !== control) return
                if (root.findFocus() != null && root.findFocus() !== control) return
                if (control.isShown && control.isEnabled && binding.playerControls.root.isElementActive(control)) {
                    configureTvPlayerActionFocus()
                    control.requestFocus()
                }
            }
        }
        pendingTvFocusRequest = request
        root.postDelayed(request, 300L)
    }

    private fun hasTvFocusInside(root: ViewGroup): Boolean {
        var current = requireActivity().currentFocus
        while (current != null) {
            if (current === root) return true
            if (current.parent !is View) return false
            current = current.parent as View
        }
        return false
    }

    private fun routeTvDirectionalKey(event: android.view.KeyEvent, focusOverride: View? = null): Boolean {
        val visible: (View) -> Boolean = { view ->
            if (view.visibility == View.VISIBLE && view.isEnabled && binding.playerControls.root.isElementActive(view)) {
                TvFocusHelper.install(view)
                view.isFocusable = true
                view.isFocusableInTouchMode = false
                true
            } else {
                false
            }
        }
        val liveTimeGroup = binding.playerControls.liveTimeGroup.takeIf {
            it.isClickable && it.isFocusable && it.visibility == View.VISIBLE
        }
        val candidates = (listOf(
            binding.playerControls.rewind,
            binding.playerControls.playPause,
            binding.playerControls.fastForward,
            binding.playerControls.download,
            binding.playerControls.follow,
            binding.playerControls.sleepTimer,
            binding.playerControls.aspectRatio,
            binding.playerControls.speed,
            binding.playerControls.quality,
            binding.playerControls.menu,
            binding.playerControls.restart,
            binding.playerControls.seekLive,
            binding.playerControls.clip,
            binding.playerControls.vodGames,
            binding.playerControls.volume,
            binding.playerControls.audioCompressor,
            binding.playerControls.audioOnly,
            binding.playerControls.liveCaptions,
            binding.playerControls.subtitles,
            binding.playerControls.toggleChatInput,
            binding.playerControls.toggleChat,
            binding.playerControls.fullscreen,
        ) + listOfNotNull(liveTimeGroup)).filter(visible)
        if (candidates.isEmpty()) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) binding.dragView.requestFocus()
            return true
        }
        return TvFocusHelper.routeDirectionalFocus(
            event,
            binding.playerControls.root,
            candidates,
            focusedOverride = focusOverride ?: requireActivity().currentFocus,
            fallback = lastTvFocusedControl?.let(::activeTvControl)
                ?: candidates.firstOrNull(),
            onMoved = { lastTvFocusedControl = it },
        )
    }

    open fun getCurrentPosition(): Long? = null
    open fun getCurrentSpeed(): Float? = null
    open fun getCurrentVolume(): Float? = null
    open fun getTotalDuration(): Long? = null
    protected open fun isPlaybackRequested(): Boolean = true
    open fun playPause() {}
    open fun rewind() {}
    open fun fastForward() {}
    open fun seek(position: Long) {}
    open fun seekToLivePosition() {}
    open suspend fun startLiveRewind(vodId: String, positionMs: Long): Boolean = false
    open suspend fun returnToLivePlayback(): Boolean = false
    protected open suspend fun getLiveRewindVodId(): String? =
        playbackService?.takeIf { it.liveRewindActive }?.liveRewindVodId
    protected open fun startLiveRewindChat(positionMs: Long) {
        enterLiveRewindChat(positionMs)
    }

    private fun liveRewindSourceState(): LiveRewindSourceState = LiveRewindSourceState(
        mode = livePlaybackMode,
        vod = liveRewindVod,
        offline = liveRewindStreamOffline,
        frozenEdgeMs = liveRewindFrozenEdgeMs,
        committedSession = LiveRewindSession(liveRewindStreamId, liveRewindStreamCreatedAt),
        pendingSession = pendingLiveSession,
    )

    private fun commitPendingLiveSession(): LiveRewindSession? {
        val result = liveRewindSourceState().completeLiveTransition()
        val session = result.state.committedSession ?: return null
        pendingLiveSession = null
        if (result.shouldDiscoverRecordingVod) {
            liveRewindStreamId = session.id
            liveRewindStreamCreatedAt = session.createdAt
            liveRewindStreamOffline = false
            liveRewindFrozenEdgeMs = null
            liveRewindVod = null
        }
        livePlaybackMode = LivePlaybackMode.Live
        pausedLivePositionMs = null
        return session.takeIf { result.shouldDiscoverRecordingVod }
    }
    open fun setPlaybackSpeed(speed: Float) {}
    open fun changeVolume(volume: Float) {}
    open fun updateProgress() {}
    open fun restartPlayer() {}
    open fun toggleAudioCompressor() {}
    open fun setSubtitlesButton() {}
    open fun toggleSubtitles(enabled: Boolean) {}

    fun toggleLiveCaptions() {
        if (!supportsLiveCaptions || playbackService?.type != BasePlaybackService.STREAM) return
        val preferences = requireContext().prefs()
        if (preferences.getBoolean(C.PLAYER_LIVE_CAPTIONS, false)) {
            preferences.edit { putBoolean(C.PLAYER_LIVE_CAPTIONS, false) }
            xtraModule.liveCaptionManager.setEnabled(false)
            configureLiveCaptionsButton()
            return
        }

        val modelManager = xtraModule.moonshineModelManager
        when (modelManager.state.value) {
            MoonshineModelState.Ready -> enableLiveCaptions()
            MoonshineModelState.Checking,
            MoonshineModelState.Verifying,
            -> {
                if (liveCaptionModelVerificationJob?.isActive == true) return
                liveCaptionModelVerificationJob = viewLifecycleOwner.lifecycleScope.launch {
                    if (modelManager.awaitVerification() is MoonshineModelState.Ready) {
                        enableLiveCaptions()
                    } else {
                        openLiveCaptionSettings()
                    }
                }
            }
            MoonshineModelState.NotInstalled,
            is MoonshineModelState.Downloading,
            is MoonshineModelState.Error,
            -> openLiveCaptionSettings()
        }
    }

    private fun enableLiveCaptions() {
        if (!isAdded || !supportsLiveCaptions || playbackService?.type != BasePlaybackService.STREAM) return
        val preferences = requireContext().prefs()
        if (preferences.getBoolean(C.PLAYER_LIVE_CAPTIONS, false)) return
        preferences.edit { putBoolean(C.PLAYER_LIVE_CAPTIONS, true) }
        xtraModule.liveCaptionManager.setEnabled(true)
        configureLiveCaptionsButton()
    }

    protected fun configureLiveCaptionsButton() {
        if (_binding == null) return
        val button = binding.playerControls.liveCaptions
        if (!supportsLiveCaptions || playbackService?.type != BasePlaybackService.STREAM) {
            button.visibility = View.GONE
            button.setOnClickListener(null)
            button.setOnLongClickListener(null)
            return
        }
        val enabled = requireContext().prefs().getBoolean(C.PLAYER_LIVE_CAPTIONS, false)
        button.visibility = View.VISIBLE
        button.setImageResource(
            if (enabled) androidx.media3.ui.R.drawable.exo_ic_subtitle_on
            else androidx.media3.ui.R.drawable.exo_ic_subtitle_off,
        )
        button.setOnClickListener {
            showController(force = true)
            toggleLiveCaptions()
        }
        button.setOnLongClickListener {
            showController(force = true)
            openLiveCaptionSettings()
            true
        }
        // The captions toggle is part of the same persisted control layout as
        // every other quick action, so a state refresh must preserve its chosen
        // perimeter anchor and visibility group.
        binding.playerControls.root.refreshAvailability()
    }

    fun isLiveCaptionsAvailable(): Boolean =
        supportsLiveCaptions && playbackService?.type == BasePlaybackService.STREAM

    fun openLiveCaptionSettings() {
        if (playbackService?.type != BasePlaybackService.STREAM) return
        openCaptionSettings()
    }

    fun openCaptionSettings() {
        val intent = Intent(requireContext(), SettingsActivity::class.java).apply {
            putExtra(EXTRA_SETTINGS_SCREEN, SETTINGS_SCREEN_LIVE_CAPTIONS)
            if (playbackService?.type == BasePlaybackService.STREAM) {
                putExtra(EXTRA_SETTINGS_HIGHLIGHT_PREFERENCE, C.PLAYER_LIVE_CAPTION_MODEL)
            }
        }
        (activity as? MainActivity)?.settingsResultLauncher?.launch(intent)
            ?: startActivity(intent)
    }

    private fun setupLiveCaptionOverlay() {
        if (!supportsLiveCaptions) return
        configureLiveCaptionsButton()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                xtraModule.liveCaptionManager.state.collectLatest { state ->
                    updateLiveCaption(
                        text = if (state.enabled) state.text else "",
                        lineShiftToken = state.lineShiftToken,
                    )
                }
            }
        }
    }

    protected fun setNativeSubtitleCues(cues: List<Cue>) {
        nativeSubtitleCues = cues
        renderSubtitleOverlay()
    }

    private fun renderSubtitleOverlay() {
        // Native subtitle tracks remain owned by SubtitleView. Live captions use a
        // fixed overlay view so partial updates never move the player or its black bar.
        binding.subtitleView.setCues(nativeSubtitleCues)
    }

    private fun updateLiveCaption(text: String, lineShiftToken: Long) {
        if (_binding == null) return
        binding.liveCaptionView.submitCaption(text, lineShiftToken)
    }
    open fun showPlaylistTags(mediaPlaylist: Boolean) {}
    open fun changeQuality(selectedQuality: VideoQuality?, persistSavedQuality: Boolean = true) {}
    open fun startAudioOnly() {}
    open val supportsLiveClipping: Boolean = false
    open fun prepareLiveClip() {}
    open fun requestLiveClipStatus() {}
    open fun close(deleteStates: Boolean = true) {}
    open fun retry(item: String) {}

    protected fun setLiveClipAvailability(available: Boolean) {
        if (_binding == null) return
        binding.playerControls.clip.isEnabled = available
    }

    /**
     * SurfaceView can become measurable one traversal after audio-only
     * playback restores the video output. Re-run the HUD layout after that
     * traversal so fixed chrome stays on the rendered video boundary.
     */
    protected fun refreshPlayerHudLayout() {
        val root = _binding?.playerControls?.root ?: return
        root.requestLayout()
        root.post {
            if (root.isAttachedToWindow) root.requestLayout()
        }
    }

    protected fun configureClipControl() {
        if (_binding == null) return
        val playbackType = playbackService?.type
        val clipAvailable = supportsLiveClipping &&
            (playbackType == BasePlaybackService.STREAM || playbackType == BasePlaybackService.VIDEO) &&
            !(playbackType == BasePlaybackService.STREAM &&
                (playbackService?.liveRewindActive == true ||
                    playbackService?.liveRewindTransitioning == true ||
                    liveRewindSwitching))
        with(binding.playerControls.clip) {
            if (clipAvailable) {
                visibility = View.VISIBLE
                isEnabled = false
                setOnClickListener {
                    showController(force = true)
                    prepareLiveClip()
                }
            } else {
                visibility = View.GONE
                isEnabled = false
                setOnClickListener(null)
            }
        }
    }

    protected fun refreshClipControl() {
        if (_binding == null) return
        configureClipControl()
        refreshPlayerControls()
        applyHudLayout()
    }

    private fun updateLiveClipSourceAvailability() {
        if (_binding == null || !supportsLiveClipping || playbackService?.type != BasePlaybackService.STREAM) {
            return
        }
        if (playbackService?.liveRewindActive == true ||
            playbackService?.liveRewindTransitioning == true ||
            liveRewindSwitching
        ) {
            binding.playerControls.clip.visibility = View.GONE
            binding.playerControls.clip.isEnabled = false
            binding.playerControls.clip.setOnClickListener(null)
        } else {
            binding.playerControls.clip.visibility = View.VISIBLE
            binding.playerControls.clip.isEnabled = false
            binding.playerControls.clip.setOnClickListener {
                showController(force = true)
                prepareLiveClip()
            }
            requestLiveClipStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (arguments?.getBoolean(KEY_OFFLINE) == true) {
            enableNetworkCheck = false
        }
        isInteractionLocked = savedInstanceState?.getBoolean(STATE_INTERACTION_LOCKED, false) ?: false
        super.onCreate(savedInstanceState)
        isPortrait = !requireContext().isTelevision() &&
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        hudVisibility.bindDialogLifecycle(childFragmentManager)
        requireActivity().onBackPressedDispatcher.addCallback(this, backPressedCallback)
        WindowCompat.getInsetsController(
            requireActivity().window,
            requireActivity().window.decorView
        ).systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroy() {
        hudVisibility.unbindDialogLifecycle()
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (requireContext().isTelevision()) {
            controllerAutoHide = false
            binding.dragView.isFocusable = true
            binding.dragView.isFocusableInTouchMode = false
            binding.playerControls.minimize.visibility = View.GONE
            binding.playerControls.interactionLock.visibility = View.GONE
            binding.playerControls.rewind.nextFocusRightId = binding.playerControls.playPause.id
            binding.playerControls.playPause.nextFocusLeftId = binding.playerControls.rewind.id
            binding.playerControls.playPause.nextFocusRightId = binding.playerControls.fastForward.id
            binding.playerControls.fastForward.nextFocusLeftId = binding.playerControls.playPause.id
            binding.playerControls.root.post { configureTvPlayerActionFocus() }
            lastTvFocusedControl = binding.playerControls.playPause
            binding.dragView.requestFocus()
        }
        with(binding) {
            hudEdgeMarkerTouchOverlay.bind(playerControls.root, playerControls.progressBar)
            chatLayout.boundaryTimeBar = playerControls.progressBar
            phoneChatOverlayGesture = PhoneChatOverlayGestureController(
                context = requireContext(),
                chat = chatLayout,
                parent = slidingLayout,
                dragHandle = phoneChatOverlayHandle,
                dispatchChatTouch = { event -> chatLinearLayout.dispatchTouchEvent(event) },
                onChatTouchActiveChanged = { active -> chatTouchActive = active },
                isInteractionLocked = { isInteractionLocked },
            )
            val ignoreCutouts = requireContext().prefs().getBoolean(C.UI_DRAW_BEHIND_CUTOUTS, false)
            val cornerPadding = requireContext().prefs().getBoolean(C.PLAYER_ROUNDED_CORNER_PADDING, false)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = if (!isPortrait && ignoreCutouts) {
                    windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
                } else {
                    windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
                }
                if (isPortrait) {
                    slidingLayout.updatePadding(left = 0, top = insets.top, right = 0)
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cornerPadding) {
                        val rootWindowInsets = view.rootView.rootWindowInsets
                        val topLeft = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
                        val topRight = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
                        val bottomLeft = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
                        val bottomRight = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
                        val leftRadius = max(topLeft?.radius ?: 0, bottomLeft?.radius ?: 0)
                        val rightRadius = max(topRight?.radius ?: 0, bottomRight?.radius ?: 0)
                        if (ignoreCutouts) {
                            slidingLayout.updatePadding(left = leftRadius, top = 0, right = rightRadius)
                        } else {
                            val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
                            slidingLayout.updatePadding(left = max(cutoutInsets.left, leftRadius), top = 0, right = max(cutoutInsets.right, rightRadius))
                        }
                    } else {
                        if (ignoreCutouts) {
                            slidingLayout.updatePadding(left = 0, top = 0, right = 0)
                        } else {
                            val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
                            slidingLayout.updatePadding(left = cutoutInsets.left, top = 0, right = cutoutInsets.right)
                        }
                    }
                }
                chatLayout.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                viewLifecycleOwner.lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        requireActivity().trackPipAnimationHintView(playerLayout)
                    }
                }
            }
            if (isMaximized) {
                enableBackground()
            } else {
                disableBackground()
            }
            isChatOpen = requireContext().prefs().getBoolean(C.KEY_CHAT_OPENED, true) &&
                requireContext().prefs().isChatEnabled() &&
                (!requireContext().isTelevision() || tvChatMode(requireContext()) != com.github.andreyasadchy.xtra.ui.tv.TvChatMode.HIDDEN)
            chatWidthLandscape = requireContext().prefs().getInt(C.LANDSCAPE_CHAT_WIDTH, 0)
            resizeMode = requireContext().prefs().getInt(C.ASPECT_RATIO_LANDSCAPE, AspectRatioFrameLayout.RESIZE_MODE_FIT)
            aspectRatioFrameLayout.setAspectRatio(16f / 9f)
            initLayout()
            changePlayerMode()
            setupLiveCaptionOverlay()
            val viewConfiguration = ViewConfiguration.get(requireContext())
            val touchSlop = viewConfiguration.scaledTouchSlop
            val touchSlopRange = -touchSlop.toFloat()..touchSlop.toFloat()
            val longPressTimeout = ViewConfiguration.getLongPressTimeout()
            val moveFreely = requireContext().prefs().getBoolean(C.PLAYER_MOVE_FREELY, false)
            val chatDoubleTapEnabled = requireContext().prefs().getBoolean(C.PLAYER_DOUBLE_TAP, true) &&
                requireContext().prefs().isChatEnabled()
            fun liveTapSeekGestureEnabled() = !requireContext().isTelevision() &&
                isLiveRewindAvailable() &&
                !liveRewindStreamOffline &&
                !liveRewindSwitching &&
                !liveRewindReturningLive
            fun doubleTapGestureEnabled() = liveTapSeekGestureEnabled() || (chatDoubleTapEnabled && !isPortrait)
            var controlTouchActive = false
            var lockedTouchActive = false
            var lockedTouchX = 0f
            var lockedTouchY = 0f
            val controllerTapDetector = GestureDetector(
                requireContext(),
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        return if (!doubleTapGestureEnabled()) {
                            toggleController()
                            true
                        } else {
                            false
                        }
                    }

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        return if (doubleTapGestureEnabled()) {
                            toggleController()
                            true
                        } else {
                            false
                        }
                    }

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        if (!doubleTapGestureEnabled() || !isMaximized) {
                            liveTapSeekDoubleTapState.clearCandidate()
                            return false
                        }
                        liveTapSeekDoubleTapState.recordFirstTap(liveTapSeekZoneForEvent(e), e.downTime)
                        return true
                    }

                    override fun onDoubleTapEvent(e: MotionEvent): Boolean {
                        if (!doubleTapGestureEnabled() || !isMaximized) {
                            liveTapSeekDoubleTapState.clearCandidate()
                            return false
                        }
                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                liveTapSeekDoubleTapState.recordSecondTapDown(
                                    liveTapSeekZoneForEvent(e),
                                    e.downTime,
                                )
                            }
                            MotionEvent.ACTION_UP -> {
                                if (!isTap || controlTouchActive || playerControls.progressBar.isPressed ||
                                    statusBarSwipe || slidingLayout.translationY !in touchSlopRange || activePointerId == -1
                                ) {
                                    liveTapSeekDoubleTapState.rejectSecondTap()
                                } else {
                                    when (liveTapSeekDoubleTapState.acceptSecondTap()) {
                                        LiveTapSeekZone.LEFT -> handleLiveTapSeekDoubleTap(LiveTapSeekDirection.BACKWARD)
                                        LiveTapSeekZone.RIGHT -> handleLiveTapSeekDoubleTap(LiveTapSeekDirection.FORWARD)
                                        LiveTapSeekZone.CENTER -> if (chatDoubleTapEnabled && !isPortrait) {
                                            if (chatLayout.isVisible) {
                                                hideChat()
                                            } else {
                                                showChat()
                                            }
                                        }
                                        null -> Unit
                                    }
                                }
                            }
                            MotionEvent.ACTION_CANCEL -> liveTapSeekDoubleTapState.clearCandidate()
                        }
                        return true
                    }
                }
            )

            fun downAction(event: MotionEvent) {
                moveAnimation?.cancel()
                isTap = true
                tapEventTime = event.eventTime
                if (isMaximized) {
                    if (playerControls.root.isVisible) {
                        controlTouchActive = playerControls.root.dispatchTouchEvent(event)
                        if (!controlTouchActive) {
                            controllerTapDetector.onTouchEvent(event)
                        } else {
                            liveTapSeekDoubleTapState.clearCandidate()
                        }
                    } else {
                        controllerTapDetector.onTouchEvent(event)
                    }
                } else {
                    velocityTracker?.clear()
                    if (velocityTracker == null) {
                        velocityTracker = VelocityTracker.obtain()
                    }
                    velocityTracker?.addMovement(
                        MotionEvent.obtain(
                            event.downTime,
                            event.eventTime,
                            event.action,
                            slidingLayout.translationX,
                            slidingLayout.translationY,
                            event.metaState
                        )
                    )
                    startTranslationX = slidingLayout.translationX
                    startTranslationY = slidingLayout.translationY
                }
            }

            fun upAction(event: MotionEvent) {
                if (isMaximized) {
                    if (controlTouchActive) {
                        playerControls.root.dispatchTouchEvent(event)
                        controlTouchActive = false
                        return
                    }
                    if (playerControls.progressBar.isPressed) {
                        playerControls.root.dispatchTouchEvent(event)
                    } else {
                        if (slidingLayout.translationY in touchSlopRange) {
                            if (playerControls.root.isVisible) {
                                playerControls.root.dispatchTouchEvent(event)
                                controllerTapDetector.onTouchEvent(event)
                            } else {
                                controllerTapDetector.onTouchEvent(event)
                            }
                        }
                        val minimizeThreshold = slidingLayout.height / 5
                        if (slidingLayout.translationY < minimizeThreshold) {
                            moveAnimation = slidingLayout.animate().apply {
                                translationX(0f)
                                translationY(0f)
                                setDuration(250L)
                                setListener(
                                    object : AnimatorListenerAdapter() {
                                        override fun onAnimationEnd(animation: Animator) {
                                            setListener(null)
                                            if (this@PlayerFragment.view != null && slidingLayout.translationY < touchSlop) {
                                                enableBackground()
                                            }
                                        }
                                    }
                                )
                                start()
                            }
                        } else {
                            minimize()
                        }
                    }
                } else {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val xVelocity = velocityTracker?.xVelocity ?: 0f
                    velocityTracker?.recycle()
                    velocityTracker = null
                    when {
                        xVelocity > 1500 -> {
                            isAnimating = true
                            slidingLayout.animate().apply {
                                translationX(slidingLayout.translationX + (slidingLayout.width * slidingLayout.scaleX))
                                setDuration(250L)
                                start()
                            }
                            (activity as? MainActivity)?.closePlayer() ?: close()
                        }
                        xVelocity < -1500 -> {
                            isAnimating = true
                            slidingLayout.animate().apply {
                                translationX(slidingLayout.translationX - (slidingLayout.width * slidingLayout.scaleX))
                                setDuration(250L)
                                start()
                            }
                            (activity as? MainActivity)?.closePlayer() ?: close()
                        }
                        else -> {
                            if (isTap && (event.eventTime - tapEventTime) < longPressTimeout) {
                                maximize()
                            } else {
                                if (moveFreely) {
                                    val windowInsets = ViewCompat.getRootWindowInsets(requireView())
                                    val insets = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                                    val scaledXDiff = (slidingLayout.width * (1f - slidingLayout.scaleX)) / 2
                                    val scaledYDiff = (slidingLayout.height * (1f - slidingLayout.scaleY)) / 2
                                    val minX = 0f - scaledXDiff - ((insets?.left ?: 0) * slidingLayout.scaleX) + (insets?.left ?: 0)
                                    val minY = 0f - scaledYDiff - ((insets?.top ?: 0) * slidingLayout.scaleY) + (insets?.top ?: 0)
                                    val maxX = 0f - scaledXDiff - ((insets?.left ?: 0) * slidingLayout.scaleX) + slidingLayout.width - (playerLayout.width * slidingLayout.scaleX) - (insets?.right ?: 0)
                                    val maxY = 0f - scaledYDiff - ((insets?.top ?: 0) * slidingLayout.scaleY) + slidingLayout.height - (playerLayout.height * slidingLayout.scaleY) - (insets?.bottom ?: 0)
                                    val newX = when {
                                        slidingLayout.translationX < minX -> minX
                                        slidingLayout.translationX > maxX -> maxX
                                        else -> null
                                    }
                                    val newY = when {
                                        slidingLayout.translationY < minY -> minY
                                        slidingLayout.translationY > maxY -> maxY
                                        else -> null
                                    }
                                    if (newX != null || newY != null) {
                                        moveAnimation = slidingLayout.animate().apply {
                                            newX?.let { translationX(it) }
                                            newY?.let { translationY(it) }
                                            setDuration(250L)
                                            setListener(
                                                object : AnimatorListenerAdapter() {
                                                    override fun onAnimationEnd(animation: Animator) {
                                                        setListener(null)
                                                    }
                                                }
                                            )
                                            start()
                                        }
                                    }
                                } else {
                                    val windowInsets = ViewCompat.getRootWindowInsets(requireView())
                                    val insets = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                                    val keyboardInsets = windowInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom?.let { if (it > 0) it - (insets?.bottom ?: 0) else it } ?: 0
                                    val scaledXDiff = (slidingLayout.width * (1f - slidingLayout.scaleX)) / 2
                                    val scaledYDiff = (slidingLayout.height * (1f - slidingLayout.scaleY)) / 2
                                    val navBarHeight = requireView().rootView.findViewById<LinearLayout>(R.id.navBarContainer)?.height?.takeIf { it > 0 }?.let { it - keyboardInsets } ?: (insets?.bottom ?: 0)
                                    val newX = slidingLayout.width - (insets?.right ?: 0) - (playerLayout.width * slidingLayout.scaleX) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20F, resources.displayMetrics) * slidingLayout.scaleX)
                                    val newY = slidingLayout.height - navBarHeight - (playerLayout.height * slidingLayout.scaleY) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 30F, resources.displayMetrics) * slidingLayout.scaleY)
                                    moveAnimation = slidingLayout.animate().apply {
                                        translationX(0f - scaledXDiff - ((insets?.left ?: 0) * slidingLayout.scaleX) + newX)
                                        translationY(0f - scaledYDiff - ((insets?.top ?: 0) * slidingLayout.scaleY) + newY)
                                        setDuration(250L)
                                        setListener(
                                            object : AnimatorListenerAdapter() {
                                                override fun onAnimationEnd(animation: Animator) {
                                                    setListener(null)
                                                }
                                            }
                                        )
                                        start()
                                    }
                                }
                            }
                        }
                    }
                }
            }

            dragView.setOnTouchListener { _, event ->
                if (!isAnimating) {
                    if (isInteractionLocked && !playerControls.root.isVisible) {
                        liveTapSeekDoubleTapState.clearCandidate()
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                lockedTouchActive = true
                                lockedTouchX = event.x
                                lockedTouchY = event.y
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (abs(event.x - lockedTouchX) > touchSlop ||
                                    abs(event.y - lockedTouchY) > touchSlop
                                ) {
                                    lockedTouchActive = false
                                }
                            }
                            MotionEvent.ACTION_POINTER_DOWN -> lockedTouchActive = false
                            MotionEvent.ACTION_UP -> {
                                if (lockedTouchActive) {
                                    showController()
                                    updateProgress()
                                }
                                lockedTouchActive = false
                            }
                            MotionEvent.ACTION_CANCEL -> lockedTouchActive = false
                        }
                        return@setOnTouchListener true
                    }

                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            activePointerId = event.getPointerId(0)
                            val x = event.x
                            val y = event.y
                            lastX = x * slidingLayout.scaleX
                            lastY = y * slidingLayout.scaleY
                            statusBarSwipe = !isPortrait && y <= 100
                            downAction(event)
                        }
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            liveTapSeekDoubleTapState.clearCandidate()
                            if (activePointerId == -1) {
                                val pointerIndex = event.actionIndex
                                val pointerId = event.getPointerId(pointerIndex)
                                val x = event.getX(pointerIndex)
                                val y = event.getY(pointerIndex)
                                if (x in 0f..playerLayout.width.toFloat() && y in 0f..playerLayout.height.toFloat()) {
                                    activePointerId = pointerId
                                    lastX = x * slidingLayout.scaleX
                                    lastY = y * slidingLayout.scaleY
                                    statusBarSwipe = !isPortrait && y <= 100
                                    downAction(event)
                                }
                            }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (isMaximized) {
                                playerControls.root.dispatchTouchEvent(event)
                                if (controlTouchActive || playerControls.progressBar.isPressed || statusBarSwipe) {
                                    liveTapSeekDoubleTapState.clearCandidate()
                                }
                                if (!controlTouchActive && !playerControls.progressBar.isPressed && !statusBarSwipe && activePointerId != -1) {
                                    val pointerIndex = event.findPointerIndex(activePointerId)
                                    if (pointerIndex != -1) {
                                        val y = event.getY(pointerIndex)
                                        val translationY = y - lastY
                                        if (slidingLayout.translationY + translationY < 0) {
                                            slidingLayout.translationY = 0f
                                            lastY = y
                                        } else {
                                            slidingLayout.translationY += translationY
                                            lastY = y - translationY
                                        }
                                        if (slidingLayout.translationY < touchSlop) {
                                            if (!backgroundVisible) {
                                                enableBackground()
                                            }
                                        } else {
                                            if (backgroundVisible) {
                                                disableBackground()
                                            }
                                            isTap = false
                                            liveTapSeekDoubleTapState.clearCandidate()
                                        }
                                    }
                                }
                            } else {
                                if (activePointerId != -1) {
                                    val pointerIndex = event.findPointerIndex(activePointerId)
                                    if (pointerIndex != -1) {
                                        val x = event.getX(pointerIndex) * slidingLayout.scaleX
                                        val y = event.getY(pointerIndex) * slidingLayout.scaleY
                                        val translationX = x - lastX
                                        val translationY = y - lastY
                                        slidingLayout.translationX += translationX
                                        if (moveFreely) {
                                            slidingLayout.translationY += translationY
                                        }
                                        lastX = x - translationX
                                        lastY = y - translationY
                                        velocityTracker?.addMovement(
                                            MotionEvent.obtain(
                                                event.downTime,
                                                event.eventTime,
                                                event.action,
                                                slidingLayout.translationX,
                                                slidingLayout.translationY,
                                                event.metaState
                                            )
                                        )
                                        if (isTap && ((startTranslationX - slidingLayout.translationX) !in touchSlopRange || (startTranslationY - slidingLayout.translationY) !in touchSlopRange)) {
                                            isTap = false
                                        }
                                    }
                                }
                            }
                        }
                        MotionEvent.ACTION_POINTER_UP -> {
                            liveTapSeekDoubleTapState.clearCandidate()
                            val pointerIndex = event.actionIndex
                            val pointerId = event.getPointerId(pointerIndex)
                            if (pointerId == activePointerId) {
                                var newId = -1
                                for (i in 0 until event.pointerCount) {
                                    val id = event.getPointerId(i)
                                    if (id != activePointerId) {
                                        val x = event.getX(i)
                                        val y = event.getY(i)
                                        if (x in 0f..playerLayout.width.toFloat() && y in 0f..playerLayout.height.toFloat()) {
                                            newId = id
                                            lastX = x * slidingLayout.scaleX
                                            lastY = y * slidingLayout.scaleY
                                            break
                                        }
                                    }
                                }
                                if (newId == -1) {
                                    upAction(event)
                                }
                                activePointerId = newId
                            }
                        }
                        MotionEvent.ACTION_UP -> upAction(event)
                        MotionEvent.ACTION_CANCEL -> {
                            liveTapSeekDoubleTapState.clearCandidate()
                            upAction(event)
                        }
                    }
                }
                true
            }
            chatTouchView.setOnTouchListener { _, event ->
                if (isInteractionLocked) {
                    return@setOnTouchListener true
                }
                if (phoneChatOverlayGesture?.onOverlayTouch(event) == true) {
                    return@setOnTouchListener true
                }

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        chatTouchActive = true
                        chatStatusBarSwipe = !isPortrait && event.y <= 100
                        chatLinearLayout.dispatchTouchEvent(event)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (chatStatusBarSwipe) {
                            chatLinearLayout.dispatchTouchEvent(
                                MotionEvent.obtain(event).apply {
                                    action = MotionEvent.ACTION_CANCEL
                                }
                            )
                        } else {
                            chatLinearLayout.dispatchTouchEvent(event)
                        }
                    }
                    else -> {
                        chatLinearLayout.dispatchTouchEvent(event)
                        if (event.actionMasked == MotionEvent.ACTION_UP ||
                            event.actionMasked == MotionEvent.ACTION_CANCEL
                        ) {
                            chatTouchActive = false
                        }
                    }
                }
                true
            }
            with(playerControls) {
                playPause.setOnClickListener {
                    showController(force = true)
                    playPause()
                }
                rewind.text = (requireContext().prefs().getString(C.PLAYER_REWIND, "10")?.toLongOrNull() ?: 10).toString()
                rewind.contentDescription = getString(
                    R.string.player_rewind_seconds,
                    rewind.text.toString().toLongOrNull() ?: 10,
                )
                rewind.setOnClickListener {
                    showController(force = true)
                    rewind()
                }
                fastForward.text = (requireContext().prefs().getString(C.PLAYER_FORWARD, "10")?.toLongOrNull() ?: 10).toString()
                fastForward.contentDescription = getString(
                    R.string.player_fast_forward_seconds,
                    fastForward.text.toString().toLongOrNull() ?: 10,
                )
                fastForward.setOnClickListener {
                    showController(force = true)
                    fastForward()
                }
                progressBar.addListener(
                    object : TimeBar.OnScrubListener {
                        override fun onScrubStart(timeBar: TimeBar, position: Long) {
                            cancelLiveTapSeek()
                            hudVisibility.onScrubStart()
                            binding.playerControls.root.removeCallbacks(controllerHideAction)
                            if (isLiveRewindAvailable()) {
                                liveRewindScrubPositionMs = position
                                showLiveRewindPreview(position)
                                return
                            }
                            binding.playerControls.position.text = DateUtils.formatElapsedTime(position / 1000)
                            binding.playerControls.position.contentDescription = getString(
                                R.string.player_position,
                                binding.playerControls.position.text,
                            )
                            binding.playerControls.root.removeCallbacks(controllerHideAction)
                        }

                        override fun onScrubMove(timeBar: TimeBar, position: Long) {
                            if (isLiveRewindAvailable()) {
                                liveRewindScrubPositionMs = position
                                showLiveRewindPreview(position)
                                return
                            }
                            binding.playerControls.position.text = DateUtils.formatElapsedTime(position / 1000)
                            binding.playerControls.position.contentDescription = getString(
                                R.string.player_position,
                                binding.playerControls.position.text,
                            )
                        }

                        override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                            hudVisibility.onScrubStop()
                            if (isLiveRewindAvailable()) {
                                liveRewindScrubPositionMs = null
                                hideLiveRewindPreview()
                                if (!canceled) {
                                    onLiveRewindScrubFinished(position)
                                } else {
                                    updateLiveRewindProgress()
                                }
                                scheduleControllerHideAfterScrub()
                                return
                            }
                            if (!canceled) {
                                seek(position)
                            }
                            scheduleControllerHideAfterScrub()
                        }
                    }
                )
                position.text = DateUtils.formatElapsedTime(0)
                duration.text = DateUtils.formatElapsedTime(0)
                position.contentDescription = getString(R.string.player_position, position.text)
                duration.contentDescription = getString(R.string.player_duration, duration.text)
                subtitleView.setUserDefaultStyle()
                subtitleView.setUserDefaultTextSize()
            }
            playerControls.interactionLock.setOnClickListener {
                setInteractionLocked(!isInteractionLocked)
            }
            // Use the actionable child for the locked-parent hit test. The
            // frame owns layout geometry, but can be a translated/expanded
            // wrapper and is not a reliable descendant target once the parent
            // starts intercepting all other touches.
            playerLayout.interactionUnlockView = playerControls.interactionLock
            playerControls.root.interactionUnlockView = playerControls.interactionLock
            setInteractionLocked(isInteractionLocked, force = true)
            dismissPlayer.setOnClickListener {
                (activity as? MainActivity)?.closePlayer() ?: close()
            }
        }
    }

    private fun isLiveRewindEnabled(): Boolean =
        requireContext().prefs().getBoolean(C.PLAYER_LIVE_REWIND, true)

    protected fun isLiveRewindAvailable(): Boolean =
        playbackService?.type == BasePlaybackService.STREAM &&
            isLiveRewindEnabled() &&
            liveRewindVod != null

    private fun prepareLiveRewind(
        requestedStreamId: String? = null,
        requestedStreamCreatedAt: String? = null,
    ) {
        if (!isLiveRewindEnabled() || playbackService?.type != BasePlaybackService.STREAM) {
            stopLiveRewindTicker()
            liveRewindVod = null
            pausedLivePositionMs = null
            liveRewindStreamId = null
            liveRewindStreamCreatedAt = null
            liveRewindFrozenEdgeMs = null
            liveRewindStreamOffline = false
            pendingLiveSession = null
            updateLiveRewindUi()
            return
        }
        val currentStream = viewModel.stream.value
        val service = playbackService ?: return
        val streamCreatedAt = requestedStreamCreatedAt
            ?: currentStream?.createdAt
            ?: service.createdAt
        if (streamCreatedAt.isNullOrBlank()) return
        val streamId = requestedStreamId
            ?: currentStream?.id
            ?: service.streamId
        val generation = ++liveRewindSessionGeneration
        liveRewindStreamId = streamId
        liveRewindStreamCreatedAt = streamCreatedAt
        liveRewindStreamWasLive = true
        liveRewindStreamOffline = false
        liveRewindFrozenEdgeMs = null
        pausedLivePositionMs = null
        updateLiveRewindUi()
        liveRewindDiscoveryJob?.cancel()
        liveRewindDiscoveryJob = viewLifecycleOwner.lifecycleScope.launch {
            repeat(2) { attempt ->
                if (attempt > 0) delay(20_000L)
                if (generation != liveRewindSessionGeneration || !isLiveRewindEnabled() || view == null || !isAdded) return@launch
                if (attempt > 0) {
                    val refreshedStream = viewModel.stream.value
                    if (!isSameLiveStreamSession(
                            expectedId = streamId,
                            expectedCreatedAt = streamCreatedAt,
                            actualId = refreshedStream?.id,
                            actualCreatedAt = refreshedStream?.createdAt,
                        )
                    ) return@launch
                }
                val vod = try {
                    viewModel.findCurrentRecordingVod(
                        channelId = service.channelId,
                        channelLogin = service.channelLogin,
                        streamCreatedAt = streamCreatedAt,
                        networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), true),
                    )
                } catch (_: Exception) {
                    null
                }
                if (vod != null) {
                    if (generation != liveRewindSessionGeneration) return@launch
                    val ownerVodId = getLiveRewindVodId()
                    if (ownerVodId != null && ownerVodId != vod.id) {
                        goLive(
                            force = true,
                            onSuccess = {
                                if (generation == liveRewindSessionGeneration) applyLiveRewindVod(vod, generation)
                            },
                            onFailure = {
                                if (generation == liveRewindSessionGeneration) updateLiveRewindUi()
                            },
                        )
                    } else {
                        applyLiveRewindVod(vod, generation)
                    }
                    return@launch
                }
            }
            if (generation == liveRewindSessionGeneration) {
                stopLiveRewindTicker()
                updateLiveRewindUi()
            }
        }
    }

    private fun applyLiveRewindVod(vod: LiveRewindVod, generation: Long) {
        if (generation != liveRewindSessionGeneration || view == null || !isAdded) return
        liveRewindVod = vod
        liveRewindStreamOffline = false
        pausedLivePositionMs = null
        viewLifecycleOwner.lifecycleScope.launch {
            livePlaybackMode = if (getLiveRewindVodId() == vod.id) {
                LivePlaybackMode.Rewound(vod.id)
            } else {
                LivePlaybackMode.Live
            }
            updateLiveRewindUi()
            if (livePlaybackMode is LivePlaybackMode.Rewound) startLiveRewindChat(getCurrentPosition() ?: 0L)
            startLiveRewindTicker()
        }
    }

    @SuppressLint("RepeatOnLifecycleWrongUsage")
    private fun startLiveRewindTicker() {
        liveRewindTickerJob?.cancel()
        liveRewindTickerJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    updateLiveRewindProgress()
                    delay(500L)
                }
            }
        }
    }

    private fun stopLiveRewindTicker() {
        liveRewindTickerJob?.cancel()
        liveRewindTickerJob = null
    }

    @SuppressLint("RepeatOnLifecycleWrongUsage")
    private fun startStreamUptimeTicker() {
        streamUptimeWasLive = true
        if (streamUptimeTickerJob?.isActive == true) return
        streamUptimeTickerJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    updateStreamUptime()
                    delay(1_000L)
                }
            }
        }
    }

    private fun stopStreamUptimeTicker() {
        streamUptimeTickerJob?.cancel()
        streamUptimeTickerJob = null
    }

    private fun streamStartedAt(): String? =
        viewModel.stream.value?.createdAt
            ?: playbackService?.createdAt

    private fun hideStreamUptime() {
        val timeView = binding.playerControls.liveTimeGroup
        val wasVisible = timeView.isVisible
        setLiveIndicatorVisible(false)
        timeView.visibility = View.GONE
        timeView.text = null
        timeView.contentDescription = null
        timeView.setOnClickListener(null)
        timeView.isClickable = false
        timeView.isFocusable = false
        if (wasVisible) binding.playerControls.root.refreshAvailabilityIfChanged()
    }

    private fun setLiveIndicatorVisible(visible: Boolean) {
        val timeView: TextView = _binding?.playerControls?.liveTimeGroup ?: return
        if ((timeView.compoundDrawablesRelative[0] != null) == visible) return

        val indicator = if (visible) {
            timeView.context.getDrawable(R.drawable.bg_game_viewer_dot)
        } else {
            null
        }
        timeView.setCompoundDrawablesRelativeWithIntrinsicBounds(indicator, null, null, null)
        timeView.compoundDrawablePadding = if (visible) {
            (4f * resources.displayMetrics.density).roundToInt()
        } else {
            0
        }
    }

    private fun updateStreamUptime() {
        if (playbackService?.type != BasePlaybackService.STREAM || isLiveRewindAvailable()) return
        if (!requireContext().prefs().getBoolean(C.UI_UPTIME, true)) {
            hideStreamUptime()
            return
        }
        val startedAtMs = parseStreamStartedAtMs(streamStartedAt())
        val uptime = startedAtMs?.let { formatStreamUptime(it, System.currentTimeMillis()) }
        if (uptime == null) {
            hideStreamUptime()
            return
        }
        val timeView = binding.playerControls.liveTimeGroup
        val wasVisible = timeView.isVisible
        setLiveIndicatorVisible(isPlaybackRequested())
        val timeText = getString(R.string.player_live_position, uptime, getString(R.string.player_live))
        timeView.visibility = View.VISIBLE
        timeView.text = timeText
        timeView.contentDescription = getString(R.string.player_uptime, timeText)
        timeView.setOnClickListener(null)
        timeView.isClickable = false
        timeView.isFocusable = false
        if (!wasVisible) binding.playerControls.root.refreshAvailabilityIfChanged()
    }

    protected fun updateLiveRewindProgress() {
        val vod = liveRewindVod ?: return
        if (!isLiveRewindAvailable() || view == null) {
            pausedLivePositionMs = null
            setLiveIndicatorVisible(false)
            binding.playerControls.progressBar.visibility = View.GONE
            binding.playerControls.position.visibility = View.GONE
            binding.playerControls.duration.visibility = View.GONE
            binding.playerControls.liveTimeGroup.visibility = View.GONE
            binding.playerControls.liveTimeGroup.setOnClickListener(null)
            binding.playerControls.liveTimeGroup.isClickable = false
            binding.playerControls.liveTimeGroup.isFocusable = false
            binding.playerControls.duration.setOnClickListener(null)
            binding.playerControls.duration.isClickable = false
            binding.playerControls.duration.isFocusable = false
            return
        }
        val currentStream = viewModel.stream.value
        val currentStreamId = currentStream?.id
        val currentStreamCreatedAt = currentStream?.createdAt
        if (currentStream != null) {
            liveRewindStreamWasLive = true
            val currentState = liveRewindSourceState()
            val recoveredState = currentState.recoverSession(
                LiveRewindSession(currentStreamId, currentStreamCreatedAt),
            )
            if (recoveredState != currentState) {
                liveRewindStreamOffline = recoveredState.offline
                liveRewindFrozenEdgeMs = recoveredState.frozenEdgeMs
                if (liveRewindTickerJob?.isActive != true) startLiveRewindTicker()
                updateLiveRewindUi()
                return
            }
        } else if (shouldMarkLiveStreamOffline(
                viewModel.streamStatusKnown.value,
                liveRewindStreamWasLive || streamUptimeWasLive,
                false,
            ) && !liveRewindStreamOffline) {
            onLiveStreamWentOffline()
            return
        }
        if (hasLiveStreamSessionChanged(
                oldId = liveRewindStreamId,
                oldCreatedAt = liveRewindStreamCreatedAt,
                newId = currentStreamId,
                newCreatedAt = currentStreamCreatedAt,
            ) && pendingLiveSession != LiveRewindSession(currentStreamId, currentStreamCreatedAt)
        ) {
            onLiveStreamSessionChanged(currentStreamId, currentStreamCreatedAt)
            return
        }
        val edgeMs = freezeLiveEdge(vod.predictedDurationMs(), liveRewindFrozenEdgeMs)
        val playbackRequested = isPlaybackRequested()
        if (shouldSeekToLiveAfterPausedLive(livePlaybackMode, playbackRequested, pausedLivePositionMs)) {
            pausedLivePositionMs = null
            seekToLivePosition()
        }
        pausedLivePositionMs = liveRewindPausedPositionMs(
            mode = livePlaybackMode,
            edgeMs = edgeMs,
            playbackRequested = playbackRequested,
            existingPositionMs = pausedLivePositionMs,
        )
        val playerPositionMs = getCurrentPosition() ?: if (livePlaybackMode is LivePlaybackMode.Live) {
            edgeMs
        } else {
            0L
        }
        binding.playerControls.progressBar.setDuration(edgeMs)
        if (liveRewindScrubPositionMs == null) {
            val positionMs = liveRewindTimelinePositionMs(
                mode = livePlaybackMode,
                edgeMs = edgeMs,
                playerPositionMs = playerPositionMs,
                scrubPositionMs = null,
                playbackRequested = playbackRequested,
                pausedLivePositionMs = pausedLivePositionMs,
            )
            binding.playerControls.progressBar.setPosition(positionMs)
        }
        val displayedPositionMs = liveRewindTimelinePositionMs(
            mode = livePlaybackMode,
            edgeMs = edgeMs,
            playerPositionMs = playerPositionMs,
            scrubPositionMs = liveRewindScrubPositionMs,
            playbackRequested = playbackRequested,
            pausedLivePositionMs = pausedLivePositionMs,
        )
        val isRewound = livePlaybackMode is LivePlaybackMode.Rewound
        val isLivePaused = livePlaybackMode is LivePlaybackMode.Live &&
            !playbackRequested &&
            liveRewindScrubPositionMs == null
        val isBehindLive = isRewound || isLivePaused || liveRewindScrubPositionMs != null
        val isAtLiveEdge = !isBehindLive &&
            !liveRewindStreamOffline &&
            !liveRewindSwitching &&
            !liveRewindReturningLive
        val currentPlayer = liveBufferHealthPlayer()
        updateLiveBufferHealth(
            player = currentPlayer,
            shouldShow = !isBehindLive && currentPlayer?.playWhenReady == true,
        )
        val positionTimeText = if (isBehindLive) {
            DateUtils.formatElapsedTime(displayedPositionMs / 1000L)
        } else {
            getString(R.string.player_live)
        }
        val timeText = if (isBehindLive) {
            getString(
                R.string.player_live_position,
                positionTimeText,
                getString(R.string.player_live),
            )
        } else {
            getString(R.string.player_live)
        }
        val timeActionable = isRewound && !liveRewindStreamOffline
        val timeDescription = if (timeActionable) {
            getString(R.string.player_return_to_live)
        } else if (isBehindLive) {
            getString(R.string.player_position, timeText)
        } else {
            getString(R.string.player_live)
        }
        binding.playerControls.position.visibility = View.GONE
        binding.playerControls.duration.visibility = View.GONE
        binding.playerControls.liveTimeGroup.visibility = View.VISIBLE
        binding.playerControls.liveTimeGroup.text = timeText
        setLiveIndicatorVisible(isAtLiveEdge)
        binding.playerControls.liveTimeGroup.contentDescription = timeDescription
        if (timeActionable) {
            binding.playerControls.liveTimeGroup.setOnTouchListener(null)
            binding.playerControls.liveTimeGroup.setOnClickListener {
                showController(force = true)
                goLive()
            }
            binding.playerControls.liveTimeGroup.isClickable = true
            binding.playerControls.liveTimeGroup.isFocusable = true
        } else {
            // Keep passive status text out of accessibility click navigation,
            // but consume touches over the fixed scrub lane so they cannot
            // fall through and seek the timeline.
            binding.playerControls.liveTimeGroup.setOnClickListener(null)
            binding.playerControls.liveTimeGroup.setOnTouchListener { _, _ -> true }
            binding.playerControls.liveTimeGroup.isClickable = false
            binding.playerControls.liveTimeGroup.isFocusable = false
        }
    }

    protected fun updateLiveBufferHealth(
        player: Player?,
        shouldShow: Boolean,
    ) {
        val nowMs = SystemClock.elapsedRealtime()
        val isLiveVideo = shouldShow && player?.playWhenReady == true &&
            player.isCurrentMediaItemLive && player.videoSize.width > 0 && player.videoSize.height > 0
        if (!isLiveVideo) {
            hasEstablishedLiveBufferHealth = false
            lastLiveBufferHealthOffsetMs = null
            liveBufferHealthTrend.update(null, null, nowMs)
        }
        val state = player?.playbackState
        val currentOffsetMs = player?.currentLiveOffset?.takeIf { it != Media3C.TIME_UNSET && it >= 0L }
        if (isLiveVideo && state == androidx.media3.common.Player.STATE_READY) {
            if (player.totalBufferedDuration >= 0L && currentOffsetMs != null) {
                hasEstablishedLiveBufferHealth = true
                lastLiveBufferHealthOffsetMs = currentOffsetMs
            } else {
                hasEstablishedLiveBufferHealth = false
                lastLiveBufferHealthOffsetMs = null
            }
        }
        val allowBuffering = isLiveVideo && hasEstablishedLiveBufferHealth &&
            state == androidx.media3.common.Player.STATE_BUFFERING
        val reading = if (isLiveVideo && (state == androidx.media3.common.Player.STATE_READY || allowBuffering)) {
            val offsetMs = currentOffsetMs ?: lastLiveBufferHealthOffsetMs.takeIf { allowBuffering }
            if (offsetMs != null && player.totalBufferedDuration >= 0L) {
                lastLiveBufferHealthOffsetMs = currentOffsetMs ?: lastLiveBufferHealthOffsetMs
                liveBufferHealthTrend.update(player.totalBufferedDuration, offsetMs, nowMs)
            } else {
                liveBufferHealthTrend.update(null, null, nowMs)
            }
        } else {
            if (!allowBuffering) {
                hasEstablishedLiveBufferHealth = false
                lastLiveBufferHealthOffsetMs = null
            }
            liveBufferHealthTrend.update(null, null, nowMs)
        }

        val healthView = binding.playerControls.bufferHealthGroup
        val wasVisible = healthView.isVisible
        if (reading == null) {
            healthView.visibility = View.GONE
        } else {
            val trendSuffix = if (reading.isDecreasing) "↓" else ""
            healthView.visibility = View.VISIBLE
            healthView.text = "${reading.bufferSeconds}s$trendSuffix / ${reading.liveOffsetSeconds}s"
            val buffered = resources.getQuantityString(
                R.plurals.player_buffered_seconds,
                reading.bufferSeconds,
                reading.bufferSeconds,
            )
            val trendDescription = if (reading.isDecreasing) {
                getString(R.string.player_buffer_decreasing)
            } else {
                ""
            }
            val behindLive = resources.getQuantityString(
                R.plurals.player_live_behind_seconds,
                reading.liveOffsetSeconds,
                reading.liveOffsetSeconds,
            )
            healthView.contentDescription = getString(
                R.string.player_buffer_health_description,
                buffered,
                trendDescription,
                behindLive,
            )
        }
        if (healthView.isVisible != wasVisible) {
            binding.playerControls.root.refreshAvailabilityIfChanged()
        }
    }

    protected fun resetLiveBufferHealth() {
        hasEstablishedLiveBufferHealth = false
        lastLiveBufferHealthOffsetMs = null
        liveBufferHealthTrend.reset()
        if (view != null) binding.playerControls.bufferHealthGroup.visibility = View.GONE
    }

    private fun updateLiveRewindUi() {
        val showLiveTransport = requireContext().isTelevision() || isLiveRewindAvailable()
        binding.playerControls.rewind.visibility = if (showLiveTransport) View.VISIBLE else View.GONE
        binding.playerControls.fastForward.visibility = if (showLiveTransport) View.VISIBLE else View.GONE
        updateLiveClipSourceAvailability()
        if (!isLiveRewindAvailable()) {
            cancelLiveTapSeek()
            pausedLivePositionMs = null
            setLiveRewindTimelineLayout(false)
            binding.playerControls.progressBar.visibility = View.GONE
            binding.playerControls.position.visibility = View.GONE
            binding.playerControls.duration.visibility = View.GONE
            binding.playerControls.liveTimeGroup.visibility = View.GONE
            setLiveIndicatorVisible(false)
            binding.playerControls.liveTimeGroup.setOnClickListener(null)
            binding.playerControls.liveTimeGroup.setOnTouchListener(null)
            binding.playerControls.liveTimeGroup.isClickable = false
            binding.playerControls.liveTimeGroup.isFocusable = false
            binding.playerControls.duration.setOnClickListener(null)
            binding.playerControls.duration.isClickable = false
            binding.playerControls.duration.isFocusable = false
            binding.playerControls.duration.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
            refreshHudLayout()
            return
        }
        setLiveRewindTimelineLayout(true)
        binding.playerControls.progressBar.visibility = View.VISIBLE
        binding.playerControls.progressBar.setPlayedColor(
            requireContext().getColor(R.color.channel_points_reward_default),
        )
        binding.playerControls.progressBar.setScrubberColor(
            requireContext().getColor(R.color.channel_points_reward_default),
        )
        updateLiveRewindProgress()
        refreshHudLayout()
    }

    private fun setLiveRewindTimelineLayout(enabled: Boolean) {
        binding.playerControls.root.setLiveRewindEnabled(enabled)
    }

    private fun showLiveRewindPreview(positionMs: Long) {
        setLiveIndicatorVisible(false)
        val edgeMs = currentLiveEdgeMs()
        val previewPositionMs = positionMs.coerceIn(0L, edgeMs)
        val previewPositionTimeText = if (previewPositionMs >= edgeMs) {
            getString(R.string.player_live)
        } else {
            DateUtils.formatElapsedTime(previewPositionMs / 1000L)
        }
        val timeText = if (previewPositionMs >= edgeMs) {
            getString(R.string.player_live)
        } else {
            getString(
                R.string.player_live_position,
                previewPositionTimeText,
                getString(R.string.player_live),
            )
        }
        binding.playerControls.position.visibility = View.GONE
        binding.playerControls.duration.visibility = View.GONE
        binding.playerControls.liveTimeGroup.visibility = View.VISIBLE
        binding.playerControls.liveTimeGroup.text = timeText
        binding.playerControls.liveTimeGroup.contentDescription = getString(
            R.string.player_position,
            timeText,
        )
        binding.playerControls.timelineContent.setLiveRewindPreview(
            text = timeText,
            fraction = if (edgeMs > 0L) previewPositionMs.toFloat() / edgeMs else 1f,
        )
        binding.playerControls.progressBar.setPosition(previewPositionMs)
    }

    private fun liveTapSeekZoneForEvent(event: MotionEvent): LiveTapSeekZone? {
        if (!isMaximized || requireContext().isTelevision() || (!isPortrait && event.y <= 100f) ||
            binding.playerLayout.width <= 0 || event.x < 0f || event.x >= binding.playerLayout.width
        ) {
            return null
        }
        return liveTapSeekZone(event.x, binding.playerLayout.width)
    }

    private fun handleLiveTapSeekDoubleTap(direction: LiveTapSeekDirection): Boolean {
        if (!isMaximized || requireContext().isTelevision() || !isLiveRewindAvailable() ||
            liveRewindStreamOffline || liveRewindSwitching || liveRewindReturningLive
        ) {
            return false
        }
        val edgeMs = currentLiveEdgeMs()
        if (edgeMs <= 0L) return false
        val playbackRequested = isPlaybackRequested()
        pausedLivePositionMs = liveRewindPausedPositionMs(
            mode = livePlaybackMode,
            edgeMs = edgeMs,
            playbackRequested = playbackRequested,
            existingPositionMs = pausedLivePositionMs,
        )
        val playerPositionMs = getCurrentPosition() ?: if (livePlaybackMode is LivePlaybackMode.Live) {
            edgeMs
        } else {
            return false
        }
        val currentPositionMs = liveRewindTimelinePositionMs(
            mode = livePlaybackMode,
            edgeMs = edgeMs,
            playerPositionMs = playerPositionMs,
            scrubPositionMs = null,
            playbackRequested = playbackRequested,
            pausedLivePositionMs = pausedLivePositionMs,
        )
        val target = liveTapSeekAccumulator.addTap(currentPositionMs, edgeMs, direction)
        liveRewindScrubPositionMs = target.positionMs
        showController(force = true)
        showLiveRewindPreview(target.positionMs)
        showLiveTapSeekFeedback(direction, target)
        binding.playerControls.root.removeCallbacks(liveTapSeekCommitAction)
        binding.playerControls.root.postDelayed(liveTapSeekCommitAction, LIVE_TAP_SEEK_DEBOUNCE_MS)
        return true
    }

    private fun commitLiveTapSeek() {
        val requestedTarget = liveTapSeekAccumulator.takePendingTarget() ?: return
        liveRewindScrubPositionMs = null
        hideLiveRewindPreview()
        if (!isLiveRewindAvailable() || liveRewindStreamOffline || liveRewindSwitching || liveRewindReturningLive) {
            updateLiveRewindProgress()
            return
        }
        val edgeMs = currentLiveEdgeMs()
        val vod = liveRewindVod ?: return
        val targetMs = requestedTarget.positionMs.coerceIn(0L, edgeMs)
        if (shouldReturnToLiveAfterLiveTap(requestedTarget, edgeMs)) {
            seekToCurrentLiveEdge()
        } else {
            playRecordingVodAt(vod, targetMs)
        }
        scheduleControllerHideAfterScrub()
    }

    private fun cancelLiveTapSeek() {
        binding.playerControls.root.removeCallbacks(liveTapSeekCommitAction)
        binding.liveTapSeekFeedbackOverlay.removeCallbacks(liveTapSeekFeedbackHideAction)
        liveTapSeekAccumulator.clear()
        liveTapSeekDoubleTapState.reset()
        liveRewindScrubPositionMs = null
        hideLiveTapSeekFeedback()
    }

    private fun showLiveTapSeekFeedback(
        direction: LiveTapSeekDirection,
        target: LiveTapSeekTarget,
    ) {
        val feedbackDirection = liveTapSeekFeedbackDirection(target, direction)
        val feedback = if (feedbackDirection == LiveTapSeekDirection.BACKWARD) {
            binding.liveTapSeekBackwardFeedback
        } else {
            binding.liveTapSeekForwardFeedback
        }
        val otherFeedback = if (feedbackDirection == LiveTapSeekDirection.BACKWARD) {
            binding.liveTapSeekForwardFeedback
        } else {
            binding.liveTapSeekBackwardFeedback
        }
        otherFeedback.animate().cancel()
        otherFeedback.visibility = View.GONE
        feedback.animate().cancel()
        feedback.visibility = View.VISIBLE
        feedback.alpha = 0f
        feedback.scaleX = 0.82f
        feedback.scaleY = 0.82f
        val seconds = (abs(target.effectiveDeltaMs) / 1000L).toInt()
        val label = if (target.atLiveEdge) {
            getString(R.string.player_live_tap_seek_live)
        } else if (feedbackDirection == LiveTapSeekDirection.BACKWARD) {
            getString(R.string.player_live_tap_seek_back, seconds)
        } else {
            getString(R.string.player_live_tap_seek_forward, seconds)
        }
        if (feedbackDirection == LiveTapSeekDirection.BACKWARD) {
            binding.liveTapSeekBackwardLabel.text = label
        } else {
            binding.liveTapSeekForwardLabel.text = label
        }
        feedback.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(150L)
            .start()
        binding.liveTapSeekFeedbackOverlay.removeCallbacks(liveTapSeekFeedbackHideAction)
        binding.liveTapSeekFeedbackOverlay.postDelayed(
            liveTapSeekFeedbackHideAction,
            LIVE_TAP_SEEK_FEEDBACK_HIDE_DELAY_MS,
        )
    }

    private fun hideLiveTapSeekFeedback() {
        binding.liveTapSeekBackwardFeedback.animate().cancel()
        binding.liveTapSeekForwardFeedback.animate().cancel()
        binding.liveTapSeekBackwardFeedback.visibility = View.GONE
        binding.liveTapSeekForwardFeedback.visibility = View.GONE
    }

    private fun onLiveRewindScrubFinished(positionMs: Long) {
        val vod = liveRewindVod ?: return
        val edgeMs = currentLiveEdgeMs()
        val targetMs = positionMs.coerceIn(0L, edgeMs)
        if (shouldReturnToLive(targetMs, edgeMs)) {
            seekToCurrentLiveEdge()
        } else {
            playRecordingVodAt(vod, targetMs)
        }
    }

    private fun seekToCurrentLiveEdge() {
        if (shouldSeekToLiveAfterLiveTarget(livePlaybackMode, true)) {
            pausedLivePositionMs = null
            seekToLivePosition()
        } else {
            goLive()
        }
    }

    private fun playRecordingVodAt(vod: LiveRewindVod, targetMs: Long) {
        if (!liveRewindSwitching && livePlaybackMode is LivePlaybackMode.Rewound &&
            (livePlaybackMode as LivePlaybackMode.Rewound).vodId == vod.id
        ) {
            seek(targetMs)
            chatFragment?.updatePosition(targetMs)
            updateLiveRewindProgress()
            return
        }
        if (liveRewindSwitchJob?.isActive == true && !liveRewindReturningLive) {
            liveRewindPendingVodId = vod.id
            liveRewindPendingTargetMs = targetMs
            return
        }
        liveRewindPendingVodId = null
        liveRewindPendingTargetMs = null
        val previousPlaybackMode = livePlaybackMode
        val generation = ++liveRewindSwitchGeneration
        liveRewindSwitchJob?.cancel()
        liveRewindReturningLive = false
        liveRewindSwitching = true
        updateLiveClipSourceAvailability()
        liveRewindSwitchJob = viewLifecycleOwner.lifecycleScope.launch {
            val success = startLiveRewind(vod.id, targetMs)
            if (generation != liveRewindSwitchGeneration) return@launch
            liveRewindSwitching = false
            updateLiveClipSourceAvailability()
            if (!success) {
                livePlaybackMode = previousPlaybackMode
                updateLiveRewindUi()
                return@launch
            }
            val pendingTarget = liveRewindPendingTargetMs
                .takeIf { liveRewindPendingVodId == vod.id }
            liveRewindPendingVodId = null
            liveRewindPendingTargetMs = null
            livePlaybackMode = LivePlaybackMode.Rewound(vod.id)
            pausedLivePositionMs = null
            startLiveRewindChat(targetMs)
            pendingTarget?.let {
                seek(it)
                chatFragment?.updatePosition(it)
            }
            updateLiveRewindUi()
        }
    }

    protected fun enterLiveRewindChat(positionMs: Long) {
        liveRewindVod?.let { vod ->
            chatFragment?.enterVideoReplay(vod.id, vod.createdAt, positionMs)
        }
    }

    private fun goLive() {
        goLive(force = false)
    }

    private fun goLive(
        force: Boolean,
        onSuccess: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null,
    ) {
        if (liveRewindReturningLive || (!force && livePlaybackMode is LivePlaybackMode.Live && !liveRewindSwitching)) return
        val generation = ++liveRewindSwitchGeneration
        liveRewindSwitchJob?.cancel()
        liveRewindPendingVodId = null
        liveRewindPendingTargetMs = null
        liveRewindReturningLive = true
        liveRewindSwitching = true
        updateLiveClipSourceAvailability()
        liveRewindSwitchJob = viewLifecycleOwner.lifecycleScope.launch {
            val success = returnToLivePlayback()
            if (generation != liveRewindSwitchGeneration) return@launch
            liveRewindSwitching = false
            liveRewindReturningLive = false
            updateLiveClipSourceAvailability()
            if (success) {
                livePlaybackMode = LivePlaybackMode.Live
                pausedLivePositionMs = null
                chatFragment?.returnToLiveChat()
                val newSession = commitPendingLiveSession()
                if (newSession != null) {
                    prepareLiveRewind(newSession.id, newSession.createdAt)
                } else {
                    updateLiveRewindUi()
                    onSuccess?.invoke()
                }
            } else {
                onFailure?.invoke()
            }
        }
    }

    private fun currentLiveEdgeMs(): Long = liveRewindVod?.predictedDurationMs()?.let {
        freezeLiveEdge(it, liveRewindFrozenEdgeMs)
    }
        ?: 0L

    private fun hideLiveRewindPreview() {
        setLiveIndicatorVisible(false)
        binding.playerControls.position.visibility = View.GONE
        binding.playerControls.duration.visibility = View.GONE
        binding.playerControls.liveTimeGroup.visibility = View.GONE
        binding.playerControls.timelineContent.clearLiveRewindPreview()
    }

    private fun onLiveStreamWentOffline() {
        if (view == null) return
        cancelLiveTapSeek()
        streamUptimeWasLive = false
        val liveEdgeMs = liveRewindVod?.predictedDurationMs()
        if (liveEdgeMs == null) {
            stopStreamUptimeTicker()
            hideStreamUptime()
            return
        }
        val state = liveRewindSourceState().streamEnded(
            liveEdgeMs,
        )
        liveRewindStreamOffline = state.offline
        liveRewindFrozenEdgeMs = state.frozenEdgeMs
        stopStreamUptimeTicker()
        stopLiveRewindTicker()
        updateLiveRewindProgress()
    }

    private fun onLiveStreamSessionChanged(newStreamId: String?, newCreatedAt: String?) {
        if (!hasLiveStreamSessionChanged(
                oldId = liveRewindStreamId,
                oldCreatedAt = liveRewindStreamCreatedAt,
                newId = newStreamId,
                newCreatedAt = newCreatedAt,
            )
        ) return
        cancelLiveTapSeek()
        ++liveRewindSessionGeneration
        liveRewindDiscoveryJob?.cancel()
        if (livePlaybackMode is LivePlaybackMode.Rewound) {
            val frozenPendingState = liveRewindSourceState().observeNewSessionWhileRewound(
                session = LiveRewindSession(newStreamId, newCreatedAt),
                frozenEdgeMs = currentLiveEdgeMs(),
            )
            pendingLiveSession = frozenPendingState.pendingSession
            liveRewindStreamOffline = frozenPendingState.offline
            liveRewindFrozenEdgeMs = frozenPendingState.frozenEdgeMs
            updateLiveRewindUi()
            goLive(force = true)
        } else {
            val state = liveRewindSourceState().observeSession(
                LiveRewindSession(newStreamId, newCreatedAt),
            )
            val session = state.committedSession ?: return
            pendingLiveSession = null
            liveRewindStreamId = session.id
            liveRewindStreamCreatedAt = session.createdAt
            liveRewindStreamOffline = false
            liveRewindFrozenEdgeMs = null
            liveRewindVod = null
            pausedLivePositionMs = null
            prepareLiveRewind(session.id, session.createdAt)
        }
    }

    protected fun onLiveRewindPlaybackError(): Boolean {
        if (livePlaybackMode is LivePlaybackMode.Rewound) {
            goLive()
            return true
        }
        return false
    }

    @SuppressLint("RepeatOnLifecycleWrongUsage") // start() runs once after service binding and resets with the view lifecycle.
    fun start() {
        with(binding) {
            clearPlayerError()
            if (playbackService?.type != BasePlaybackService.OFFLINE_VIDEO) {
                viewModel.isFollowingChannel(
                    requireContext().tokenPrefs().getString(C.USER_ID, null),
                    playbackService?.channelId,
                    playbackService?.channelLogin,
                    requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                    requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    TwitchApiHelper.getGQLHeaders(requireContext(), true),
                    TwitchApiHelper.getHelixHeaders(requireContext()),
                )
                when (playbackService?.type) {
                    BasePlaybackService.STREAM -> {
                        playbackService?.channelLogin?.let {
                            viewModel.loadStreamInfo(
                                channelId = playbackService?.channelId,
                                channelLogin = it,
                                viewerCount = playbackService?.viewerCount,
                                loop = !requireContext().prefs().isChatEnabled() || isLiveRewindEnabled(),
                                networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext()),
                                gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                                refreshForLiveRewind = isLiveRewindEnabled(),
                            )
                        }
                        prepareLiveRewind()
                    }
                    BasePlaybackService.VIDEO -> {
                        val videoId = playbackService?.videoId
                        if (!videoId.isNullOrBlank() && (requireContext().prefs().getBoolean(C.PLAYER_GAMES_BUTTON, true) || requireContext().prefs().getBoolean(C.PLAYER_MENU_GAMES, false))) {
                            viewModel.loadGamesList(
                                videoId,
                                requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                TwitchApiHelper.getGQLHeaders(requireContext()),
                            )
                        }
                    }
                }
            }
            if (playbackService?.restoreQuality == true) {
                playbackService?.restoreQuality = false
                changeQuality(playbackService?.previousQuality)
            }
            with(playerControls) {
                configureClipControl()
                val channelLogin = playbackService?.channelLogin
                val channelName = playbackService?.channelName
                val displayName = if (channelLogin != null && !channelLogin.equals(channelName, true)) {
                    when (requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                        "0" -> "${channelName}(${channelLogin})"
                        "1" -> channelName
                        else -> channelLogin
                    }
                } else {
                    channelName
                }
                if (requireContext().prefs().getBoolean(C.PLAYER_CHANNEL, true)) {
                    updateChannelAvatar(playbackService?.channelImage)
                    channel.visibility = View.VISIBLE
                    channel.text = displayName
                    channel.isFocusable = true
                    channel.contentDescription = getString(R.string.player_open_channel, displayName.orEmpty())
                    channel.setOnClickListener { openChannel() }
                    channelAvatar.setOnClickListener { openChannel() }
                } else {
                    updateChannelAvatar(null)
                }
                val titleText = playbackService?.title
                if (!titleText.isNullOrBlank() && requireContext().prefs().getBoolean(C.PLAYER_TITLE, true)) {
                    title.visibility = View.VISIBLE
                    title.text = titleText
                }
                val gameName = playbackService?.gameName
                if (!gameName.isNullOrBlank() && requireContext().prefs().getBoolean(C.PLAYER_CATEGORY, true)) {
                    playingLabel.visibility = View.VISIBLE
                    category.visibility = View.VISIBLE
                    category.text = gameName
                    category.isFocusable = true
                    category.contentDescription = getString(R.string.player_open_category, gameName)
                    category.setOnClickListener {
                        findNavController().navigate(GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                            gameId = playbackService?.gameId,
                            gameSlug = playbackService?.gameSlug,
                            gameName = gameName
                        ))
                        minimize()
                    }
                } else {
                    playingLabel.visibility = View.GONE
                    category.visibility = View.GONE
                    category.text = null
                    category.setOnClickListener(null)
                    category.isFocusable = false
                    category.contentDescription = null
                }
                // Placement controls where an eligible action is shown; it must not
                // prevent the action from being rebound when the editor saves live.
                minimize.visibility = if (requireContext().isTelevision()) View.GONE else View.VISIBLE
                minimize.setOnClickListener { minimize() }
                volume.visibility = View.VISIBLE
                volume.setOnClickListener {
                    showController(force = true)
                    showVolumeDialog()
                }
                quality.visibility = View.VISIBLE
                quality.setOnClickListener {
                    showController(force = true)
                    showQualityDialog()
                }
                audioOnly.visibility = View.VISIBLE
                audioOnly.setOnClickListener {
                    showController(force = true)
                    when (playbackService?.quality?.name) {
                        BasePlaybackService.AUDIO_ONLY_QUALITY -> changeQuality(
                            resolveAudioModeRestoreQuality(
                                playbackService?.previousQuality,
                                playbackService?.qualities,
                            ),
                        )
                        BasePlaybackService.CHAT_ONLY_QUALITY -> changeQuality(playbackService?.previousQuality)
                        else -> changeQuality(playbackService?.qualities?.find { it.name == BasePlaybackService.AUDIO_ONLY_QUALITY })
                    }
                    changePlayerMode()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    audioCompressor.visibility = View.VISIBLE
                    if (requireContext().prefs().getBoolean(C.PLAYER_AUDIO_COMPRESSOR, false)) {
                        audioCompressor.setImageResource(R.drawable.baseline_audio_compressor_on_24dp)
                    } else {
                        audioCompressor.setImageResource(R.drawable.baseline_audio_compressor_off_24dp)
                    }
                    audioCompressor.setOnClickListener {
                        showController(force = true)
                        toggleAudioCompressor()
                    }
                }
                menu.visibility = View.VISIBLE
                menu.setOnClickListener {
                    showController(force = true)
                    PlayerSettingsDialog.newInstance(
                        type = playbackService?.type,
                        speedText = getCurrentSpeed()?.let { speed ->
                            requireContext().prefs().getString(C.PLAYER_SPEED_LIST, "0.25\n0.5\n0.75\n1.0\n1.25\n1.5\n1.75\n2.0\n3.0\n4.0\n8.0")
                                ?.split("\n")?.find { it == speed.toString() }
                        },
                        vodGames = !viewModel.gamesList.value.isNullOrEmpty()
                    ).show(childFragmentManager, "closeOnPip")
                }
                viewersLayout.apply {
                    setOnClickListener(null)
                    isClickable = false
                    isFocusable = false
                }
                if (supportsLiveClipping &&
                    (playbackService?.type == BasePlaybackService.STREAM ||
                        playbackService?.type == BasePlaybackService.VIDEO)
                ) {
                    requestLiveClipStatus()
                }
                if (playbackService?.type == BasePlaybackService.STREAM) {
                    if (!requireContext().tokenPrefs().getString(C.USERNAME, null).isNullOrBlank() &&
                        (!TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                                !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank())
                    ) {
                        if (requireContext().prefs().isChatEnabled()) {
                            toggleChatInput.visibility = View.VISIBLE
                            toggleChatInput.setOnClickListener {
                                showController(force = true)
                                toggleChatBar()
                            }
                        }
                        keyboardLayoutListener?.let(slidingLayout.viewTreeObserver::removeOnGlobalLayoutListener)
                        keyboardLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
                            val currentBinding = _binding ?: return@OnGlobalLayoutListener
                            val currentContext = context ?: return@OnGlobalLayoutListener
                            if (currentBinding.slidingLayout.isKeyboardShown) {
                                if (!isKeyboardShown) {
                                    isKeyboardShown = true
                                    if (!isPortrait && !currentContext.isTelevision() && !phoneChatOverlayEnabled(currentContext)) {
                                        currentBinding.chatLayout.updateLayoutParams { width = (currentBinding.slidingLayout.width / 1.8f).toInt() }
                                        showStatusBar()
                                    }
                                }
                            } else {
                                if (isKeyboardShown) {
                                    isKeyboardShown = false
                                    currentBinding.chatLayout.clearFocus()
                                    if (!isPortrait && !currentContext.isTelevision() && !phoneChatOverlayEnabled(currentContext)) {
                                        currentBinding.chatLayout.updateLayoutParams { width = effectiveLandscapeChatWidth() }
                                        if (isMaximized) {
                                            hideStatusBar()
                                        }
                                    }
                                }
                            }
                        }
                        slidingLayout.viewTreeObserver.addOnGlobalLayoutListener(keyboardLayoutListener)
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.stream.collectLatest { stream ->
                                if (stream != null) {
                                    startStreamUptimeTicker()
                                    if (requireContext().prefs().getBoolean(C.PLAYER_CHANNEL, true)) {
                                        stream.channelImage?.takeIf { it.isNotBlank() }?.let(::updateChannelAvatar)
                                    }
                                    stream.id?.let {
                                        playbackService?.streamId = it
                                        chatFragment?.updateStreamId(it)
                                    }
                                    if (!requireContext().prefs().isChatEnabled() ||
                                        false ||
                                        viewersText.text.isNullOrBlank()
                                    ) {
                                        updateViewerCount(stream.viewerCount)
                                    }
                                    // Keep the recorder's attribution in sync
                                    // with refreshed stream metadata even when
                                    // the title/category views are already
                                    // populated.
                                    updateStreamInfo(stream.title, stream.gameId, stream.gameSlug, stream.gameName)
                                    if (isLiveRewindEnabled() &&
                                        playbackService?.type == BasePlaybackService.STREAM &&
                                        liveRewindStreamCreatedAt.isNullOrBlank() &&
                                        !stream.createdAt.isNullOrBlank()
                                    ) {
                                        // The playback service can bind before stream metadata is
                                        // loaded. Start the first recording lookup as soon as the
                                        // stream's creation time becomes available.
                                        prepareLiveRewind(stream.id, stream.createdAt)
                                        return@collectLatest
                                    }
                                    if (isLiveRewindEnabled() &&
                                        playbackService?.type == BasePlaybackService.STREAM &&
                                        hasLiveStreamSessionChanged(
                                            oldId = liveRewindStreamId,
                                            oldCreatedAt = liveRewindStreamCreatedAt,
                                            newId = stream.id,
                                            newCreatedAt = stream.createdAt,
                                        ) && pendingLiveSession != LiveRewindSession(stream.id, stream.createdAt)
                                    ) {
                                        onLiveStreamSessionChanged(stream.id, stream.createdAt)
                                        return@collectLatest
                                    }
                                    if (isLiveRewindAvailable()) {
                                        updateLiveRewindProgress()
                                    }
                                } else if (shouldMarkLiveStreamOffline(
                                        viewModel.streamStatusKnown.value,
                                        liveRewindStreamWasLive || streamUptimeWasLive,
                                        false,
                                    )) {
                                    onLiveStreamWentOffline()
                                }
                            }
                        }
                    }
                    restart.visibility = View.VISIBLE
                    restart.setOnClickListener {
                        showController(force = true)
                        restartPlayer()
                    }
                    seekLive.visibility = View.VISIBLE
                    seekLive.setOnClickListener {
                        showController(force = true)
                        pausedLivePositionMs = null
                        seekToLivePosition()
                    }
                    viewersLayout.apply {
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            showController(force = true)
                            openViewerList()
                        }
                    }
                    val showLiveTransport = requireContext().isTelevision() || isLiveRewindAvailable()
                    rewind.visibility = if (showLiveTransport) View.VISIBLE else View.GONE
                    fastForward.visibility = if (showLiveTransport) View.VISIBLE else View.GONE
                    position.visibility = View.GONE
                    progressBar.visibility = View.GONE
                    duration.visibility = View.GONE
                    updateStreamInfo(
                        playbackService?.title,
                        playbackService?.gameId,
                        playbackService?.gameSlug,
                        playbackService?.gameName
                    )
                    updateViewerCount(playbackService?.viewerCount)
                    startStreamUptimeTicker()
                } else {
                    speed.visibility = View.VISIBLE
                    speed.setOnClickListener {
                        showController(force = true)
                        showSpeedDialog()
                    }
                }
                if (playbackService?.type == BasePlaybackService.VIDEO) {
                    if (requireContext().prefs().getBoolean(C.PLAYER_MENU_BOOKMARK, true)) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.isBookmarked.collectLatest {
                                    if (it != null) {
                                        (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setBookmarkText(it)
                                        viewModel.isBookmarked.value = null
                                    }
                                }
                            }
                        }
                    }
                    if (!playbackService?.videoId.isNullOrBlank() && (requireContext().prefs().getBoolean(C.PLAYER_GAMES_BUTTON, true) || requireContext().prefs().getBoolean(C.PLAYER_MENU_GAMES, false))) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.gamesList.collectLatest { list ->
                                    if (!list.isNullOrEmpty()) {
                                        if (requireContext().prefs().getBoolean(C.PLAYER_GAMES_BUTTON, true)) {
                                            vodGames.visibility = View.VISIBLE
                                            vodGames.setOnClickListener {
                                                showController(force = true)
                                                showVodGames()
                                            }
                                        } else {
                                            vodGames.setOnClickListener(null)
                                            vodGames.visibility = View.GONE
                                        }
                                        (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setVodGames()
                                    } else {
                                        vodGames.setOnClickListener(null)
                                        vodGames.visibility = View.GONE
                                    }
                                }
                            }
                        }
                    }
                }
                if (playbackService?.type == BasePlaybackService.CLIP) {
                    val videoId = playbackService?.videoId
                    if (!videoId.isNullOrBlank()) {
                        binding.watchVideo.visibility = View.VISIBLE
                        binding.watchVideo.setOnClickListener {
                            viewLifecycleOwner.lifecycleScope.launch {
                                val offset = playbackService?.videoOffsetSeconds?.let {
                                    (it * 1000) + (getCurrentPosition() ?: 0)
                                } ?: 0
                                (requireActivity() as MainActivity).startVideo(
                                    Video(
                                        id = videoId,
                                        channelId = playbackService?.channelId,
                                        channelLogin = playbackService?.channelLogin,
                                        channelName = playbackService?.channelName,
                                        channelImageURL = playbackService?.channelImage,
                                        createdAt = playbackService?.videoCreatedAt,
                                        animatedPreviewURL = playbackService?.videoAnimatedPreviewURL,
                                    ),
                                    offset,
                                    true
                                )
                            }
                        }
                    }
                } else {
                    sleepTimer.visibility = View.VISIBLE
                    sleepTimer.setOnClickListener {
                        showController(force = true)
                        showSleepTimerDialog()
                    }
                }
                if (playbackService?.type != BasePlaybackService.OFFLINE_VIDEO) {
                    if (playbackService?.loaded == true) {
                        quality.isEnabled = true
                        setQualityButtonColor(Color.WHITE)
                        download.isEnabled = true
                        download.setColorFilter(Color.WHITE)
                        audioOnly.isEnabled = true
                        audioOnly.setColorFilter(Color.WHITE)
                        setQualityText()
                    } else {
                        quality.isEnabled = false
                        setQualityButtonColor(Color.GRAY)
                        download.isEnabled = false
                        download.setColorFilter(Color.GRAY)
                        audioOnly.isEnabled = false
                        audioOnly.setColorFilter(Color.GRAY)
                    }
                    download.visibility = View.VISIBLE
                    download.setOnClickListener {
                        showController(force = true)
                        showDownloadDialog()
                    }
                    val setting = requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0
                    if (setting == 0 || setting == 1) {
                        follow.visibility = View.VISIBLE
                        follow.setOnClickListener {
                            showController(force = true)
                            viewModel.isFollowing.value?.let {
                                if (it) {
                                    requireContext().getAlertDialogBuilder()
                                        .setMessage(getString(R.string.unfollow_channel, displayName))
                                        .setNegativeButton(getString(R.string.no), null)
                                        .setPositiveButton(getString(R.string.yes)) { _, _ ->
                                            viewModel.deleteFollowChannel(
                                                requireContext().tokenPrefs().getString(C.USER_ID, null),
                                                playbackService?.channelId,
                                                setting,
                                                requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                                TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                            )
                                        }
                                        .show()
                                } else {
                                    viewModel.saveFollowChannel(
                                        requireContext().tokenPrefs().getString(C.USER_ID, null),
                                        playbackService?.channelId,
                                        playbackService?.channelLogin,
                                        playbackService?.channelName,
                                        setting,
                                        requireContext().prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false),
                                        !requireContext().prefs().getBoolean(C.UI_ACTIVATE_NOTIFICATIONS_WHEN_FOLLOWING, true),
                                        playbackService?.createdAt,
                                        requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                        TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                    )
                                }
                            }
                        }
                        viewLifecycleOwner.lifecycleScope.launch {
                            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.authenticationRequired.collect {
                                    requestTwitchReauthorization()
                                }
                            }
                        }
                        viewLifecycleOwner.lifecycleScope.launch {
                            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.isFollowing.collectLatest {
                                    if (it != null) {
                                        if (it) {
                                            follow.setImageResource(R.drawable.baseline_favorite_black_24)
                                            follow.contentDescription = getString(R.string.player_unfollow)
                                        } else {
                                            follow.setImageResource(R.drawable.baseline_favorite_border_black_24)
                                            follow.contentDescription = getString(R.string.player_follow)
                                        }
                                    }
                                }
                            }
                        }
                        viewLifecycleOwner.lifecycleScope.launch {
                            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.follow.collectLatest { pair ->
                                    if (pair != null) {
                                        val following = pair.first
                                        val errorMessage = pair.second
                                        if (!errorMessage.isNullOrBlank()) {
                                            Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_SHORT).show()
                                        } else {
                                            if (following) {
                                                Toast.makeText(requireContext(), getString(R.string.now_following, displayName), Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(requireContext(), getString(R.string.unfollowed, displayName), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        viewModel.follow.value = null
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val currentChatFragment = (childFragmentManager.findFragmentById(R.id.chatFragmentContainer) as? ChatFragment)
            if (currentChatFragment != null) {
                chatFragment = currentChatFragment
            } else {
                val fragment = when (playbackService?.type) {
                    BasePlaybackService.STREAM -> ChatFragment.newInstance(
                        playbackService?.channelId,
                        playbackService?.channelLogin,
                        playbackService?.channelName,
                        playbackService?.streamId,
                    )
                    BasePlaybackService.VIDEO -> ChatFragment.newInstance(
                        playbackService?.channelId,
                        playbackService?.channelLogin,
                        playbackService?.videoId,
                        playbackService?.createdAt,
                        0,
                    )
                    BasePlaybackService.CLIP -> ChatFragment.newInstance(
                        playbackService?.channelId,
                        playbackService?.channelLogin,
                        playbackService?.videoId,
                        playbackService?.videoCreatedAt,
                        playbackService?.videoOffsetSeconds.takeIf { it != -1 },
                    )
                    BasePlaybackService.OFFLINE_VIDEO -> ChatFragment.newLocalInstance(
                        playbackService?.channelId,
                        playbackService?.channelLogin,
                        playbackService?.videoCreatedAt ?: playbackService?.createdAt?.takeIf { playbackService?.clipId == null },
                        playbackService?.chatUrl,
                    )
                    else -> null
                }
                if (fragment != null) {
                    childFragmentManager.beginTransaction().replace(R.id.chatFragmentContainer, fragment).commit()
                }
                chatFragment = fragment
            }
            refreshPlayerControls()
            applyHudLayout()
            if (isInteractionLocked) {
                setInteractionLocked(true, force = true)
            }
            view?.post {
                if (isInteractionLocked && view != null) {
                    updateInteractionLockBackCallback()
                }
            }
        }
    }

    private fun initLayout() {
        with(binding) {
            if (isPortrait) {
                requireActivity().window.decorView.setOnSystemUiVisibilityChangeListener(null)
                showStatusBar()
                phoneChatOverlayGesture?.setActive(false)
                resetPhoneChatOverlayPresentation(chatLayout, phoneChatOverlayHandle)
                resetPhoneChatOverlayLayout(
                    chatLayout,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.BOTTOM,
                )
                playerLayout.isPortrait = true
                chatLayout.isPortrait = true
                playerLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                    marginEnd = 0
                }
                if (isMaximized) {
                    setChatLayoutVisibility(View.VISIBLE)
                } else {
                    setChatLayoutVisibility(View.GONE)
                    val (minimizedScaleX, minimizedScaleY) = getScaleValues()
                    slidingLayout.scaleX = minimizedScaleX
                    slidingLayout.scaleY = minimizedScaleY
                    slidingLayout.doOnPreDraw {
                        val (minimizedScaleX, minimizedScaleY) = getScaleValues()
                        val windowInsets = ViewCompat.getRootWindowInsets(requireView())
                        val insets = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                        val keyboardInsets = windowInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom?.let { if (it > 0) it - (insets?.bottom ?: 0) else it } ?: 0
                        val playerHeight = (slidingLayout.width / (16f / 9f)).toInt()
                        val scaledXDiff = (slidingLayout.width * (1f - minimizedScaleX)) / 2
                        val scaledYDiff = (slidingLayout.height * (1f - minimizedScaleY)) / 2
                        val navBarHeight = requireView().rootView.findViewById<LinearLayout>(R.id.navBarContainer)?.height?.takeIf { it > 0 }?.let { it - keyboardInsets } ?: (insets?.bottom ?: 0)
                        val newX = slidingLayout.width - (insets?.right ?: 0) - (slidingLayout.width * minimizedScaleX) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20F, resources.displayMetrics) * minimizedScaleX)
                        val newY = slidingLayout.height - navBarHeight - (playerHeight * minimizedScaleY) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 30F, resources.displayMetrics) * minimizedScaleY)
                        slidingLayout.translationX = 0f - scaledXDiff - ((insets?.left ?: 0) * minimizedScaleX) + newX
                        slidingLayout.translationY = 0f - scaledYDiff - ((insets?.top ?: 0) * minimizedScaleY) + newY
                        applyMinimizedDismissButtonTransform()
                    }
                }
                aspectRatioFrameLayout.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                with(playerControls) {
                    if (requireContext().prefs().getBoolean(C.PLAYER_FULLSCREEN, true)) {
                        fullscreen.visibility = View.VISIBLE
                        fullscreen.setImageResource(R.drawable.baseline_fullscreen_black_24)
                        fullscreen.contentDescription = getString(R.string.player_enter_fullscreen)
                        fullscreen.setOnClickListener {
                            showController(force = true)
                            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                    } else {
                        fullscreen.setOnClickListener(null)
                        fullscreen.visibility = View.GONE
                    }
                    aspectRatio.visibility = View.GONE
                    aspectRatio.setOnClickListener(null)
                    if (requireContext().prefs().getBoolean(C.PLAYER_CHAT_TOGGLE, true) &&
                        requireContext().prefs().isChatEnabled()
                    ) {
                        toggleChat.visibility = View.VISIBLE
                        if (isChatOpen) {
                            toggleChat.setImageResource(R.drawable.baseline_speaker_notes_off_black_24)
                            toggleChat.contentDescription = getString(R.string.player_hide_chat)
                            toggleChat.setOnClickListener {
                                showController(force = true)
                                hideChat()
                            }
                        } else {
                            toggleChat.setImageResource(R.drawable.baseline_speaker_notes_black_24)
                            toggleChat.contentDescription = getString(R.string.player_show_chat)
                            toggleChat.setOnClickListener {
                                showController(force = true)
                                showChat()
                            }
                        }
                    } else {
                        toggleChat.setOnClickListener(null)
                        toggleChat.visibility = View.GONE
                    }
                }
            } else {
                requireActivity().window.decorView.setOnSystemUiVisibilityChangeListener {
                    if (!isKeyboardShown && isMaximized && activity != null) {
                        hideStatusBar()
                    }
                }
                if (isMaximized) {
                    hideStatusBar()
                    val phoneOverlay = phoneChatOverlayEnabled(requireContext())
                    val chatWidth = if (isChatOpen && !phoneOverlay) effectiveLandscapeChatWidth() else 0
                    playerLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                        width = ViewGroup.LayoutParams.MATCH_PARENT
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        marginEnd = if (phoneOverlay) 0 else chatWidth
                    }
                    chatLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                        width = if (phoneOverlay) ViewGroup.LayoutParams.MATCH_PARENT else chatWidth
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        gravity = if (phoneOverlay) Gravity.TOP or Gravity.START else Gravity.END
                        leftMargin = 0
                        topMargin = 0
                        rightMargin = 0
                        bottomMargin = 0
                    }
                    if (!phoneOverlay) {
                        slidingLayout.doOnLayout {
                            if (!phoneChatOverlayEnabled(requireContext()) && !isPortrait && isMaximized && isChatOpen) {
                                val effectiveChatWidth = effectiveLandscapeChatWidth()
                                playerLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                                    marginEnd = effectiveChatWidth
                                }
                                chatLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                                    width = effectiveChatWidth
                                    leftMargin = 0
                                    topMargin = 0
                                    rightMargin = 0
                                    bottomMargin = 0
                                }
                            }
                        }
                    }
                    if (isChatOpen) {
                        setChatLayoutVisibility(View.VISIBLE)
                        if (requireView().findViewById<Button>(R.id.btnDown)?.isVisible == false) {
                            requireView().findViewById<RecyclerView>(R.id.recyclerView)?.let { recyclerView ->
                                recyclerView.adapter?.itemCount?.let { recyclerView.scrollToPosition(it - 1) }
                            }
                        }
                    } else {
                        setChatLayoutVisibility(View.GONE)
                    }
                } else {
                    showStatusBar()
                    playerLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                        width = ViewGroup.LayoutParams.MATCH_PARENT
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        marginEnd = 0
                    }
                    chatLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                        width = chatWidthLandscape
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        gravity = Gravity.END
                        leftMargin = 0
                        topMargin = 0
                        rightMargin = 0
                        bottomMargin = 0
                    }
                    setChatLayoutVisibility(View.GONE)
                    val (minimizedScaleX, minimizedScaleY) = getScaleValues()
                    slidingLayout.scaleX = minimizedScaleX
                    slidingLayout.scaleY = minimizedScaleY
                    slidingLayout.doOnPreDraw {
                        val (minimizedScaleX, minimizedScaleY) = getScaleValues()
                        val windowInsets = ViewCompat.getRootWindowInsets(requireView())
                        val insets = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                        val keyboardInsets = windowInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom?.let { if (it > 0) it - (insets?.bottom ?: 0) else it } ?: 0
                        val playerWidth = slidingLayout.width - getHorizontalInsets(windowInsets)
                        val scaledXDiff = (slidingLayout.width * (1f - minimizedScaleX)) / 2
                        val scaledYDiff = (slidingLayout.height * (1f - minimizedScaleY)) / 2
                        val navBarHeight = requireView().rootView.findViewById<LinearLayout>(R.id.navBarContainer)?.height?.takeIf { it > 0 }?.let { it - keyboardInsets } ?: (insets?.bottom ?: 0)
                        val newX = slidingLayout.width - (insets?.right ?: 0) - (playerWidth * minimizedScaleX) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20F, resources.displayMetrics) * minimizedScaleX)
                        val newY = slidingLayout.height - navBarHeight - (slidingLayout.height * minimizedScaleY) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 30F, resources.displayMetrics) * minimizedScaleY)
                        slidingLayout.translationX = 0f - scaledXDiff - ((insets?.left ?: 0) * minimizedScaleX) + newX
                        slidingLayout.translationY = 0f - scaledYDiff - ((insets?.top ?: 0) * minimizedScaleY) + newY
                        applyMinimizedDismissButtonTransform()
                    }
                }
                aspectRatioFrameLayout.resizeMode = resizeMode
                playerLayout.isPortrait = false
                chatLayout.isPortrait = false
                with(playerControls) {
                    if (requireContext().prefs().getBoolean(C.PLAYER_FULLSCREEN, true)) {
                        fullscreen.visibility = View.VISIBLE
                        fullscreen.setImageResource(R.drawable.baseline_fullscreen_exit_black_24)
                        fullscreen.contentDescription = getString(R.string.player_exit_fullscreen)
                        fullscreen.setOnClickListener {
                            showController(force = true)
                            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                    } else {
                        fullscreen.setOnClickListener(null)
                        fullscreen.visibility = View.GONE
                    }
                    if (requireContext().prefs().getBoolean(C.PLAYER_ASPECT, true)) {
                        aspectRatio.visibility = View.VISIBLE
                        aspectRatio.setOnClickListener {
                            showController(force = true)
                            setResizeMode()
                        }
                    } else {
                        aspectRatio.setOnClickListener(null)
                        aspectRatio.visibility = View.GONE
                    }
                    if (requireContext().prefs().getBoolean(C.PLAYER_CHAT_TOGGLE, true) && requireContext().prefs().isChatEnabled()) {
                        toggleChat.visibility = View.VISIBLE
                        if (isChatOpen) {
                            toggleChat.setImageResource(R.drawable.baseline_speaker_notes_off_black_24)
                            toggleChat.contentDescription = getString(R.string.player_hide_chat)
                            toggleChat.setOnClickListener {
                                showController(force = true)
                                hideChat()
                            }
                        } else {
                            toggleChat.setImageResource(R.drawable.baseline_speaker_notes_black_24)
                            toggleChat.contentDescription = getString(R.string.player_show_chat)
                            toggleChat.setOnClickListener {
                                showController(force = true)
                                showChat()
                            }
                        }
                    } else {
                        toggleChat.setOnClickListener(null)
                        toggleChat.visibility = View.GONE
                    }
                }
            }
            if (requireContext().isTelevision() && !isPortrait) {
                applyTvChatPresentation(chatLayout, playerLayout, slidingLayout, isChatOpen)
            } else if (!isPortrait) {
                applyPhoneChatPresentation(isChatOpen)
            }
        }
    }

    private fun configureTvPlayerActionFocus() {
        hideTvSecondaryActions()
        val visible: (View) -> Boolean = { view ->
            if (view.visibility == View.VISIBLE && view.isEnabled && binding.playerControls.root.isElementActive(view)) {
                TvFocusHelper.install(view)
                view.isFocusable = true
                view.isFocusableInTouchMode = false
                true
            } else {
                false
            }
        }
        val liveTimeGroup = binding.playerControls.liveTimeGroup.takeIf {
            it.isClickable && it.isFocusable && it.visibility == View.VISIBLE
        }
        val candidates = (listOf(
            binding.playerControls.rewind,
            binding.playerControls.playPause,
            binding.playerControls.fastForward,
            binding.playerControls.download,
            binding.playerControls.follow,
            binding.playerControls.sleepTimer,
            binding.playerControls.aspectRatio,
            binding.playerControls.speed,
            binding.playerControls.quality,
            binding.playerControls.menu,
            binding.playerControls.restart,
            binding.playerControls.seekLive,
            binding.playerControls.clip,
            binding.playerControls.vodGames,
            binding.playerControls.volume,
            binding.playerControls.audioCompressor,
            binding.playerControls.audioOnly,
            binding.playerControls.liveCaptions,
            binding.playerControls.subtitles,
            binding.playerControls.toggleChatInput,
            binding.playerControls.toggleChat,
            binding.playerControls.fullscreen,
        ) + listOfNotNull(liveTimeGroup)).filter(visible)
        TvFocusHelper.linkVisualFocus(binding.playerControls.root, candidates)
    }

    private fun applyHudLayout() {
        binding.playerControls.root.refreshAvailability()
        hideTvSecondaryActions()
        refreshHudLayout()
    }

    private fun hideTvSecondaryActions() {
        binding.playerControls.root.refreshAvailability()
    }

    private fun refreshPlayerControls() {
        if (!viewModel.gamesList.value.isNullOrEmpty()) {
            binding.playerControls.vodGames.setOnClickListener {
                showController(force = true)
                showVodGames()
            }
        } else {
            binding.playerControls.vodGames.setOnClickListener(null)
            binding.playerControls.vodGames.visibility = View.GONE
        }
        binding.playerControls.root.refreshAvailability()
    }

    private fun refreshHudLayout() {
        binding.playerControls.root.setHudOrientation(
            if (isPortrait) HudOrientation.PORTRAIT else HudOrientation.LANDSCAPE,
        )
        binding.playerControls.root.refreshAvailability()
    }

    fun canShowHudActionInOverflow(id: HudElementId): Boolean =
        binding.playerControls.root.canShowInOverflow(id)

    fun hudActionContentDescription(id: HudElementId): CharSequence? =
        binding.playerControls.root.actionContentDescription(id)

    fun isHudActionEnabled(id: HudElementId): Boolean =
        binding.playerControls.root.isActionEnabled(id)

    fun performHudAction(id: HudElementId): Boolean =
        binding.playerControls.root.performAction(id)

    fun reloadHudLayoutFromSettings() {
        if (!isAdded || view == null) return
        binding.playerControls.root.reloadProfile()
        binding.playerControls.root.refreshAvailability()
        binding.playerControls.root.requestLayout()
    }

    fun setResizeMode() {
        resizeMode = (resizeMode + 1).let { if (it < 5) it else 0 }
        binding.aspectRatioFrameLayout.resizeMode = resizeMode
        if (!isPortrait && isMaximized && isChatOpen) {
            val phoneOverlay = phoneChatOverlayEnabled(requireContext())
            val chatWidth = if (phoneOverlay) 0 else effectiveLandscapeChatWidth()
            binding.playerLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                marginEnd = if (phoneOverlay) 0 else chatWidth
            }
            binding.chatLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                width = if (phoneOverlay) ViewGroup.LayoutParams.MATCH_PARENT else chatWidth
                gravity = if (phoneOverlay) Gravity.TOP or Gravity.START else Gravity.END
                leftMargin = 0
                topMargin = 0
                rightMargin = 0
                bottomMargin = 0
            }
            if (phoneOverlay) applyPhoneChatPresentation(true)
        }
        requireContext().prefs().edit { putInt(C.ASPECT_RATIO_LANDSCAPE, resizeMode) }
    }

    fun showSleepTimerDialog() {
        SleepTimerDialog.newInstance((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0).show(childFragmentManager, null)
    }

    fun getQualities(): List<Pair<String, VideoQuality>>? {
        val qualities = playbackService?.qualities
        return if (!qualities.isNullOrEmpty()) {
            val hideCodecs = qualities.all {
                val codec = it.codecs?.substringBefore('.')
                codec == "avc1" || codec == "mp4a" || codec.isNullOrBlank()
            }
            qualities.map { quality ->
                when (quality.name) {
                    BasePlaybackService.AUTO_QUALITY -> getString(R.string.auto)
                    BasePlaybackService.SOURCE_QUALITY -> getString(R.string.source)
                    BasePlaybackService.AUDIO_ONLY_QUALITY -> getString(R.string.audio_only)
                    BasePlaybackService.CHAT_ONLY_QUALITY -> getString(R.string.chat_only)
                    else -> {
                        if (hideCodecs) {
                            quality.name.toString()
                        } else {
                            val codec = quality.codecs?.substringBefore('.')
                            val codecName = when {
                                codec == "av01" -> "AV1"
                                codec == "hev1" || codec == "hvc1" -> "H.265"
                                codec == "avc1" || codec.isNullOrBlank() -> "H.264"
                                else -> codec
                            }
                            "${quality.name} $codecName"
                        }
                    }
                } to quality
            }
        } else null
    }

    fun showQualityDialog() {
        val qualities = getQualities()
        if (!qualities.isNullOrEmpty()) {
            RadioButtonDialogFragment.newInstance(
                REQUEST_CODE_QUALITY,
                qualities.map { it.first },
                qualities.map { it.second.name.toString() }.toTypedArray(),
                qualities.map { it.second.url.toString() }.toTypedArray(),
                qualities.indexOf(qualities.find { it.second.name == playbackService?.quality?.name && it.second.url == playbackService?.quality?.url })
            ).show(childFragmentManager, "closeOnPip")
        }
    }

    fun showSpeedDialog() {
        val speed = getCurrentSpeed()
        if (speed != null) {
            val speedList = requireContext().prefs().getString(C.PLAYER_SPEED_LIST, "0.25\n0.5\n0.75\n1.0\n1.25\n1.5\n1.75\n2.0\n3.0\n4.0\n8.0")?.split("\n")
            if (speedList != null) {
                RadioButtonDialogFragment.newInstance(
                    REQUEST_CODE_SPEED,
                    speedList,
                    checkedIndex = speedList.indexOf(speed.toString())
                ).show(childFragmentManager, "closeOnPip")
            }
        }
    }

    fun showVolumeDialog() {
        PlayerVolumeDialog.newInstance(getCurrentVolume()).show(childFragmentManager, "closeOnPip")
    }

    fun getTranslateAllMessages(): Boolean? {
        return if (!playbackService?.channelId.isNullOrBlank()) {
            chatFragment?.getTranslateAllMessages()
        } else null
    }

    fun saveTranslatedChannel() {
        playbackService?.channelId?.let {
            chatFragment?.saveTranslatedChannel(it)
        }
    }

    fun deleteTranslatedChannel() {
        playbackService?.channelId?.let {
            chatFragment?.deleteTranslatedChannel(it)
        }
    }

    fun toggleChatBar() {
        with(binding) {
            requireView().findViewById<LinearLayout>(R.id.messageView)?.let {
                if (it.isVisible) {
                    (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(chatLayout.windowToken, 0)
                    chatLayout.clearFocus()
                    if (playbackService?.type == BasePlaybackService.STREAM && chatFragment?.emoteMenuIsVisible() == true) {
                        chatFragment?.toggleEmoteMenu(false)
                    }
                    it.visibility = View.GONE
                    requireContext().prefs().edit { putBoolean(C.KEY_CHAT_BAR_VISIBLE, false) }
                } else {
                    it.visibility = View.VISIBLE
                    requireContext().prefs().edit { putBoolean(C.KEY_CHAT_BAR_VISIBLE, true) }
                }
            }
        }
    }

    fun hideChat() {
        isChatOpen = false
        hideChatLayout()
        if (requireContext().prefs().getBoolean(C.PLAYER_CHAT_TOGGLE, true)) {
            binding.playerControls.toggleChat.apply {
                visibility = View.VISIBLE
                setImageResource(R.drawable.baseline_speaker_notes_black_24)
                contentDescription = getString(R.string.player_show_chat)
                setOnClickListener {
                    showController(force = true)
                    showChat()
                }
            }
        }
        requireContext().prefs().edit { putBoolean(C.KEY_CHAT_OPENED, false) }
    }

    fun showChat() {
        isChatOpen = true
        showChatLayout()
        if (requireContext().prefs().getBoolean(C.PLAYER_CHAT_TOGGLE, true)) {
            binding.playerControls.toggleChat.apply {
                visibility = View.VISIBLE
                setImageResource(R.drawable.baseline_speaker_notes_off_black_24)
                contentDescription = getString(R.string.player_hide_chat)
                setOnClickListener {
                    showController(force = true)
                    hideChat()
                }
            }
        }
        requireContext().prefs().edit { putBoolean(C.KEY_CHAT_OPENED, true) }
        if (requireView().findViewById<Button>(R.id.btnDown)?.isVisible == false) {
            requireView().findViewById<RecyclerView>(R.id.recyclerView)?.let { recyclerView ->
                recyclerView.adapter?.itemCount?.let { recyclerView.scrollToPosition(it - 1) }
            }
        }
    }

    private fun hideChatLayout() {
        with(binding) {
            playerLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
                marginEnd = 0
            }
            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(chatLayout.windowToken, 0)
            chatLayout.clearFocus()
            setChatLayoutVisibility(View.GONE)
            if (requireContext().isTelevision()) {
                applyTvChatPresentation(chatLayout, playerLayout, slidingLayout, false)
            } else if (!isPortrait) {
                applyPhoneChatPresentation(false)
            }
        }
    }

    private fun setChatLayoutVisibility(visibility: Int) {
        binding.chatLayout.visibility = visibility
        chatFragment?.setV2RendererVisible(visibility == View.VISIBLE)
    }

    private fun showChatLayout() {
        with(binding) {
            val phoneOverlay = !isPortrait && isMaximized && phoneChatOverlayEnabled(requireContext())
            val chatWidth = when {
                phoneOverlay -> 0
                isPortrait -> ViewGroup.LayoutParams.MATCH_PARENT
                else -> effectiveLandscapeChatWidth()
            }
            val chatGravity = when {
                phoneOverlay -> Gravity.TOP or Gravity.START
                isPortrait -> Gravity.BOTTOM
                else -> Gravity.END
            }
            playerLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
                marginEnd = if (phoneOverlay || isPortrait) 0 else chatWidth
            }
            chatLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                width = if (phoneOverlay) ViewGroup.LayoutParams.MATCH_PARENT else chatWidth
                height = ViewGroup.LayoutParams.MATCH_PARENT
                gravity = chatGravity
                leftMargin = 0
                topMargin = 0
                rightMargin = 0
                bottomMargin = 0
            }
            if (!phoneOverlay) {
                resetPhoneChatOverlayPresentation(chatLayout, phoneChatOverlayHandle)
                chatLayout.isPortrait = isPortrait
                phoneChatOverlayGesture?.setActive(false)
            }
            setChatLayoutVisibility(View.VISIBLE)
            if (requireContext().isTelevision()) {
                applyTvChatPresentation(chatLayout, playerLayout, slidingLayout, true)
            } else if (!isPortrait) {
                applyPhoneChatPresentation(true)
            }
        }
    }

    private fun applyPhoneChatPresentation(visible: Boolean) {
        val overlayMode = !isPortrait && isMaximized && phoneChatOverlayEnabled(requireContext())
        if (!overlayMode) {
            phoneChatOverlayGesture?.setActive(false)
            resetPhoneChatOverlayPresentation(binding.chatLayout, binding.phoneChatOverlayHandle)
            if (visible && isMaximized && !isPortrait) {
                val chatWidth = effectiveLandscapeChatWidth()
                binding.playerLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                    marginEnd = chatWidth
                }
                binding.chatLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                    width = chatWidth
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                    gravity = Gravity.END
                    leftMargin = 0
                    topMargin = 0
                    rightMargin = 0
                    bottomMargin = 0
                }
            }
            return
        }
        val applied = applyPhoneChatOverlayPresentation(
            chat = binding.chatLayout,
            player = binding.playerLayout,
            parent = binding.slidingLayout,
            dragHandle = binding.phoneChatOverlayHandle,
            visible = visible,
            isCurrent = {
                isAdded && view != null && !isPortrait && isMaximized &&
                    phoneChatOverlayEnabled(requireContext()) && isChatOpen == visible
            },
        )
        phoneChatOverlayGesture?.setActive(applied && visible && overlayMode)
    }

    private fun effectiveLandscapeChatWidth(): Int {
        if (chatWidthLandscape <= 0) return 0
        val availableWidth = binding.slidingLayout.width - binding.slidingLayout.paddingLeft - binding.slidingLayout.paddingRight
        if (availableWidth <= 0) return chatWidthLandscape
        val percentage = requireContext().prefs().getInt(C.CHAT_WIDTH_PERCENT, 30)
        return landscapeChatWidthForAvailableWidth(availableWidth, percentage)
    }

    protected fun setQualityButtonColor(color: Int) {
        binding.playerControls.quality.apply {
            setTextColor(color)
            iconTint = ColorStateList.valueOf(color)
        }
    }

    private fun compactQualityLabel(label: String?): String {
        val compact = label
            ?.removeSuffix(" H.264")
            ?.removeSuffix(" H.265")
            ?.removeSuffix(" AV1")
            ?.takeIf { it.isNotBlank() }
            ?: return getString(R.string.auto)
        // Keep the resolution/fps visible in the fixed-width HUD pill. The
        // full codec/source label remains available in contentDescription and
        // in the quality dialog.
        return if (compact.firstOrNull()?.isDigit() == true && 'p' in compact) {
            compact.substringBefore(' ')
        } else {
            compact
        }
    }

    private fun isVaftActive(): Boolean {
        return (playbackService as? ExoPlayerService)?.vaftActive == true
    }

    fun setQualityText() {
        val label = getQualities()?.find { it.second == playbackService?.quality }?.first
        if (view != null) {
            val vaftActive = isVaftActive()
            binding.playerControls.quality.apply {
                text = if (vaftActive) {
                    getString(R.string.avoid_twitch_ads).substringBefore(' ')
                } else {
                    compactQualityLabel(label)
                }
                contentDescription = if (vaftActive) {
                    getString(R.string.waiting_ads)
                } else {
                    label ?: getString(R.string.player_quality)
                }
            }
        }
        (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.let { dialog ->
            dialog.setQuality(label)
        }
    }

    fun reapplyNetworkDefaultQuality(cellular: Boolean) {
        val service = playbackService ?: return

        // This feature is specifically for a live stream handoff.
        // Do not unexpectedly change VOD/clip/offline playback.
        if (service.type != BasePlaybackService.STREAM) {
            return
        }

        if (service.qualities.isNullOrEmpty()) {
            // If qualities are not loaded yet, the normal startup path will call
            // setDefaultQuality() once they become available.
            return
        }

        val target = service.resolveDefaultQualityForNetwork(cellular) ?: return

        if (service.quality?.name == target.name &&
            service.quality?.url == target.url
        ) {
            return
        }

        changeQuality(target, persistSavedQuality = false)
        setQualityText()
    }

    fun updateViewerCount(viewerCount: Int?) {
        with(binding.playerControls) {
            if (viewerCount != null) {
                val formattedCount = TwitchApiHelper.formatCount(viewerCount, compact = false)
                viewersText.text = getString(R.string.player_viewers_suffix, formattedCount)
                viewersLayout.visibility = View.VISIBLE
                titleAndViewersLayout.visibility = View.VISIBLE
                viewersLayout.contentDescription = getString(R.string.player_viewers, viewersText.text)
            } else {
                viewersText.text = null
                viewersLayout.visibility = View.GONE
                viewersLayout.contentDescription = null
                titleAndViewersLayout.visibility = if (
                    title.visibility == View.VISIBLE ||
                        category.visibility == View.VISIBLE
                ) View.VISIBLE else View.GONE
            }
        }
        binding.playerControls.root.refreshAvailabilityIfChanged()
    }

    private fun updateChannelAvatar(url: String?) {
        val normalized = url?.takeIf { it.isNotBlank() }
        if (normalized == null) {
            loadedChannelAvatarUrl = null
            binding.playerControls.channelAvatar.setImageDrawable(null)
            binding.playerControls.channelAvatar.visibility = View.GONE
            binding.playerControls.root.refreshAvailabilityIfChanged()
            return
        }

        binding.playerControls.channelAvatar.visibility = View.VISIBLE
        binding.playerControls.root.refreshAvailabilityIfChanged()
        if (loadedChannelAvatarUrl == normalized) return

        loadedChannelAvatarUrl = normalized
        requireContext().imageLoader.enqueue(
            ImageRequest.Builder(requireContext())
                .data(normalized)
                .crossfade(true)
                .transformations(CircleCropTransformation())
                .listener(
                    onError = { _, _ ->
                        if (loadedChannelAvatarUrl == normalized) {
                            loadedChannelAvatarUrl = null
                        }
                    }
                )
                .target(binding.playerControls.channelAvatar)
                .build()
        )
    }

    fun updateLiveStatus(live: Boolean, serverTime: Long?, channelLogin: String?) {
        if (channelLogin == playbackService?.channelLogin) {
            if (live) {
                if (isLiveRewindEnabled() && playbackService?.type == BasePlaybackService.STREAM) {
                    viewModel.loadStreamInfo(
                        channelId = playbackService?.channelId,
                        channelLogin = playbackService?.channelLogin,
                        viewerCount = null,
                        loop = false,
                        networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                        helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext()),
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                    )
                    startLiveRewindTicker()
                }
                if (playbackService?.type == BasePlaybackService.STREAM) startStreamUptimeTicker()
                restartPlayer()
            } else {
                onLiveStreamWentOffline()
            }
        }
    }

    fun updateStreamInfo(title: String?, gameId: String?, gameSlug: String?, gameName: String?) {
        playbackService?.gameSlug = gameSlug
        playbackService?.updateViewingMetadata(
            categoryId = gameId,
            categoryName = gameName,
            title = title,
        )
        binding.playerControls.title.apply {
            if (!title.isNullOrBlank() && requireContext().prefs().getBoolean(C.PLAYER_TITLE, true)) {
                text = title.trim()
                visibility = View.VISIBLE
            } else {
                text = null
                visibility = View.GONE
            }
        }
        binding.playerControls.category.apply {
            val showCategory = !gameName.isNullOrBlank() && requireContext().prefs().getBoolean(C.PLAYER_CATEGORY, true)
            if (showCategory) {
                text = gameName
                visibility = View.VISIBLE
                isFocusable = true
                contentDescription = getString(R.string.player_open_category, gameName)
                setOnClickListener {
                    findNavController().navigate(GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                        gameId = gameId,
                        gameSlug = gameSlug,
                        gameName = gameName
                    ))
                    minimize()
                }
            } else {
                text = null
                visibility = View.GONE
                isFocusable = false
                setOnClickListener(null)
                contentDescription = null
            }
            binding.playerControls.playingLabel.visibility = if (showCategory) View.VISIBLE else View.GONE
        }
        binding.playerControls.titleAndViewersLayout.visibility = if (
            binding.playerControls.title.visibility == View.VISIBLE ||
                binding.playerControls.category.visibility == View.VISIBLE ||
            binding.playerControls.viewersLayout.visibility == View.VISIBLE
        ) View.VISIBLE else View.GONE
        binding.playerControls.root.refreshAvailability()
    }

    fun openViewerList() {
        playbackService?.channelLogin?.let { login ->
            PlayerViewerListDialog.newInstance(login).show(childFragmentManager, "closeOnPip")
        }
    }

    private fun openChannel() {
        findNavController().navigate(
            ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                channelId = playbackService?.channelId,
                channelLogin = playbackService?.channelLogin,
                channelName = playbackService?.channelName,
            )
        )
        minimize()
    }

    fun showVodGames() {
        viewModel.gamesList.value?.let {
            PlayerGamesDialog.newInstance(it).show(childFragmentManager, "closeOnPip")
        }
    }

    fun checkBookmark() {
        playbackService?.videoId?.let { viewModel.checkBookmark(it) }
    }

    fun saveBookmark() {
        viewModel.saveBookmark(
            filesDir = requireContext().filesDir.path,
            networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext()),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
            videoId = playbackService?.videoId,
            title = playbackService?.title,
            uploadDate = playbackService?.createdAt,
            durationSeconds = playbackService?.durationSeconds,
            type = playbackService?.videoType,
            animatedPreviewUrl = playbackService?.videoAnimatedPreviewURL,
            channelId = playbackService?.channelId,
            channelLogin = playbackService?.channelLogin,
            channelName = playbackService?.channelName,
            channelImage = playbackService?.channelImage,
            thumbnail = playbackService?.thumbnail,
            gameId = playbackService?.gameId,
            gameSlug = playbackService?.gameSlug,
            gameName = playbackService?.gameName,
        )
    }

    fun share() {
        when (playbackService?.type) {
            BasePlaybackService.STREAM -> {
                playbackService?.channelLogin?.let { channelLogin ->
                    startActivity(Intent.createChooser(Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, "https://twitch.tv/${channelLogin}")
                        playbackService?.channelName?.let {
                            putExtra(Intent.EXTRA_TITLE, it)
                        }
                        type = "text/plain"
                    }, null))
                }
            }
            BasePlaybackService.VIDEO -> {
                playbackService?.videoId?.let { videoId ->
                    val position = getCurrentPosition()?.let { position ->
                        val totalSeconds = position / 1000
                        val hours = (totalSeconds / 3600).let { if (it < 10) "0$it" else "$it" }
                        val minutes = ((totalSeconds % 3600) / 60).let { if (it < 10) "0$it" else "$it" }
                        val seconds = (totalSeconds % 60).let { if (it < 10) "0$it" else "$it" }
                        "?t=${hours}h${minutes}m${seconds}s"
                    } ?: ""
                    startActivity(Intent.createChooser(Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, "https://twitch.tv/videos/${videoId}${position}")
                        playbackService?.title?.let {
                            putExtra(Intent.EXTRA_TITLE, it)
                        }
                        type = "text/plain"
                    }, null))
                }
            }
            BasePlaybackService.CLIP -> {
                playbackService?.clipId?.let { clipId ->
                    playbackService?.channelLogin?.let { channelLogin ->
                        startActivity(Intent.createChooser(Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, "https://twitch.tv/${channelLogin}/clip/${clipId}")
                            playbackService?.title?.let {
                                putExtra(Intent.EXTRA_TITLE, it)
                            }
                            type = "text/plain"
                        }, null))
                    }
                }
            }
            BasePlaybackService.OFFLINE_VIDEO -> {
                playbackService?.quality?.url?.let { videoUrl ->
                    val uri = if (videoUrl.endsWith(".m3u8")) {
                        videoUrl.substringBefore("%2F").toUri()
                    } else {
                        videoUrl.toUri()
                    }
                    startActivity(Intent.createChooser(Intent().apply {
                        action = Intent.ACTION_SEND
                        setDataAndType(uri, requireContext().contentResolver.getType(uri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        playbackService?.title?.let {
                            putExtra(Intent.EXTRA_TITLE, it)
                        }
                    }, null))
                }
            }
        }
    }

    fun changePlayerMode() {
        with(binding) {
            if (canEnterPictureInPicture()) {
                val resumeAutoHide = !controllerHideOnTouch && !controllerIsAnimating && controllerAutoHide && !binding.playerControls.progressBar.isPressed
                controllerHideOnTouch = true
                if (resumeAutoHide) hudVisibility.scheduleHide()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
                    requireContext().prefs().getBoolean(C.PLAYER_PICTURE_IN_PICTURE, true)
                ) {
                    setPipActions(pipPlaying, autoEnterEnabled = true)
                }
            } else {
                controllerHideOnTouch = false
                showController(force = true)
                updateProgress()
                requireView().keepScreenOn = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
                ) {
                    setPipActions(pipPlaying, autoEnterEnabled = false)
                }
            }
        }
    }

    protected fun showPlayerError(@StringRes message: Int, retry: (() -> Unit)? = null) {
        binding.playerErrorText.setText(message)
        binding.playerErrorRetry.isVisible = retry != null
        binding.playerErrorRetry.setOnClickListener {
            clearPlayerError()
            retry?.invoke()
        }
        binding.playerErrorContainer.isVisible = true
        binding.playerErrorContainer.isFocusable = retry == null
        if (retry != null) {
            binding.playerErrorRetry.requestFocus()
        } else {
            binding.playerErrorContainer.requestFocus()
        }
    }

    protected fun clearPlayerError() {
        binding.playerErrorContainer.isVisible = false
        binding.playerErrorRetry.setOnClickListener(null)
    }

    private fun setInteractionLocked(
        locked: Boolean,
        force: Boolean = false,
    ) {
        if (!force && isInteractionLocked == locked) {
            return
        }

        val currentBinding = _binding ?: return
        if (locked && !isInteractionLocked) {
            phoneChatOverlayGesture?.cancel()
            if (chatTouchActive) {
                MotionEvent.obtain(
                    SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_CANCEL,
                    0f,
                    0f,
                    0,
                ).also { cancelEvent ->
                    currentBinding.chatLinearLayout.dispatchTouchEvent(cancelEvent)
                    cancelEvent.recycle()
                }
                chatTouchActive = false
            }
            currentBinding.chatLayout.findViewById<RecyclerView>(R.id.recyclerView)?.stopScroll()
        }

        isInteractionLocked = locked
        hudVisibility.setInteractionActive(locked)
        with(currentBinding) {
            playerLayout.interactionUnlockView = playerControls.interactionLock
            playerControls.root.interactionUnlockView = playerControls.interactionLock
            playerControls.root.interactionLocked = locked
            playerLayout.interactionLocked = locked
            playerControls.interactionLock.setImageResource(
                if (locked) {
                    R.drawable.baseline_lock_open_black_24
                } else {
                    R.drawable.baseline_lock_black_24
                }
            )
            playerControls.interactionLock.contentDescription = getString(
                if (locked) {
                    R.string.player_unlock_controls
                } else {
                    R.string.player_lock_controls
                }
            )
            playerControls.interactionLock.isEnabled = true
            playerControls.interactionLock.visibility = if (requireContext().isTelevision()) View.GONE else View.VISIBLE
            playerControls.root.removeCallbacks(controllerHideAction)

            if (locked) {
                (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(chatLayout.windowToken, 0)
                chatLayout.clearFocus()
                showController(force = true)
            } else if (controllerAutoHide && controllerHideOnTouch && !playerControls.progressBar.isPressed) {
                hudVisibility.scheduleHide()
            }
        }

        updateInteractionLockBackCallback()
    }

    private fun updateInteractionLockBackCallback() {
        interactionLockBackCallback?.remove()
        interactionLockBackCallback = null

        if (!isInteractionLocked || _binding == null) {
            return
        }

        interactionLockBackCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Deliberately consume Back while interaction is locked.
            }
        }.also { callback ->
            requireActivity().onBackPressedDispatcher.addCallback(
                viewLifecycleOwner,
                callback,
            )
        }
    }

    private fun toggleController() {
        if (requireContext().isTelevision() || !controllerHideOnTouch || isInteractionLocked) {
            hudVisibility.show(force = true)
            showController(force = true)
            updateProgress()
        } else if (hudVisibility.toggle()) {
            showController()
            updateProgress()
        } else {
            hideController()
        }
    }

    private fun scheduleControllerHide() {
        hudVisibility.setInteractionActive(isInteractionLocked)
        hudVisibility.scheduleHide()
    }

    private fun scheduleControllerHideAfterScrub() {
        binding.playerControls.root.post {
            if (view != null) {
                scheduleControllerHide()
            }
        }
    }

    protected fun showController(show: Boolean = true, force: Boolean = false) {
        if (!useController) return

        if (!show) {
            scheduleControllerHide()
            return
        }
        hudVisibility.setInteractionActive(isInteractionLocked)
        hudVisibility.show(force)
    }

    private fun hideController(force: Boolean = false) {
        if (requireContext().isTelevision() && !force) return

        hudVisibility.hide(force)
    }

    private fun showStatusBar() {
        WindowCompat.getInsetsController(
            requireActivity().window,
            requireActivity().window.decorView
        ).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun hideStatusBar() {
        WindowCompat.getInsetsController(
            requireActivity().window,
            requireActivity().window.decorView
        ).hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun enableBackground() {
        backgroundVisible = true
        binding.playerBackground.setBackgroundColor(
            if (isPortrait) {
                backgroundColor ?: MaterialColors.getColor(binding.playerBackground, com.google.android.material.R.attr.colorSurface).also { backgroundColor = it }
            } else {
                Color.BLACK
            }
        )
        binding.playerBackground.isClickable = true
    }

    private fun disableBackground() {
        backgroundVisible = false
        binding.playerBackground.setBackgroundColor(Color.TRANSPARENT)
        binding.playerBackground.isClickable = false
    }

    private fun getHorizontalInsets(windowInsets: WindowInsetsCompat?): Int {
        return if (windowInsets != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && requireContext().prefs().getBoolean(C.PLAYER_ROUNDED_CORNER_PADDING, false)) {
                val rootWindowInsets = requireView().rootWindowInsets
                val topLeft = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
                val topRight = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
                val bottomLeft = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
                val bottomRight = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
                val leftRadius = max(topLeft?.radius ?: 0, bottomLeft?.radius ?: 0)
                val rightRadius = max(topRight?.radius ?: 0, bottomRight?.radius ?: 0)
                if (requireContext().prefs().getBoolean(C.UI_DRAW_BEHIND_CUTOUTS, false)) {
                    leftRadius + rightRadius
                } else {
                    val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
                    max(cutoutInsets.left, leftRadius) + max(cutoutInsets.right, rightRadius)
                }
            } else {
                if (requireContext().prefs().getBoolean(C.UI_DRAW_BEHIND_CUTOUTS, false)) {
                    0
                } else {
                    val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
                    cutoutInsets.left + cutoutInsets.right
                }
            }
        } else 0
    }

    private fun getScaleValues(): Pair<Float, Float> {
        return if (isPortrait) {
            0.5f to 0.5f
        } else {
            0.3f to 0.325f
        }
    }

    private fun applyMinimizedDismissButtonTransform() {
        if (isMaximized) {
            return
        }

        val (minimizedScaleX, minimizedScaleY) = getScaleValues()
        binding.dismissPlayer.scaleX = 1f / minimizedScaleX
        binding.dismissPlayer.scaleY = 1f / minimizedScaleY

        val layoutParams = binding.dismissPlayer.layoutParams as? FrameLayout.LayoutParams ?: return
        if (binding.dismissPlayer.width == 0 || binding.dismissPlayer.height == 0) {
            return
        }

        val rightOverflow = (binding.dismissPlayer.width * (binding.dismissPlayer.scaleX - 1f) / 2f - layoutParams.marginEnd).coerceAtLeast(0f)
        val topOverflow = (binding.dismissPlayer.height * (binding.dismissPlayer.scaleY - 1f) / 2f - layoutParams.topMargin).coerceAtLeast(0f)
        binding.dismissPlayer.translationX = -rightOverflow
        binding.dismissPlayer.translationY = topOverflow
    }

    fun getIsPortrait() = isPortrait

    fun reloadEmotes() = chatFragment?.reloadEmotes()

    fun isActive() = chatFragment?.isActive()

    fun disconnect() = chatFragment?.disconnect()

    /** Explicit playback end, distinct from the Fragment/view becoming hidden. */
    protected fun releaseV2ChatSession() {
        chatFragment?.disconnect()
    }

    fun reconnect() = chatFragment?.reconnect()

    fun secondViewIsHidden() = !binding.chatLayout.isVisible && isMaximized

    fun canEnterPictureInPicture(): Boolean {
        val quality = playbackService?.quality
        return quality?.name != BasePlaybackService.AUDIO_ONLY_QUALITY &&  quality?.name != BasePlaybackService.CHAT_ONLY_QUALITY
    }

    protected fun setPipActions(playing: Boolean, autoEnterEnabled: Boolean? = null) {
        pipPlaying = playing
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            requireContext().prefs().getBoolean(C.PLAYER_PICTURE_IN_PICTURE, true)
        ) {
            val builder = PictureInPictureParams.Builder().apply {
                setActions(listOf(
                    RemoteAction(
                        Icon.createWithResource(requireContext(), R.drawable.baseline_audiotrack_black_24),
                        getString(R.string.audio_only),
                        getString(R.string.audio_only),
                        PendingIntent.getBroadcast(
                            requireContext(),
                            REQUEST_CODE_AUDIO_ONLY,
                            Intent(MainActivity.INTENT_START_AUDIO_ONLY).setPackage(requireContext().packageName),
                            PendingIntent.FLAG_IMMUTABLE
                        )
                    ),
                    if (playing) {
                        RemoteAction(
                            Icon.createWithResource(requireContext(), R.drawable.baseline_pause_black_48),
                            getString(R.string.pause),
                            getString(R.string.pause),
                            PendingIntent.getBroadcast(
                                requireContext(),
                                REQUEST_CODE_PLAY_PAUSE,
                                Intent(MainActivity.INTENT_PLAY_PAUSE_PLAYER).setPackage(requireContext().packageName),
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        )
                    } else {
                        RemoteAction(
                            Icon.createWithResource(requireContext(), R.drawable.baseline_play_arrow_black_48),
                            getString(R.string.resume),
                            getString(R.string.resume),
                            PendingIntent.getBroadcast(
                                requireContext(),
                                REQUEST_CODE_PLAY_PAUSE,
                                Intent(MainActivity.INTENT_PLAY_PAUSE_PLAYER).setPackage(requireContext().packageName),
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        )
                    }
                ))
                if (binding.playerLayout.width > 0 && binding.playerLayout.height > 0) {
                    val aspectRatio = binding.playerLayout.width.toFloat() / binding.playerLayout.height
                    if (aspectRatio in 0.42f..2.39f) {
                        setAspectRatio(Rational(binding.playerLayout.width, binding.playerLayout.height))
                    }
                    val sourceRect = Rect()
                    if (binding.playerLayout.getGlobalVisibleRect(sourceRect) && sourceRect.width() > 0 && sourceRect.height() > 0) {
                        setSourceRectHint(sourceRect)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(autoEnterEnabled ?: (playing && canEnterPictureInPicture()))
                }
            }
            requireActivity().setPictureInPictureParams(builder.build())
        }
    }

    override fun onResume() {
        super.onResume()
        binding.playerControls.root.reloadProfile()
        if (requireContext().isTelevision() && !isPortrait) {
            applyTvChatPresentation(binding.chatLayout, binding.playerLayout, binding.slidingLayout, isChatOpen)
        } else if (!isPortrait) {
            applyPhoneChatPresentation(isChatOpen)
        }
        val isInPIPMode = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> requireActivity().isInPictureInPictureMode
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> !useController && isMaximized
            else -> false
        }
        if (isInPIPMode) {
            if (isPortrait) {
                setChatLayoutVisibility(View.GONE)
            } else {
                hideChatLayout()
            }
            useController = false
        }
    }

    override fun initialize() {
        if (isMaximized) {
            (activity as? com.github.andreyasadchy.xtra.ui.main.MainActivity)?.onPlayerEnteredPlayback(
                isLive = playbackService?.type == BasePlaybackService.STREAM,
            )
        }
        if (!started && playbackService?.started == true) {
            started = true
            start()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        with(binding) {
            isPortrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
            if (isMaximized) {
                enableBackground()
            } else {
                disableBackground()
            }
            val isInPIPMode = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> requireActivity().isInPictureInPictureMode
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> !useController && isMaximized
                else -> false
            }
            if (!isInPIPMode) {
                (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(chatLayout.windowToken, 0)
                chatLayout.clearFocus()
                initLayout()
                refreshPlayerControls()
                applyHudLayout()
                if (isInteractionLocked) {
                    setInteractionLocked(true, force = true)
                }
            }
            (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.dismiss()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        with(binding) {
            if (isInPictureInPictureMode) {
                if (!isMaximized) {
                    isMaximized = true
                    requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)
                    if (playbackService?.type == BasePlaybackService.STREAM && chatFragment?.emoteMenuIsVisible() == true) {
                        chatFragment?.toggleBackPressedCallback(true)
                    }
                    slidingLayout.translationX = 0f
                    slidingLayout.translationY = 0f
                    slidingLayout.scaleX = 1f
                    slidingLayout.scaleY = 1f
                }
                if (isPortrait) {
                    setChatLayoutVisibility(View.GONE)
                } else {
                    hideChatLayout()
                }
                useController = false
                hideController(force = true)
                updateInteractionLockBackCallback()
                // player dialog
                (childFragmentManager.findFragmentByTag("closeOnPip") as? BottomSheetDialogFragment)?.dismiss()
                // player chat message dialog
                (chatFragment?.childFragmentManager?.findFragmentByTag("messageDialog") as? BottomSheetDialogFragment)?.dismiss()
                (chatFragment?.childFragmentManager?.findFragmentByTag("replyDialog") as? BottomSheetDialogFragment)?.dismiss()
                (chatFragment?.childFragmentManager?.findFragmentByTag("imageDialog") as? BottomSheetDialogFragment)?.dismiss()
                (activity as? com.github.andreyasadchy.xtra.ui.main.MainActivity)?.onPlayerEnteredPlayback(
                    isLive = playbackService?.type == BasePlaybackService.STREAM,
                )
            } else {
                (activity as? com.github.andreyasadchy.xtra.ui.main.MainActivity)?.onPlayerEnteredPlayback(
                    isLive = playbackService?.type == BasePlaybackService.STREAM,
                )
                useController = true
                if (isInteractionLocked) {
                    showController(force = true)
                    updateInteractionLockBackCallback()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        _binding?.let {
            hudVisibility.onScrubStop()
            it.playerControls.root.removeCallbacks(controllerHideAction)
            hideController(force = true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_INTERACTION_LOCKED, isInteractionLocked)
        super.onSaveInstanceState(outState)
    }

    fun minimize() {
        if (requireContext().isTelevision()) {
            (activity as? MainActivity)?.closePlayer() ?: close()
            return
        }
        if (isInteractionLocked) {
            return
        }

        with(binding) {
            val wasMaximized = isMaximized
            isMaximized = false
            dismissPlayer.visibility = View.VISIBLE
            if (wasMaximized) {
                (activity as? com.github.andreyasadchy.xtra.ui.main.MainActivity)?.onPlayerReturnedToBrowsing(playerStillOpen = true)
            }
            if (playbackService?.type == BasePlaybackService.STREAM && chatFragment?.emoteMenuIsVisible() == true) {
                chatFragment?.toggleBackPressedCallback(false)
            }
            backPressedCallback.remove()
            useController = false
            hideController(true)
            fun animate() {
                val (minimizedScaleX, minimizedScaleY) = getScaleValues()
                applyMinimizedDismissButtonTransform()
                val windowInsets = ViewCompat.getRootWindowInsets(requireView())
                val insets = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val keyboardInsets = windowInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom?.let { if (it > 0) it - (insets?.bottom ?: 0) else it } ?: 0
                val scaledXDiff = (slidingLayout.width * (1f - minimizedScaleX)) / 2
                val scaledYDiff = (slidingLayout.height * (1f - minimizedScaleY)) / 2
                val navBarHeight = requireView().rootView.findViewById<LinearLayout>(R.id.navBarContainer)?.height?.takeIf { it > 0 }?.let { it - keyboardInsets } ?: (insets?.bottom ?: 0)
                val playerWidth = if (isPortrait) {
                    playerLayout.width
                } else {
                    slidingLayout.width - getHorizontalInsets(windowInsets)
                }
                val newX = slidingLayout.width - (insets?.right ?: 0) - (playerWidth * minimizedScaleX) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20F, resources.displayMetrics) * minimizedScaleX)
                val newY = slidingLayout.height - navBarHeight - (playerLayout.height * minimizedScaleY) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 30F, resources.displayMetrics) * minimizedScaleY)
                slidingLayout.animate().apply {
                    translationX(0f - scaledXDiff - ((insets?.left ?: 0) * minimizedScaleX) + newX)
                    translationY(0f - scaledYDiff - ((insets?.top ?: 0) * minimizedScaleY) + newY)
                    scaleX(minimizedScaleX)
                    scaleY(minimizedScaleY)
                    setDuration(250L)
                    setListener(
                        object : AnimatorListenerAdapter() {
                            override fun onAnimationStart(animation: Animator) {
                                isAnimating = true
                                if (view != null) {
                                    disableBackground()
                                }
                            }

                            override fun onAnimationEnd(animation: Animator) {
                                isAnimating = false
                                setListener(null)
                                activePointerId = -1
                            }
                        }
                    )
                    start()
                }
            }
            if (isPortrait) {
                setChatLayoutVisibility(View.GONE)
                slidingLayout.doOnLayout {
                    animate()
                }
            } else {
                showStatusBar()
                hideChatLayout()
                slidingLayout.doOnPreDraw {
                    animate()
                }
                val activity = requireActivity()
                activity.lifecycleScope.launch {
                    delay(500.milliseconds)
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }

    fun maximize(showControls: Boolean = false) {
        with(binding) {
            isMaximized = true
            dismissPlayer.visibility = View.GONE
            dismissPlayer.scaleX = 1f
            dismissPlayer.scaleY = 1f
            dismissPlayer.translationX = 0f
            dismissPlayer.translationY = 0f
            (activity as? com.github.andreyasadchy.xtra.ui.main.MainActivity)?.onPlayerEnteredPlayback(
                isLive = playbackService?.type == BasePlaybackService.STREAM,
            )
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)
            if (playbackService?.type == BasePlaybackService.STREAM && chatFragment?.emoteMenuIsVisible() == true) {
                chatFragment?.toggleBackPressedCallback(true)
            }
            updateInteractionLockBackCallback()
            useController = true
            if (showControls || !controllerHideOnTouch) {
                showController(force = true)
                updateProgress()
            }
            refreshPlayerHudLayout()
            if (isPortrait) {
                setChatLayoutVisibility(View.VISIBLE)
            } else {
                hideStatusBar()
                if (isChatOpen) {
                    showChatLayout()
                }
            }
            slidingLayout.animate().apply {
                translationX(0f)
                translationY(0f)
                scaleX(1f)
                scaleY(1f)
                setDuration(250L)
                setListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationStart(animation: Animator) {
                            isAnimating = true
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            isAnimating = false
                            setListener(null)
                            if (view != null) {
                                enableBackground()
                            }
                            activePointerId = -1
                        }
                    }
                )
                start()
            }
        }
    }

    fun findVideoUrl() {
        (activity as? MainActivity)?.findVideoUrl(playbackService?.streamId, playbackService?.channelLogin, playbackService?.createdAt)
    }

    fun showDownloadDialog() {
        if (playbackService?.loaded == true) {
            when (playbackService?.type) {
                BasePlaybackService.STREAM -> {
                    val qualities = playbackService?.qualities?.filter { !it.url.isNullOrBlank() }
                    DownloadDialog.newStreamInstance(
                        id = playbackService?.streamId,
                        channelId = playbackService?.channelId,
                        channelLogin = playbackService?.channelLogin,
                        channelName = playbackService?.channelName,
                        channelImage = playbackService?.channelImage,
                        gameId = playbackService?.gameId,
                        gameSlug = playbackService?.gameSlug,
                        gameName = playbackService?.gameName,
                        title = playbackService?.title,
                        thumbnail = playbackService?.thumbnail,
                        createdAt = playbackService?.createdAt,
                        qualityNames = qualities?.map { it.name.toString() }?.toTypedArray(),
                        qualityCodecs = qualities?.map { it.codecs.toString() }?.toTypedArray(),
                        qualityBitrates = qualities?.map { it.bitrate.toString() }?.toTypedArray(),
                        qualityUrls = qualities?.map { it.url.toString() }?.toTypedArray(),
                    ).show(childFragmentManager, null)
                }
                BasePlaybackService.VIDEO -> {
                    val qualities = playbackService?.qualities?.filter { !it.url.isNullOrBlank() }
                    DownloadDialog.newVideoInstance(
                        id = playbackService?.videoId,
                        channelId = playbackService?.channelId,
                        channelLogin = playbackService?.channelLogin,
                        channelName = playbackService?.channelName,
                        channelImage = playbackService?.channelImage,
                        gameId = playbackService?.gameId,
                        gameSlug = playbackService?.gameSlug,
                        gameName = playbackService?.gameName,
                        title = playbackService?.title,
                        thumbnail = playbackService?.thumbnail,
                        createdAt = playbackService?.createdAt,
                        durationSeconds = playbackService?.durationSeconds,
                        type = playbackService?.videoType,
                        animatedPreviewUrl = playbackService?.videoAnimatedPreviewURL,
                        totalDuration = getTotalDuration(),
                        currentPosition = getCurrentPosition(),
                        qualityNames = qualities?.map { it.name.toString() }?.toTypedArray(),
                        qualityCodecs = qualities?.map { it.codecs.toString() }?.toTypedArray(),
                        qualityBitrates = qualities?.map { it.bitrate.toString() }?.toTypedArray(),
                        qualityUrls = qualities?.map { it.url.toString() }?.toTypedArray(),
                    ).show(childFragmentManager, null)
                }
                BasePlaybackService.CLIP -> {
                    val qualities = playbackService?.qualities?.filter { !it.url.isNullOrBlank() }
                    DownloadDialog.newClipInstance(
                        id = playbackService?.clipId,
                        channelId = playbackService?.channelId,
                        channelLogin = playbackService?.channelLogin,
                        channelName = playbackService?.channelName,
                        channelImage = playbackService?.channelImage,
                        gameId = playbackService?.gameId,
                        gameSlug = playbackService?.gameSlug,
                        gameName = playbackService?.gameName,
                        title = playbackService?.title,
                        thumbnail = playbackService?.thumbnail,
                        createdAt = playbackService?.createdAt,
                        durationSeconds = playbackService?.durationSeconds,
                        videoId = playbackService?.videoId,
                        videoOffsetSeconds = playbackService?.videoOffsetSeconds,
                        videoCreatedAt = playbackService?.videoCreatedAt,
                        qualityNames = qualities?.map { it.name.toString() }?.toTypedArray(),
                        qualityCodecs = qualities?.map { it.codecs.toString() }?.toTypedArray(),
                        qualityBitrates = qualities?.map { it.bitrate.toString() }?.toTypedArray(),
                        qualityUrls = qualities?.map { it.url.toString() }?.toTypedArray(),
                    ).show(childFragmentManager, null)
                }
            }
        }
    }

    fun onSleepTimerChanged(durationMs: Long, hours: Int, minutes: Int, lockScreen: Boolean) {
        if (durationMs > 0L) {
            Toast.makeText(
                requireContext(),
                when {
                    hours == 0 -> getString(
                        R.string.playback_will_stop,
                        resources.getQuantityString(R.plurals.minutes, minutes, minutes)
                    )
                    minutes == 0 -> getString(
                        R.string.playback_will_stop,
                        resources.getQuantityString(R.plurals.hours, hours, hours)
                    )
                    else -> getString(
                        R.string.playback_will_stop_hours_minutes,
                        resources.getQuantityString(R.plurals.hours, hours, hours),
                        resources.getQuantityString(R.plurals.minutes, minutes, minutes)
                    )
                },
                Toast.LENGTH_LONG
            ).show()
        } else if (((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0) > 0L) {
            Toast.makeText(requireContext(), R.string.timer_canceled, Toast.LENGTH_LONG).show()
        }
        if (lockScreen != requireContext().prefs().getBoolean(C.SLEEP_TIMER_LOCK, false)) {
            requireContext().prefs().edit { putBoolean(C.SLEEP_TIMER_LOCK, lockScreen) }
        }
        (activity as? MainActivity)?.setSleepTimer(durationMs)
    }

    override fun onChange(requestCode: Int, index: Int, text: CharSequence, tag: String?, tag2: String?) {
        when (requestCode) {
            REQUEST_CODE_QUALITY -> {
                changeQuality(playbackService?.qualities?.find { it.name == tag && it.url == tag2 })
                changePlayerMode()
                setQualityText()
            }
            REQUEST_CODE_SPEED -> {
                requireContext().prefs().getString(C.PLAYER_SPEED_LIST, "0.25\n0.5\n0.75\n1.0\n1.25\n1.5\n1.75\n2.0\n3.0\n4.0\n8.0")?.split("\n")?.let { speeds ->
                    speeds.getOrNull(index)?.toFloatOrNull()?.let { speed ->
                        setPlaybackSpeed(speed)
                        requireContext().prefs().edit { putFloat(C.PLAYER_SPEED, speed) }
                        (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setSpeed(speed.toString())
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        phoneChatOverlayGesture?.detach()
        phoneChatOverlayGesture = null
        _binding?.let { cancelLiveTapSeek() }
        _binding?.let { binding ->
            keyboardLayoutListener?.let(binding.slidingLayout.viewTreeObserver::removeOnGlobalLayoutListener)
        }
        keyboardLayoutListener = null
        _binding?.playerControls?.root?.let { root ->
            pendingTvFocusRequest?.let(root::removeCallbacks)
        }
        pendingTvFocusRequest = null
        liveRewindDiscoveryJob?.cancel()
        liveRewindDiscoveryJob = null
        liveCaptionModelVerificationJob?.cancel()
        liveCaptionModelVerificationJob = null
        stopLiveRewindTicker()
        stopStreamUptimeTicker()
        streamUptimeWasLive = false
        liveRewindScrubPositionMs = null
        pausedLivePositionMs = null
        liveRewindSwitchGeneration++
        liveRewindSwitchJob?.cancel()
        liveRewindSwitchJob = null
        liveRewindSwitching = false
        liveRewindReturningLive = false
        liveRewindPendingVodId = null
        liveRewindPendingTargetMs = null
        started = false
        loadedChannelAvatarUrl = null
        interactionLockBackCallback?.remove()
        interactionLockBackCallback = null
        _binding?.liveCaptionView?.clearCaption()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val CONTROLLER_ANIMATION_DURATION_MS = 250L
        private const val CONTROLLER_AUTO_HIDE_DELAY_MS = 3_000L
        private const val REQUEST_CODE_QUALITY = 0
        private const val REQUEST_CODE_SPEED = 1
        private const val REQUEST_CODE_AUDIO_ONLY = 2
        private const val REQUEST_CODE_PLAY_PAUSE = 3
        private const val STATE_INTERACTION_LOCKED = "interaction_locked"
        const val KEY_OFFLINE = "offline"
    }
}






