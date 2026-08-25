package com.github.andreyasadchy.xtra.ui.main

import android.Manifest
import android.app.ActivityOptions
import android.app.PictureInPictureParams
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.os.ext.SdkExtensions
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.res.use
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withStarted
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupWithNavController
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ActivityMainBinding
import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.repository.auth.AuthSessionMaintenanceState
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.download.StreamDownloadService
import com.github.andreyasadchy.xtra.ui.download.VideoDownloadService
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.games.GamesFragmentDirections
import com.github.andreyasadchy.xtra.ui.login.TwitchWebLoginActivity
import com.github.andreyasadchy.xtra.ui.main.MainViewModel.Companion.MainViewModelFactory
import com.github.andreyasadchy.xtra.ui.player.BasePlaybackService
import com.github.andreyasadchy.xtra.ui.player.ExoPlayerFragment
import com.github.andreyasadchy.xtra.ui.player.Media3Fragment
import com.github.andreyasadchy.xtra.ui.player.Media3PlayerFragment
import com.github.andreyasadchy.xtra.ui.multiview.MultiviewFragment
import com.github.andreyasadchy.xtra.ui.player.MediaPlayerFragment
import com.github.andreyasadchy.xtra.ui.player.PlayerFragment
import com.github.andreyasadchy.xtra.ui.saved.SavedMediaFragment
import com.github.andreyasadchy.xtra.ui.saved.SavedPagerFragment
import com.github.andreyasadchy.xtra.ui.saved.downloads.DownloadsFragment
import com.github.andreyasadchy.xtra.ui.team.TeamFragmentDirections
import com.github.andreyasadchy.xtra.ui.top.TopStreamsFragmentDirections
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.SettingsUpdateIndicator
import com.github.andreyasadchy.xtra.util.SettingsMigration
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.updater.UpdateRelease
import com.github.andreyasadchy.xtra.util.updater.UpdateReleaseHistory
import com.github.andreyasadchy.xtra.util.updater.UpdateCheckScheduler
import com.github.andreyasadchy.xtra.util.updater.UpdateState
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.applyTheme
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.rawPrefs
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.chromium.net.CronetProvider
import java.util.Timer
import kotlin.concurrent.schedule

class MainActivity : AppCompatActivity() {

    companion object {
        const val KEY_VIDEO = "video"

        const val INTENT_LIVE_NOTIFICATION = "com.github.andreyasadchy.xtra.LIVE_NOTIFICATION"
        const val INTENT_OPEN_DOWNLOADS_TAB = "com.github.andreyasadchy.xtra.OPEN_DOWNLOADS_TAB"
        const val INTENT_OPEN_DOWNLOADED_VIDEO = "com.github.andreyasadchy.xtra.OPEN_DOWNLOADED_VIDEO"
        const val INTENT_OPEN_PLAYER = "com.github.andreyasadchy.xtra.OPEN_PLAYER"
        const val INTENT_START_AUDIO_ONLY = "com.github.andreyasadchy.xtra.START_AUDIO_ONLY"
        const val INTENT_PLAY_PAUSE_PLAYER = "com.github.andreyasadchy.xtra.PLAY_PAUSE_PLAYER"
        const val INTENT_OPEN_OWN_PROFILE = "com.github.andreyasadchy.xtra.OPEN_OWN_PROFILE"
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels { MainViewModelFactory }
    private lateinit var navController: NavController
    var playerFragment: Fragment? = null
        private set
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var pipActionReceiver: BroadcastReceiver? = null
    private lateinit var prefs: SharedPreferences
    var settingsResultLauncher: ActivityResultLauncher<Intent>? = null
    var loginResultLauncher: ActivityResultLauncher<Intent>? = null
    var logoutResultLauncher: ActivityResultLauncher<Intent>? = null
    private var authMaintenanceResultLauncher: ActivityResultLauncher<Intent>? = null
    private val updateRepository by lazy { (application as XtraApp).xtraModule.updateRepository }
    private val authSessionMaintainer by lazy { (application as XtraApp).xtraModule.authSessionMaintainer }
    private var updateDialog: AlertDialog? = null
    private var updateDialogReleaseId: String? = null
    private var networkSnackbar: Snackbar? = null
    private var updateNotificationSnackbar: Snackbar? = null
    private var updateNotificationPermissionLauncher: ActivityResultLauncher<String>? = null
    private var fragmentLifecycleCallbacks: FragmentManager.FragmentLifecycleCallbacks? = null
    private var startupTasksReady = false

    //Lifecycle methods

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = prefs()
        migrateSettings()
        UpdateCheckScheduler.schedule(this)
        LiveNotificationScheduler.migrateMode(this)
        applyTheme()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fragmentLifecycleCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(
                fragmentManager: FragmentManager,
                fragment: Fragment,
                view: View,
                savedInstanceState: Bundle?,
            ) {
                view.findViewById<Toolbar>(R.id.toolbar)?.let {
                    SettingsUpdateIndicator.update(it, this@MainActivity)
                    ProfileMenuBinder.bind(it, this@MainActivity)
                }
            }
        }.also {
            supportFragmentManager.registerFragmentLifecycleCallbacks(it, true)
        }
        setNavBarColor(resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
        val ignoreCutouts = prefs.getBoolean(C.UI_DRAW_BEHIND_CUTOUTS, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = if (ignoreCutouts) {
                windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            } else {
                windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            }
            binding.navHostFragment.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = insets.left
                rightMargin = insets.right
            }
            binding.navBarContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = insets.left
                rightMargin = insets.right
            }
            windowInsets
        }
        settingsResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                recreate()
            }
        }
        loginResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                findViewById<Toolbar>(R.id.toolbar)?.let { ProfileMenuBinder.bind(it, this) }
                restartActivity()
            }
        }
        logoutResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            findViewById<Toolbar>(R.id.toolbar)?.let { ProfileMenuBinder.bind(it, this) }
            restartActivity()
        }
        authMaintenanceResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                restartActivity()
            }
        }
        updateNotificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) {
            updateNotificationSnackbar?.dismiss()
            updateNotificationSnackbar = null
        }

        var initialized = savedInstanceState != null
        initNavigation()
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        if (!initialized) {
            val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            val isNetworkAvailable = networkCapabilities != null
                    && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (!isNetworkAvailable) {
                initialized = true
                showNetworkFeedback(isNetworkAvailable = false)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.checkNetworkStatus.collectLatest {
                    if (it) {
                        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                        val isNetworkAvailable = networkCapabilities != null
                                && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        if (viewModel.isNetworkAvailable.value != isNetworkAvailable) {
                            viewModel.isNetworkAvailable.value = isNetworkAvailable
                            if (initialized) {
                                showNetworkFeedback(isNetworkAvailable, showRestored = true)
                            } else {
                                initialized = true
                                if (!isNetworkAvailable) {
                                    showNetworkFeedback(isNetworkAvailable = false)
                                }
                            }
                            if (isNetworkAvailable) {
                                binding.root.post {
                                    if (isFinishing || isDestroyed || !startupTasksReady) return@post
                                    handleNetworkAvailable()
                                }
                            }
                        }
                        viewModel.checkNetworkStatus.value = false
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.checkCellularStatus.collectLatest {
                    if (it) {
                        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                        val cellular = networkCapabilities != null && networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        if (!cellular) {
                            if (prefs.getBoolean(C.DOWNLOAD_WIFI_ONLY, false)) {
                                val downloads = viewModel.getWaitingDownloads()
                                if (downloads.isNotEmpty()) {
                                    downloads.forEach {
                                        val intent = if (it.live) {
                                            Intent(this@MainActivity, StreamDownloadService::class.java).apply {
                                                action = StreamDownloadService.INTENT_START
                                                putExtra(StreamDownloadService.KEY_VIDEO_ID, it.id)
                                            }
                                        } else {
                                            Intent(this@MainActivity, VideoDownloadService::class.java).apply {
                                                action = VideoDownloadService.INTENT_START
                                                putExtra(VideoDownloadService.KEY_VIDEO_ID, it.id)
                                            }
                                        }
                                        startService(intent)
                                    }
                                    val currentFragment = supportFragmentManager.findFragmentById(R.id.navHostFragment)?.childFragmentManager?.fragments?.getOrNull(0)
                                    if (currentFragment is SavedPagerFragment || currentFragment is SavedMediaFragment) {
                                        val fragment = currentFragment.childFragmentManager.fragments.find { it is DownloadsFragment }
                                        if (downloads.any { it.live }) {
                                            (fragment as? DownloadsFragment)?.bindStreamDownloadService(true)
                                        }
                                        if (downloads.any { !it.live }) {
                                            (fragment as? DownloadsFragment)?.bindVideoDownloadService(true)
                                        }
                                    }
                                }
                            }
                        }
                        viewModel.checkCellularStatus.value = false
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.startDownloadService.collect {
                    val videoId = it.first
                    val live = it.second
                    if (live) {
                        val intent = Intent(this@MainActivity, StreamDownloadService::class.java).apply {
                            action = StreamDownloadService.INTENT_START
                            putExtra(StreamDownloadService.KEY_VIDEO_ID, videoId)
                        }
                        startService(intent)
                    } else {
                        val intent = Intent(this@MainActivity, VideoDownloadService::class.java).apply {
                            action = VideoDownloadService.INTENT_START
                            putExtra(VideoDownloadService.KEY_VIDEO_ID, videoId)
                        }
                        startService(intent)
                    }
                    val currentFragment = supportFragmentManager.findFragmentById(R.id.navHostFragment)?.childFragmentManager?.fragments?.getOrNull(0)
                    if (currentFragment is SavedPagerFragment || currentFragment is SavedMediaFragment) {
                        val fragment = currentFragment.childFragmentManager.fragments.find { it is DownloadsFragment }
                        if (live) {
                            (fragment as? DownloadsFragment)?.bindStreamDownloadService(true)
                        } else {
                            (fragment as? DownloadsFragment)?.bindVideoDownloadService(true)
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                updateRepository.state.collectLatest { state ->
                    updateSettingsIndicator(state)
                    if (state is UpdateState.Available &&
                        updateDialog?.isShowing == true &&
                        updateDialogReleaseId != state.release.id
                    ) {
                        dismissUpdateDialogForRefresh()
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                updateRepository.automaticPromptEvents.collectLatest { release ->
                    showUpdateDialog(release)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                authSessionMaintainer.state.collectLatest { state ->
                    handleAuthMaintenanceState(state)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                authSessionMaintainer.authHealth.collectLatest {
                    findViewById<Toolbar>(R.id.toolbar)?.let { toolbar ->
                        ProfileMenuBinder.refreshAuthHealth(toolbar, this@MainActivity)
                    }
                }
            }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                lifecycleScope.launch {
                    viewModel.checkNetworkStatus.value = true
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                lifecycleScope.launch {
                    viewModel.checkCellularStatus.value = true
                }
            }

            override fun onLost(network: Network) {
                lifecycleScope.launch {
                    viewModel.checkNetworkStatus.value = true
                }
            }
        }
        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder().apply {
                addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }.build(),
            callback
        )
        networkCallback = callback
        val pipReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    INTENT_START_AUDIO_ONLY -> {
                        (playerFragment as? Media3PlayerFragment)?.startAudioOnly() ?: (playerFragment as? PlayerFragment)?.startAudioOnly()
                        moveTaskToBack(false)
                    }
                    INTENT_PLAY_PAUSE_PLAYER -> {
                        (playerFragment as? Media3PlayerFragment)?.playPause() ?: (playerFragment as? PlayerFragment)?.playPause()
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            pipReceiver,
            IntentFilter().apply {
                addAction(INTENT_START_AUDIO_ONLY)
                addAction(INTENT_PLAY_PAUSE_PLAYER)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        pipActionReceiver = pipReceiver
        if (prefs.getString(C.PLAYER, C.EXOPLAYER) == C.MEDIA_PLAYER || prefs.getBoolean(C.DEBUG_USE_CUSTOM_PLAYBACK_SERVICE, true)) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.playbackStates.collectLatest { states ->
                        val savedState = states.firstOrNull()
                        if (savedState != null) {
                            (playerFragment as? Media3PlayerFragment)?.close() ?: (playerFragment as? PlayerFragment)?.close()
                            val fragment = when (prefs.getString(C.PLAYER, C.EXOPLAYER)) {
                                C.MEDIA_PLAYER -> MediaPlayerFragment()
                                else -> ExoPlayerFragment()
                            }.apply {
                                if (savedState.type == BasePlaybackService.OFFLINE_VIDEO) {
                                    arguments = Bundle().apply {
                                        putBoolean(PlayerFragment.KEY_OFFLINE, true)
                                    }
                                }
                            }
                            (application as XtraApp).xtraModule.streamFeedRefreshCoordinator.playbackEntered(
                                isLive = savedState.type == BasePlaybackService.STREAM,
                            )
                            startPlayer(fragment)
                        }
                    }
                }
            }
        }
        restorePlayerFragment()
        handleIntent(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.videoUrl.collectLatest { videoUrl ->
                    if (videoUrl != null) {
                        if (videoUrl == "") {
                            Toast.makeText(this@MainActivity, R.string.video_not_found, Toast.LENGTH_SHORT).show()
                        } else {
                            startVideo(Video(), 0, videoUrl = videoUrl)
                        }
                        viewModel.videoUrl.value = null
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.video.collectLatest { pair ->
                    val video = pair?.first
                    val offset = pair?.second
                    if (video != null) {
                        if (!video.id.isNullOrBlank()) {
                            (playerFragment as? Media3PlayerFragment)?.also {
                                it.minimize()
                                it.close()
                                closePlayer()
                            } ?:
                            (playerFragment as? PlayerFragment)?.also {
                                it.minimize()
                                it.close()
                                closePlayer()
                            }
                            startVideo(video, offset, offset != null)
                        }
                        viewModel.video.value = null
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.clip.collectLatest { clip ->
                    if (clip != null) {
                        if (!clip.id.isNullOrBlank()) {
                            startClip(clip)
                        }
                        viewModel.clip.value = null
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.user.collectLatest { user ->
                    if (user != null) {
                        if (!user.id.isNullOrBlank() || !user.login.isNullOrBlank()) {
                            (playerFragment as? Media3PlayerFragment)?.minimize() ?: (playerFragment as? PlayerFragment)?.minimize()
                            navController.navigate(
                                ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                                    channelId = user.id,
                                    channelLogin = user.login,
                                    channelName = user.name,
                                    channelImage = user.profileImage,
                                )
                            )
                        }
                        viewModel.user.value = null
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.game.collectLatest { pair ->
                    if (pair != null) {
                        val game = pair.first
                        val tag = pair.second
                        if (game != null) {
                            (playerFragment as? Media3PlayerFragment)?.minimize() ?: (playerFragment as? PlayerFragment)?.minimize()
                            navController.navigate(GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                gameId = game.id,
                                gameSlug = game.slug,
                                gameName = game.name,
                                boxArt = game.boxArt,
                                tags = tag?.let { arrayOf(it) },
                            ))
                        }
                        viewModel.game.value = null
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.tag.collectLatest { tag ->
                    if (tag != null) {
                        (playerFragment as? Media3PlayerFragment)?.minimize() ?: (playerFragment as? PlayerFragment)?.minimize()
                        navController.navigate(
                            GamesFragmentDirections.actionGlobalGamesFragment(
                                tags = arrayOf(tag)
                            )
                        )
                        viewModel.tag.value = null
                    }
                }
            }
        }
        binding.root.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            startupTasksReady = true
            handleAuthMaintenanceState(authSessionMaintainer.state.value)
            runDeferredStartupTasks()
        }, 250L)
    }

    private fun runDeferredStartupTasks() {
        if (prefs.getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) && LiveNotificationScheduler.canPostNotifications(this)) {
            LiveNotificationScheduler.enable(this, baselineOnly = false)
        } else {
            if (prefs.getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false)) {
                prefs.edit { putBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) }
            }
            LiveNotificationScheduler.disable(this)
        }
        if (hasValidatedNetwork()) {
            handleNetworkAvailable()
        } else {
            checkUpdatesIfDue()
        }
    }

    private fun maybePromptForUpdateNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            !prefs.getBoolean(C.UPDATE_CHECK_ENABLED, true) ||
            prefs.getBoolean(C.UPDATE_NOTIFICATION_PERMISSION_PROMPT_SHOWN, false) ||
            updateDialog?.isShowing == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        prefs.edit { putBoolean(C.UPDATE_NOTIFICATION_PERMISSION_PROMPT_SHOWN, true) }
        updateNotificationSnackbar = Snackbar.make(
            binding.root,
            R.string.update_notifications_prompt,
            Snackbar.LENGTH_LONG,
        ).setAction(R.string.update_notifications_enable) {
            updateNotificationPermissionLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
        }.also { it.show() }
    }

    private fun hasValidatedNetwork(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun handleNetworkAvailable() {
        (application as XtraApp).xtraModule.streamFeedRefreshCoordinator.onNetworkRestored()
        validateSessionIfDue()
        checkUpdatesIfDue()
    }

    private fun validateSessionIfDue() {
        if (!startupTasksReady || !hasValidatedNetwork()) return
        lifecycleScope.launch {
            authSessionMaintainer.validateIfDue()
        }
    }

    private fun handleAuthMaintenanceState(state: AuthSessionMaintenanceState) {
        if (!startupTasksReady || isFinishing || isDestroyed) return
        val launcher = authMaintenanceResultLauncher ?: return
        when (state) {
            AuthSessionMaintenanceState.REAUTHORIZATION_REQUIRED -> {
                if (authSessionMaintainer.consumeReauthorizationRequest() == state) {
                    Toast.makeText(this, R.string.token_expired, Toast.LENGTH_LONG).show()
                    launcher.launch(
                        Intent(this, TwitchWebLoginActivity::class.java)
                            .putExtra(TwitchWebLoginActivity.EXTRA_REAUTHORIZE, true),
                    )
                }
            }
            AuthSessionMaintenanceState.IDLE,
            AuthSessionMaintenanceState.VALID,
            AuthSessionMaintenanceState.TRANSIENT_FAILURE,
            -> Unit
        }
    }

    private fun showUpdateDialog(release: UpdateRelease) {
        updateRepository.consumeAutomaticPrompt(release)
        if (updateDialog?.isShowing == true && updateDialogReleaseId == release.id) return
        updateRepository.dismissUpdateNotification()
        dismissUpdateDialogForRefresh()
        val releaseNotes = UpdateReleaseHistory.formatForUpdate(
            historyComplete = updateRepository.releaseHistoryComplete.value,
            cumulativeReleases = updateRepository.releasesSinceInstalled(release),
            latestRelease = release,
            noReleaseNotes = getString(R.string.update_no_release_notes),
            incompleteHistoryMessage = getString(R.string.update_history_incomplete),
        )
        var userActionTaken = false
        fun deferFromUserAction() {
            if (!userActionTaken) {
                userActionTaken = true
                updateRepository.defer(release)
            }
        }
        lateinit var dialog: AlertDialog
        dialog = getAlertDialogBuilder()
            .setTitle(getString(R.string.update_available_title, release.displayVersion))
            .setMessage(releaseNotes.ifBlank { getString(R.string.update_no_release_notes) })
            .setPositiveButton(getString(R.string.download_update)) { _, _ ->
                userActionTaken = true
                updateRepository.downloadCurrent()
            }
            .setNegativeButton(getString(R.string.update_not_now)) { _, _ -> deferFromUserAction() }
            .setOnCancelListener { deferFromUserAction() }
            .setOnDismissListener {
                if (updateDialog === dialog) {
                    updateDialog = null
                    updateDialogReleaseId = null
                    scheduleUpdateNotificationPrompt()
                }
            }
            .show()
        updateDialog = dialog
        updateDialogReleaseId = release.id
    }

    private fun scheduleUpdateNotificationPrompt() {
        binding.root.postDelayed({
            if (!isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                maybePromptForUpdateNotifications()
            }
        }, 500L)
    }

    private fun showPersistedUpdateIfNeeded() {
        lifecycleScope.launch {
            updateRepository.awaitReady()
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
            val release = (updateRepository.state.value as? UpdateState.Available)?.release ?: return@launch
            if (!updateRepository.hasPendingAutomaticPrompt(release)) return@launch
            showUpdateDialog(release)
        }
    }

    private fun dismissUpdateDialogForRefresh() {
        val dialog = updateDialog ?: return
        dialog.dismiss()
        if (updateDialog === dialog) {
            updateDialog = null
            updateDialogReleaseId = null
        }
    }

    private fun checkUpdatesIfDue() {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true ||
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true
        ) {
            return
        }
        updateRepository.checkIfDue(prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP), C.DEFAULT_UPDATE_URL)
    }

    private fun updateSettingsIndicator(state: UpdateState? = null) {
        findViewById<Toolbar>(R.id.toolbar)?.let {
            SettingsUpdateIndicator.update(it, this, state)
        }
    }

    fun openOwnProfile() {
        val userId = tokenPrefs().getString(C.USER_ID, null)
        val login = tokenPrefs().getString(C.USERNAME, null)
        if (userId.isNullOrBlank() && login.isNullOrBlank()) {
            return
        }
        (playerFragment as? Media3PlayerFragment)?.minimize()
            ?: (playerFragment as? PlayerFragment)?.minimize()
        navController.navigate(
            ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                channelId = userId,
                channelLogin = login,
                channelName = login,
                channelImage = tokenPrefs().getString(C.PROFILE_IMAGE_URL, null),
            )
        )
    }

    fun openStatistics() {
        (playerFragment as? Media3PlayerFragment)?.minimize()
            ?: (playerFragment as? PlayerFragment)?.minimize()
        if (navController.currentDestination?.id != R.id.statisticsFragment) {
            navController.navigate(R.id.action_global_statisticsFragment)
        }
    }

    private fun setNavBarColor(isPortrait: Boolean) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                window.isNavigationBarContrastEnforced = !isPortrait || !binding.navBarContainer.isVisible
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                @Suppress("DEPRECATION")
                window.navigationBarColor = if (isPortrait && binding.navBarContainer.isVisible) {
                    Color.TRANSPARENT
                } else {
                    val isLightTheme = obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.isLightTheme)).use {
                        it.getBoolean(0, false)
                    }
                    ContextCompat.getColor(this, if (!isLightTheme) R.color.darkScrim else R.color.lightScrim)
                }
            }
            else -> {
                val isLightTheme = obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.isLightTheme)).use {
                    it.getBoolean(0, false)
                }
                @Suppress("DEPRECATION")
                if (!isLightTheme) {
                    window.navigationBarColor = if (isPortrait && binding.navBarContainer.isVisible) {
                        Color.TRANSPARENT
                    } else {
                        ContextCompat.getColor(this, R.color.darkScrim)
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setNavBarColor(newConfig.orientation == Configuration.ORIENTATION_PORTRAIT)
    }

    override fun onResume() {
        super.onResume()
        findViewById<Toolbar>(R.id.toolbar)?.let { ProfileMenuBinder.bind(it, this) }
        if (prefs.getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) && !LiveNotificationScheduler.canPostNotifications(this)) {
            prefs.edit { putBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) }
            LiveNotificationScheduler.disable(this)
        }
        updateRepository.resumePendingInstall()
        if (startupTasksReady) {
            showPersistedUpdateIfNeeded()
            checkUpdatesIfDue()
        }
        updateSettingsIndicator()
        restorePlayerFragment()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (startupTasksReady) {
            scheduleUpdateNotificationPrompt()
        }
    }

    override fun onDestroy() {
        networkSnackbar?.dismiss()
        networkSnackbar = null
        updateNotificationSnackbar?.dismiss()
        updateNotificationSnackbar = null
        networkCallback?.let {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(it)
        }
        pipActionReceiver?.let { unregisterReceiver(it) }
        fragmentLifecycleCallbacks?.let {
            supportFragmentManager.unregisterFragmentLifecycleCallbacks(it)
        }
        if (isFinishing) {
            onPlayerReturnedToBrowsing(playerStillOpen = false)
            (playerFragment as? Media3PlayerFragment)?.close() ?: (playerFragment as? PlayerFragment)?.close()
        }
        super.onDestroy()
    }

    private fun showNetworkFeedback(isNetworkAvailable: Boolean, showRestored: Boolean = false) {
        if (!isNetworkAvailable) {
            if (networkSnackbar == null) {
                networkSnackbar = Snackbar.make(
                    binding.root,
                    R.string.no_connection,
                    Snackbar.LENGTH_INDEFINITE,
                ).setAction(R.string.retry) {
                    viewModel.checkNetworkStatus.value = true
                }
            }
            networkSnackbar?.show()
        } else {
            networkSnackbar?.dismiss()
            networkSnackbar = null
            if (showRestored) {
                Snackbar.make(binding.root, R.string.connection_restored, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun restartActivity() {
        finish()
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            },
            ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
        )
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Multiview uses background audio when Home is pressed. Its explicit
        // toolbar action is the opt-in path into PiP, so Home never leaves a
        // grid unexpectedly floating over another app.
        if (playerFragment == null && currentMultiviewFragment() != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            prefs.getBoolean(C.PLAYER_PICTURE_IN_PICTURE, true) &&
            ((playerFragment as? Media3PlayerFragment)?.canEnterPictureInPicture() ?: (playerFragment as? PlayerFragment)?.canEnterPictureInPicture()) == true
        ) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().build())
            } catch (e: IllegalStateException) {
                //device doesn't support PIP
            }
        }
    }

    fun canMinimizeMultiview(): Boolean {
        return playerFragment == null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            prefs.getBoolean(C.PLAYER_PICTURE_IN_PICTURE, true)
    }

    fun minimizeMultiview() {
        if (!canMinimizeMultiview()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        }
    }

    fun prepareMultiviewPictureInPicture() {
        if (!canMinimizeMultiview() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        setPictureInPictureParams(
            PictureInPictureParams.Builder()
                // Multiview deliberately keeps background audio on Home. PiP
                // is available through the explicit minimize control instead.
                .setAutoEnterEnabled(false)
                .build(),
        )
    }

    fun clearMultiviewPictureInPicture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || playerFragment != null) return
        setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                if (!isTwitchWebUri(uri)) return
                val path = uri.pathSegments
                val networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP)
                val gqlHeaders = TwitchApiHelper.getGQLHeaders(this)
                val helixHeaders = TwitchApiHelper.getHelixHeaders(this)
                val clipId = when {
                    uri.host.equals("clips.twitch.tv", ignoreCase = true) -> path.firstOrNull()
                    path.firstOrNull() == "clip" -> path.getOrNull(1)
                    path.getOrNull(1) == "clip" -> path.getOrNull(2)
                    else -> null
                }
                if (!clipId.isNullOrBlank()) {
                    viewModel.loadClip(clipId, networkLibrary, gqlHeaders, helixHeaders)
                } else when {
                    path.firstOrNull() == "videos" -> {
                        path.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { id ->
                            val offset = uri.getQueryParameter("t")
                                ?.let { TwitchApiHelper.getDuration(it).toLong() * 1000 }
                            viewModel.loadVideo(id, offset, networkLibrary, gqlHeaders, helixHeaders)
                        }
                    }
                    path.take(2) == listOf("directory", "category") -> {
                        path.getOrNull(2)?.takeIf { it.isNotBlank() }?.let {
                            viewModel.loadGame(
                                gameSlug = it,
                                tag = uri.getQueryParameter("tl"),
                                networkLibrary = networkLibrary,
                                gqlHeaders = gqlHeaders,
                                helixHeaders = helixHeaders,
                            )
                        }
                    }
                    path.take(2) == listOf("directory", "game") -> {
                        path.getOrNull(2)?.takeIf { it.isNotBlank() }?.let {
                            viewModel.loadGame(
                                gameName = it,
                                tag = uri.getQueryParameter("tl"),
                                networkLibrary = networkLibrary,
                                gqlHeaders = gqlHeaders,
                                helixHeaders = helixHeaders,
                            )
                        }
                    }
                    path.take(3) == listOf("directory", "all", "tags") -> {
                        path.getOrNull(3)?.takeIf { it.isNotBlank() }?.let {
                            (playerFragment as? Media3PlayerFragment)?.minimize() ?: (playerFragment as? PlayerFragment)?.minimize()
                            navController.navigate(TopStreamsFragmentDirections.actionGlobalTopFragment(tags = arrayOf(it)))
                        }
                    }
                    path == listOf("directory", "all") -> {
                        (playerFragment as? Media3PlayerFragment)?.minimize() ?: (playerFragment as? PlayerFragment)?.minimize()
                        navController.navigate(TopStreamsFragmentDirections.actionGlobalTopFragment())
                    }
                    path.take(2) == listOf("directory", "tags") -> {
                        path.getOrNull(2)?.takeIf { it.isNotBlank() }?.let {
                            viewModel.loadTag(it, networkLibrary, gqlHeaders)
                        }
                    }
                    path.firstOrNull() == "directory" -> {
                        (playerFragment as? Media3PlayerFragment)?.minimize() ?: (playerFragment as? PlayerFragment)?.minimize()
                        navController.navigate(GamesFragmentDirections.actionGlobalGamesFragment())
                    }
                    path.firstOrNull() == "team" -> {
                        path.getOrNull(1)?.takeIf { it.isNotBlank() }?.let {
                            (playerFragment as? Media3PlayerFragment)?.minimize() ?: (playerFragment as? PlayerFragment)?.minimize()
                            navController.navigate(TeamFragmentDirections.actionGlobalTeamFragment(teamName = it))
                        }
                    }
                    else -> {
                        path.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                            viewModel.loadUser(it, networkLibrary, gqlHeaders, helixHeaders)
                        }
                    }
                }
            }
            INTENT_LIVE_NOTIFICATION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(KEY_VIDEO, Stream::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(KEY_VIDEO)
                }?.let {
                    startStream(it)
                }
            }
            INTENT_OPEN_DOWNLOADS_TAB -> {
                binding.navBar.selectedItemId = R.id.savedPagerFragment
            }
            INTENT_OPEN_DOWNLOADED_VIDEO -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(KEY_VIDEO, OfflineVideo::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(KEY_VIDEO)
                }?.let {
                    startOfflineVideo(it)
                }
            }
            INTENT_OPEN_PLAYER -> {
                if (playerFragment != null) {
                    (playerFragment as? Media3PlayerFragment)?.maximize() ?: (playerFragment as? PlayerFragment)?.maximize()
                } else {
                    if (prefs.getString(C.PLAYER, C.EXOPLAYER) != C.MEDIA_PLAYER && prefs.getBoolean(C.DEBUG_USE_CUSTOM_PLAYBACK_SERVICE, true)) {
                        viewModel.getPlaybackStates()
                    }
                }
            }
            INTENT_OPEN_OWN_PROFILE -> openOwnProfile()
        }
    }

    private fun isTwitchWebUri(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        return scheme in setOf("http", "https") &&
            (host == "twitch.tv" || host?.endsWith(".twitch.tv") == true)
    }


//Navigation listeners

    fun startStream(stream: Stream) {
        val tapElapsedMs = SystemClock.elapsedRealtime()
        (application as XtraApp).xtraModule.streamPreloadCoordinator.onStreamSelected(stream)
        onPlayerEnteredPlayback(isLive = true, channelLogin = stream.channelLogin)
        (application as XtraApp).xtraModule.streamPreloadCoordinator.onPlaybackEntered()
        if (prefs.getString(C.PLAYER, C.EXOPLAYER) != C.MEDIA_PLAYER && !prefs.getBoolean(C.DEBUG_USE_CUSTOM_PLAYBACK_SERVICE, true)) {
            (playerFragment as? Media3PlayerFragment)?.close() ?: (playerFragment as? ExoPlayerFragment)?.close()
            val fragment = Media3Fragment.newInstance(stream, tapElapsedMs)
            startPlayer(fragment)
            return
        }
        (playerFragment as? Media3PlayerFragment)?.close() ?: (playerFragment as? ExoPlayerFragment)?.close(deleteStates = false)
        viewModel.savePlaybackState(PlaybackState(
            type = BasePlaybackService.STREAM,
            streamId = stream.id,
            channelId = stream.channelId,
            channelLogin = stream.channelLogin,
            channelName = stream.channelName,
            channelImage = stream.channelImage,
            gameId = stream.gameId,
            gameSlug = stream.gameSlug,
            gameName = stream.gameName,
            title = stream.title,
            thumbnail = stream.thumbnail,
            createdAt = stream.createdAt,
            viewerCount = stream.viewerCount,
        ))
        val fragment = when (prefs.getString(C.PLAYER, C.EXOPLAYER)) {
            C.MEDIA_PLAYER -> MediaPlayerFragment()
            else -> ExoPlayerFragment()
        }
        startPlayer(fragment)
    }

    fun startVideo(video: Video, offset: Long?, ignoreSavedPosition: Boolean = false, videoUrl: String? = null) {
        onPlayerChangedPlayback(isLive = false)
        if (prefs.getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
            viewModel.saveVideoHistory(video)
            if (offset != null) {
                video.id?.toLongOrNull()?.let { viewModel.saveVideoPosition(it, offset) }
            }
        }
        if (prefs.getString(C.PLAYER, C.EXOPLAYER) != C.MEDIA_PLAYER && !prefs.getBoolean(C.DEBUG_USE_CUSTOM_PLAYBACK_SERVICE, true)) {
            (playerFragment as? Media3PlayerFragment)?.close() ?: (playerFragment as? ExoPlayerFragment)?.close()
            val fragment = Media3Fragment.newInstance(video, offset, ignoreSavedPosition)
            startPlayer(fragment)
            return
        }
        (playerFragment as? Media3PlayerFragment)?.close() ?: (playerFragment as? ExoPlayerFragment)?.close(deleteStates = false)
        viewModel.savePlaybackState(PlaybackState(
            type = BasePlaybackService.VIDEO,
            videoId = video.id,
            channelId = video.channelId,
            channelLogin = video.channelLogin,
            channelName = video.channelName,
            channelImage = video.channelImage,
            gameId = video.gameId,
            gameSlug = video.gameSlug,
            gameName = video.gameName,
            title = video.title,
            thumbnail = video.thumbnail,
            createdAt = video.createdAt,
            durationSeconds = video.durationSeconds,
            videoType = video.type,
            videoAnimatedPreviewURL = video.animatedPreviewURL,
            videoUrl = videoUrl,
            position = offset,
        ))
        val fragment = when (prefs.getString(C.PLAYER, C.EXOPLAYER)) {
            C.MEDIA_PLAYER -> MediaPlayerFragment()
            else -> ExoPlayerFragment()
        }
        startPlayer(fragment)
    }

    fun startClip(clip: Clip) {
        onPlayerChangedPlayback(isLive = false)
        if (prefs.getString(C.PLAYER, C.EXOPLAYER) != C.MEDIA_PLAYER && !prefs.getBoolean(C.DEBUG_USE_CUSTOM_PLAYBACK_SERVICE, true)) {
            (playerFragment as? Media3PlayerFragment)?.close() ?: (playerFragment as? ExoPlayerFragment)?.close()
            val fragment = Media3Fragment.newInstance(clip)
            startPlayer(fragment)
            return
        }
        (playerFragment as? Media3PlayerFragment)?.close() ?: (playerFragment as? ExoPlayerFragment)?.close(deleteStates = false)
        viewModel.savePlaybackState(PlaybackState(
            type = BasePlaybackService.CLIP,
            videoId = clip.videoId,
            clipId = clip.id,
            channelId = clip.channelId,
            channelLogin = clip.channelLogin,
            channelName = clip.channelName,
            channelImage = clip.channelImage,
            gameId = clip.gameId,
            gameSlug = clip.gameSlug,
            gameName = clip.gameName,
            title = clip.title,
            thumbnail = clip.thumbnail,
            createdAt = clip.createdAt,
            durationSeconds = clip.durationSeconds,
            videoOffsetSeconds = clip.videoOffsetSeconds,
            videoCreatedAt = clip.videoCreatedAt,
            videoAnimatedPreviewURL = clip.videoAnimatedPreviewURL,
        ))
        val fragment = when (prefs.getString(C.PLAYER, C.EXOPLAYER)) {
            C.MEDIA_PLAYER -> MediaPlayerFragment()
            else -> ExoPlayerFragment()
        }
        startPlayer(fragment)
    }

    fun startOfflineVideo(video: OfflineVideo, offset: Long? = null) {
        onPlayerChangedPlayback(isLive = false)
        if (prefs.getString(C.PLAYER, C.EXOPLAYER) != C.MEDIA_PLAYER && !prefs.getBoolean(C.DEBUG_USE_CUSTOM_PLAYBACK_SERVICE, true)) {
            (playerFragment as? Media3PlayerFragment)?.close() ?: (playerFragment as? ExoPlayerFragment)?.close()
            val fragment = Media3Fragment.newInstance(video)
            startPlayer(fragment)
            return
        }
        (playerFragment as? Media3PlayerFragment)?.close() ?: (playerFragment as? ExoPlayerFragment)?.close(deleteStates = false)
        viewModel.savePlaybackState(PlaybackState(
            type = BasePlaybackService.OFFLINE_VIDEO,
            offlineVideoId = video.id,
            channelId = video.channelId,
            channelLogin = video.channelLogin,
            channelName = video.channelName,
            channelImage = video.channelLogo,
            gameId = video.gameId,
            gameSlug = video.gameSlug,
            gameName = video.gameName,
            title = video.name,
            createdAt = video.uploadDate?.toString(),
            videoCreatedAt = video.videoCreatedAt,
        ))
        if (offset != null && prefs.getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
            viewModel.saveOfflineVideoPosition(video.id, offset)
        }
        val fragment = when (prefs.getString(C.PLAYER, C.EXOPLAYER)) {
            C.MEDIA_PLAYER -> MediaPlayerFragment()
            else -> ExoPlayerFragment()
        }.apply {
            arguments = Bundle().apply {
                putBoolean(PlayerFragment.KEY_OFFLINE, true)
            }
        }
        startPlayer(fragment)
    }

//Player methods

    private fun startPlayer(fragment: Fragment) {
        playerFragment = fragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.playerContainer, fragment).commit()
        viewModel.isPlayerOpened = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            prefs.getBoolean(C.PLAYER_PICTURE_IN_PICTURE, true)
        ) {
            setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(true).build())
        }
    }

    fun closePlayer() {
        onPlayerReturnedToBrowsing(playerStillOpen = false)
        supportFragmentManager.beginTransaction()
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            .remove(supportFragmentManager.findFragmentById(R.id.playerContainer)!!)
            .commit()
        playerFragment = null
        viewModel.isPlayerOpened = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
        }
        viewModel.sleepTimer?.cancel()
        viewModel.sleepTimerEndTime = 0L
        currentMultiviewFragment()?.resumeAfterExternalPlayer()
    }

    fun onPlayerReturnedToBrowsing(playerStillOpen: Boolean) {
        (application as XtraApp).xtraModule.streamPreviewCoordinator.onPlaybackReturned()
        (application as XtraApp).xtraModule.streamFeedRefreshCoordinator.playbackReturned(playerStillOpen)
    }

    fun onPlayerEnteredPlayback(isLive: Boolean = true, channelLogin: String? = null) {
        (application as XtraApp).xtraModule.streamFeedRefreshCoordinator.playbackEntered(isLive)
        (application as XtraApp).xtraModule.streamPreviewCoordinator.onFullscreenPlaybackStarted(channelLogin)
    }

    fun onPlayerChangedPlayback(isLive: Boolean) {
        (application as XtraApp).xtraModule.streamFeedRefreshCoordinator.playbackChanged(isLive)
        (application as XtraApp).xtraModule.streamPreviewCoordinator.onFullscreenPlaybackStarted()
    }

    private fun currentMultiviewFragment(): MultiviewFragment? {
        return (supportFragmentManager.findFragmentById(R.id.navHostFragment) as? NavHostFragment)
            ?.childFragmentManager
            ?.fragments
            ?.firstOrNull { it is MultiviewFragment } as? MultiviewFragment
    }

    private fun restorePlayerFragment() {
        if (playerFragment == null) {
            playerFragment = supportFragmentManager.findFragmentById(R.id.playerContainer) as? Media3PlayerFragment ?: supportFragmentManager.findFragmentById(R.id.playerContainer) as? PlayerFragment
            if (playerFragment == null) {
                if (prefs.getString(C.PLAYER, C.EXOPLAYER) != C.MEDIA_PLAYER && prefs.getBoolean(C.DEBUG_USE_CUSTOM_PLAYBACK_SERVICE, true)) {
                    viewModel.getPlaybackStates()
                }
            }
        } else {
            if (viewModel.isPlayerOpened && ((playerFragment as? Media3PlayerFragment)?.secondViewIsHidden() ?: (playerFragment as? PlayerFragment)?.secondViewIsHidden()) == true && prefs.getBoolean(C.PLAYER_PICTURE_IN_PICTURE, true)) {
                (playerFragment as? Media3PlayerFragment)?.maximize() ?: (playerFragment as? PlayerFragment)?.maximize()
            }
        }
    }

    fun setSleepTimer(duration: Long) {
        viewModel.sleepTimer?.cancel()
        viewModel.sleepTimerEndTime = 0L
        if (duration > 0L) {
            viewModel.sleepTimer = Timer().apply {
                schedule(duration) {
                    lifecycleScope.launch {
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            (playerFragment as? Media3PlayerFragment)?.also {
                                it.minimize()
                                it.close()
                                closePlayer()
                            } ?:
                            (playerFragment as? PlayerFragment)?.also {
                                it.minimize()
                                it.close()
                                closePlayer()
                            }
                            if (prefs.getBoolean(C.SLEEP_TIMER_LOCK, false)) {
                                if ((getSystemService(POWER_SERVICE) as PowerManager).isInteractive) {
                                    try {
                                        (getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager).lockNow()
                                    } catch (e: SecurityException) {

                                    }
                                }
                            }
                        } else {
                            withStarted {
                                (playerFragment as? Media3PlayerFragment)?.also {
                                    it.minimize()
                                    it.close()
                                    closePlayer()
                                } ?:
                                (playerFragment as? PlayerFragment)?.also {
                                    it.minimize()
                                    it.close()
                                    closePlayer()
                                }
                            }
                        }
                    }
                }
            }
            viewModel.sleepTimerEndTime = System.currentTimeMillis() + duration
        }
    }

    fun getSleepTimerTimeLeft(): Long {
        return viewModel.sleepTimerEndTime - System.currentTimeMillis()
    }

    fun findVideoUrl(streamId: String?, channelLogin: String?, streamCreatedAt: String?) {
        viewModel.findVideoUrl(prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP), streamId, channelLogin, streamCreatedAt)
    }

    fun downloadStream(filesDir: String, id: String?, title: String?, createdAt: String?, channelId: String?, channelLogin: String?, channelName: String?, channelImage: String?, thumbnail: String?, gameId: String?, gameSlug: String?, gameName: String?, downloadPath: String, quality: String, downloadChat: Boolean, downloadChatEmotes: Boolean, wifiOnly: Boolean) {
        viewModel.downloadStream(prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP), filesDir, id, title, createdAt, channelId, channelLogin, channelName, channelImage, thumbnail, gameId, gameSlug, gameName, downloadPath, quality, downloadChat, downloadChatEmotes, wifiOnly)
    }

    fun downloadVideo(filesDir: String, id: String?, title: String?, createdAt: String?, type: String?, channelId: String?, channelLogin: String?, channelName: String?, channelImage: String?, thumbnail: String?, gameId: String?, gameSlug: String?, gameName: String?, url: String, downloadPath: String, quality: String, from: Long, to: Long, downloadChat: Boolean, downloadChatEmotes: Boolean, playlistToFile: Boolean, wifiOnly: Boolean) {
        viewModel.downloadVideo(prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP), filesDir, id, title, createdAt, type, channelId, channelLogin, channelName, channelImage, thumbnail, gameId, gameSlug, gameName, url, downloadPath, quality, from, to, downloadChat, downloadChatEmotes, playlistToFile, wifiOnly)
    }

    fun downloadClip(filesDir: String, clipId: String?, title: String?, createdAt: String?, durationSeconds: Int?, videoId: String?, videoOffsetSeconds: Int?, videoCreatedAt: String?, channelId: String?, channelLogin: String?, channelName: String?, channelImage: String?, thumbnail: String?, gameId: String?, gameSlug: String?, gameName: String?, url: String, downloadPath: String, quality: String, downloadChat: Boolean, downloadChatEmotes: Boolean, wifiOnly: Boolean) {
        viewModel.downloadClip(prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP), filesDir, clipId, title, createdAt, durationSeconds, videoId, videoOffsetSeconds, videoCreatedAt, channelId, channelLogin, channelName, channelImage, thumbnail, gameId, gameSlug, gameName, url, downloadPath, quality, downloadChat, downloadChatEmotes, wifiOnly)
    }

    fun popFragment() {
        navController.navigateUp()
    }

    private fun initNavigation() {
        navController = (supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment).navController
        val tabList = prefs.getString(C.UI_NAVIGATION_TAB_LIST, null).let { tabPref ->
            val defaultTabs = C.DEFAULT_NAVIGATION_TAB_LIST.split(',')
            if (tabPref != null) {
                val list = tabPref.split(',').filter { item ->
                    defaultTabs.find { it.first() == item.first() } != null
                }.toMutableList()
                defaultTabs.forEachIndexed { index, item ->
                    if (list.find { it.first() == item.first() } == null) {
                        list.add(index, item)
                    }
                }
                list
            } else defaultTabs
        }
        navController.setGraph(navController.navInflater.inflate(R.navigation.nav_graph).also {
            val defaultItem = tabList.find { it.split(':')[1] != "0" }?.split(':')[0] ?: "1"
            when {
                defaultItem == "2" -> it.setStartDestination(R.id.followPagerFragment)
                defaultItem == "0" -> it.setStartDestination(R.id.rootGamesFragment)
                defaultItem == "4" -> it.setStartDestination(R.id.rootDiscoverFragment)
                defaultItem == "3" -> it.setStartDestination(R.id.savedPagerFragment)
            }
        }, null)
        binding.navBar.apply {
            if (tabList.any { it.split(':')[2] != "0" }) {
                tabList.forEach {
                    val split = it.split(':')
                    val key = split[0]
                    val enabled = split[2] != "0"
                    if (enabled) {
                        when (key) {
                            "0" -> menu.add(Menu.NONE, R.id.rootGamesFragment, Menu.NONE, R.string.browse).setIcon(R.drawable.ic_games_black_24dp)
                            "4" -> menu.add(Menu.NONE, R.id.rootDiscoverFragment, Menu.NONE, R.string.discover).setIcon(R.drawable.ic_explore)
                            "1" -> menu.add(Menu.NONE, R.id.rootTopFragment, Menu.NONE, R.string.following_overview).setIcon(R.drawable.baseline_home_black_24)
                            "2" -> {
                                menu.add(Menu.NONE, R.id.followPagerFragment, Menu.NONE, R.string.following).setIcon(R.drawable.ic_favorite_black_24dp)
                            }
                            "3" -> {
                                menu.add(Menu.NONE, R.id.savedPagerFragment, Menu.NONE, R.string.saved).setIcon(R.drawable.ic_file_download_black_24dp)
                            }
                        }
                    }
                }
            } else {
                binding.navBarContainer.visibility = View.GONE
            }
            setupWithNavController(navController)
            setOnItemSelectedListener {
                NavigationUI.onNavDestinationSelected(it, navController)
                return@setOnItemSelectedListener true
            }
            setOnItemReselectedListener {
                if (!navController.popBackStack(it.itemId, false)) {
                    val currentFragment = supportFragmentManager.findFragmentById(R.id.navHostFragment)?.childFragmentManager?.fragments?.getOrNull(0)
                    if (currentFragment is Scrollable) {
                        currentFragment.scrollToTop()
                    }
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun migrateSettings() {
        val freshInstall = rawPrefs().all.isEmpty()
        val version = prefs.getInt(C.SETTINGS_VERSION, 0).let {
            if (it == 0 && !prefs.getBoolean(C.FIRST_LAUNCH2, true)) {
                when {
                    !prefs.getBoolean(C.FIRST_LAUNCH9, true) -> 8
                    !prefs.getBoolean(C.FIRST_LAUNCH8, true) -> 7
                    !prefs.getBoolean(C.FIRST_LAUNCH7, true) -> 6
                    !prefs.getBoolean(C.FIRST_LAUNCH6, true) -> 5
                    !prefs.getBoolean(C.FIRST_LAUNCH5, true) -> 4
                    !prefs.getBoolean(C.FIRST_LAUNCH3, true) -> 3
                    !prefs.getBoolean(C.FIRST_LAUNCH1, true) -> 2
                    else -> 1
                }
            } else {
                it
            }
        }
        if (version < 1) {
            prefs.edit {
                val width = resources.displayMetrics.widthPixels
                val height = resources.displayMetrics.heightPixels
                val chatWidth = ((if (height > width) height else width) * (30 / 100f)).toInt()
                putInt(C.LANDSCAPE_CHAT_WIDTH, chatWidth)
                if (resources.getBoolean(R.bool.isTablet)) {
                    putString(C.PORTRAIT_COLUMN_COUNT, "2")
                    putString(C.LANDSCAPE_COLUMN_COUNT, "3")
                }
            }
        }
        if (version < 3) {
            val langPref = prefs.getString(C.UI_LANGUAGE, "")
            if (!langPref.isNullOrBlank() && langPref != "auto") {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(langPref))
            }
        }
        if (version < 5) {
            prefs.edit {
                if (prefs.getString(C.PLAYER_PROXY, "1")?.toIntOrNull() == 0) {
                    putBoolean(C.PLAYER_STREAM_PROXY, true)
                }
            }
        }
        if (version < 6) {
            prefs.edit {
                when {
                    MediaCodecSelector.DEFAULT.getDecoderInfos(MimeTypes.VIDEO_H265, false, false).none { it.hardwareAccelerated } -> {
                        putString(C.TOKEN_SUPPORTED_CODECS, "h264")
                    }
                    MediaCodecSelector.DEFAULT.getDecoderInfos(MimeTypes.VIDEO_AV1, false, false).none { it.hardwareAccelerated } -> {
                        putString(C.TOKEN_SUPPORTED_CODECS, "h265,h264")
                    }
                }
            }
        }
        if (version < 7) {
            prefs.edit {
                if (prefs.getString(C.UI_CUTOUT_MODE, "0") == "1") {
                    putBoolean(C.UI_DRAW_BEHIND_CUTOUTS, true)
                }
            }
        }
        if (version < 8) {
            prefs.edit {
                remove(C.USER_ID)
                remove(C.USERNAME)
                remove(C.TOKEN)
            }
        }
        if (version < 9) {
            // Image decoding is selected by the production pipeline now.
        }
        if (version < 10) {
            viewModel.deleteOldImages()
            prefs.edit {
                prefs.getString(C.PLAYER_BACKGROUND_PLAYBACK, "0")?.let {
                    if (it == "1") {
                        putBoolean(C.PLAYER_PICTURE_IN_PICTURE, false)
                    } else if (it == "2") {
                        putBoolean(C.PLAYER_PICTURE_IN_PICTURE, false)
                        putBoolean(C.PLAYER_BACKGROUND_AUDIO, false)
                    }
                }
            }
        }
        if (version < 11) {
            prefs.edit {
                val tabs = prefs.getStringSet(C.UI_NAVIGATION_TABS, null)?.toSortedSet()
                val defaultPage = prefs.getString(C.UI_DEFAULT_PAGE, null)
                if (tabs != null || defaultPage != null) {
                    val set = tabs ?: setOf("0", "1", "2", "3")
                    val default = defaultPage ?: "1"
                    val list = "0:${if (default == "0") "1" else "0"}:${if (set.contains("0")) "1" else "0"}," +
                            "1:${if (default == "1") "1" else "0"}:${if (set.contains("1")) "1" else "0"}," +
                            "2:${if (default == "2") "1" else "0"}:${if (set.contains("2")) "1" else "0"}," +
                            "3:${if (default == "3") "1" else "0"}:${if (set.contains("3")) "1" else "0"}"
                    putString(C.UI_NAVIGATION_TAB_LIST, list)
                }
                val defaultFollowPage = prefs.getString(C.UI_FOLLOW_DEFAULT_PAGE, null)
                if (defaultFollowPage != null) {
                    val list = "0:${if (defaultFollowPage == "3") "1" else "0"}:1," +
                            "1:${if (defaultFollowPage == "0") "1" else "0"}:1," +
                            "2:${if (defaultFollowPage == "1") "1" else "0"}:1," +
                            "3:${if (defaultFollowPage == "2") "1" else "0"}:1"
                    putString(C.UI_FOLLOWING_TABS, list)
                }
                val defaultSavedPage = prefs.getString(C.UI_SAVED_DEFAULT_PAGE, null)
                if (defaultSavedPage != null) {
                    val list = "0:${if (defaultSavedPage == "0") "1" else "0"}:1," +
                            "1:${if (defaultSavedPage == "1") "1" else "0"}:1"
                    putString(C.UI_SAVED_TABS, list)
                }
            }
        }
        if (version < 12) {
            prefs.edit {
                if (!prefs.getBoolean("ui_theme_rounded_corners", true)) {
                    putString(C.UI_THEME_ROUNDED_CORNERS, "2")
                }
            }
        }
        if (version < 13) {
            prefs.edit {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7) {
                    putString(C.NETWORK_LIBRARY, C.HTTP_ENGINE)
                } else {
                    if (CronetProvider.getAllProviders(this@MainActivity).any { it.isEnabled }) {
                        putString(C.NETWORK_LIBRARY, C.CRONET)
                    }
                }
                prefs.getString("playerRewind", null)?.toLongOrNull()?.let {
                    putString(C.PLAYER_REWIND, (it / 1000).toString())
                }
                prefs.getString("playerForward", null)?.toLongOrNull()?.let {
                    putString(C.PLAYER_FORWARD, (it / 1000).toString())
                }
                putInt(C.SETTINGS_VERSION, 13)
            }
        }
        if (version < 14) {
            prefs.edit {
                when {
                    prefs.contains(C.PLAYER_AVOID_ADS) && !prefs.contains(C.PLAYER_HIDE_ADS) -> {
                        putBoolean(C.PLAYER_HIDE_ADS, prefs.getBoolean(C.PLAYER_AVOID_ADS, false))
                    }
                    !prefs.contains(C.PLAYER_AVOID_ADS) && prefs.contains(C.PLAYER_HIDE_ADS) -> {
                        putBoolean(C.PLAYER_AVOID_ADS, prefs.getBoolean(C.PLAYER_HIDE_ADS, false))
                    }
                }
                putInt(C.SETTINGS_VERSION, 14)
            }
        }
        if (version < 15) {
            // GeckoView is now the only account authority. Do not let an old OAuth cache make
            // the profile look signed in; preserve an already-imported Gecko session.
            val hasWebSession = !tokenPrefs().getString(C.GQL_TOKEN_WEB, null).isNullOrBlank()
            tokenPrefs().edit {
                // A legacy Helix credential is never valid for the Gecko account.
                remove(C.TOKEN)
                remove(C.TOKEN_CLIENT_ID)
                if (!hasWebSession) {
                    remove(C.USER_ID)
                    remove(C.USERNAME)
                    remove(C.TOKEN_SCOPES)
                }
            }
            prefs.edit {
                putString(C.GQL_CLIENT_ID_WEB, C.DEFAULT_GQL_CLIENT_ID_WEB)
                putInt(C.SETTINGS_VERSION, 15)
            }
        }
        SettingsMigration.migrate(this, freshInstall = freshInstall)
    }
}

