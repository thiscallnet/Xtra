package com.github.andreyasadchy.xtra.ui.channel

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.viewpager2.widget.ViewPager2
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentChannelBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerViewModel.Companion.ChannelPagerViewModelFactory
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.FragmentHost
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.Sortable
import com.github.andreyasadchy.xtra.ui.common.dispatchPagerScrollState
import com.github.andreyasadchy.xtra.ui.download.DownloadDialog
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.login.TwitchWebLoginActivity
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationScheduler
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.ui.settings.setTabCustomizationLongPress
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.configureForSmoothPaging
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Instant

class ChannelPagerFragment : BaseNetworkFragment(), Scrollable, FragmentHost {

    override val initializeWithoutNetwork = true

    private var _binding: FragmentChannelBinding? = null
    private val binding get() = _binding!!
    private val args: ChannelPagerFragmentArgs by navArgs()
    private val viewModel: ChannelPagerViewModel by viewModels { ChannelPagerViewModelFactory }
    private var firstLaunch = true
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private var pendingNotificationEnable: (() -> Unit)? = null
    private var enableLiveNotificationsAfterChannel = false

    override val currentFragment: Fragment?
        get() = childFragmentManager.findFragmentByTag("f${binding.viewPager.currentItem}")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firstLaunch = savedInstanceState == null
        notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingNotificationEnable
            pendingNotificationEnable = null
            if (granted) {
                action?.invoke()
            } else {
                Toast.makeText(requireContext(), R.string.live_notifications_permission_required, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChannelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(binding) {
            val activity = requireActivity() as MainActivity
            if (isCompactLandscape()) appBar.post { _binding?.appBar?.setExpanded(false, false) }
            val heroVisibilityOwnedIds = intArrayOf(userLayout.id, streamLayout.id, lastBroadcast.id, profileActions.id)
            val compactHeroConstraints = ConstraintSet().apply {
                clone(channelHero)
                heroVisibilityOwnedIds.forEach { setVisibilityMode(it, ConstraintSet.VISIBILITY_MODE_IGNORE) }
            }
            val wideHeroConstraints = ConstraintSet().apply {
                clone(channelHero)
                heroVisibilityOwnedIds.forEach { setVisibilityMode(it, ConstraintSet.VISIBILITY_MODE_IGNORE) }
                val gutter = resources.getDimensionPixelSize(R.dimen.channel_hero_wide_gutter)
                connect(userLayout.id, ConstraintSet.END, channelHeroSplit.id, ConstraintSet.START, gutter)
                clear(userLayout.id, ConstraintSet.BOTTOM)
                setGuidelinePercent(channelHeroSplit.id, 0.5f)
                connect(streamLayout.id, ConstraintSet.START, channelHeroSplit.id, ConstraintSet.END, gutter)
                connect(streamLayout.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, gutter)
                connect(streamLayout.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, gutter)
                connect(lastBroadcast.id, ConstraintSet.START, channelHeroSplit.id, ConstraintSet.END, gutter)
                connect(lastBroadcast.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, gutter)
                connect(profileActions.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, gutter)
                connect(profileActions.id, ConstraintSet.END, channelHeroSplit.id, ConstraintSet.START, gutter)
                connect(profileActions.id, ConstraintSet.TOP, userLayout.id, ConstraintSet.BOTTOM, gutter / 2)
            }
            var heroUsesWideLayout: Boolean? = null
            channelHero.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val useWideLayout = channelHero.width >= resources.getDimensionPixelSize(
                    R.dimen.channel_hero_expanded_min_width,
                ) || (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    channelHero.width >= resources.getDimensionPixelSize(
                        R.dimen.channel_hero_landscape_min_width,
                    ))
                if (heroUsesWideLayout == useWideLayout) return@addOnLayoutChangeListener
                heroUsesWideLayout = useWideLayout
                if (useWideLayout) wideHeroConstraints.applyTo(channelHero) else compactHeroConstraints.applyTo(channelHero)
                updateCompactHero()
            }
            if (viewModel.stream.value == null) {
                watchLive.setOnClickListener {
                    activity.startStream(
                        Stream(
                            id = args.streamId,
                            channelId = args.channelId,
                            channelLogin = args.channelLogin,
                            channelName = args.channelName,
                            channelImageURL = args.channelImage,
                        )
                    )
                }
            }
            args.channelName.let {
                if (it != null) {
                    userLayout.visibility = View.VISIBLE
                    userName.visibility = View.VISIBLE
                    userName.text = if (args.channelLogin != null && !args.channelLogin.equals(it, true)) {
                        when (requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                            "0" -> "${it}(${args.channelLogin})"
                            "1" -> it
                            else -> args.channelLogin
                        }
                    } else {
                        it
                    }
                } else {
                    userName.visibility = View.GONE
                }
            }
            args.channelImage.let {
                if (it != null) {
                    userLayout.visibility = View.VISIBLE
                    userImage.visibility = View.VISIBLE
                    userImage.tag = it
                    requireContext().imageLoader.enqueue(
                        ImageRequest.Builder(requireContext()).apply {
                            data(it)
                            if (requireContext().prefs().getBoolean(C.UI_ROUND_USER_IMAGE, true)) {
                                transformations(CircleCropTransformation())
                            }
                            crossfade(true)
                            target(userImage)
                        }.build()
                    )
                } else {
                    userImage.visibility = View.GONE
                    userImage.tag = null
                }
            }
            val isLoggedIn = !TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                    !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank()
            val setting = requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0
            val navController = findNavController()
            val appBarConfiguration = AppBarConfiguration(setOf(R.id.rootGamesFragment, R.id.rootTopFragment, R.id.followPagerFragment, R.id.followMediaFragment, R.id.savedPagerFragment, R.id.savedMediaFragment))
            toolbar.setupWithNavController(navController, appBarConfiguration)
            toolbar.menu.findItem(R.id.login).title = if (isLoggedIn) getString(R.string.log_out) else getString(R.string.log_in)
            profileShare.setOnClickListener { toolbar.menu.performIdentifierAction(R.id.share, 0) }
            profileFollow.setOnClickListener { toolbar.menu.performIdentifierAction(R.id.followButton, 0) }
            toolbar.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.toggleNotifications -> {
                        viewModel.notificationsEnabled.value?.let {
                            if (it) {
                                viewModel.disableNotifications(
                                    requireContext().tokenPrefs().getString(C.USER_ID, null),
                                    args.channelId,
                                    setting,
                                    requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                    TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                )
                            } else {
                                val notificationsEnabled = requireContext().prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false)
                                val enableChannelNotifications = {
                                    if (!notificationsEnabled) {
                                        enableLiveNotificationsAfterChannel = true
                                    }
                                    viewModel.enableNotifications(
                                        requireContext().tokenPrefs().getString(C.USER_ID, null),
                                        args.channelId,
                                        setting,
                                        notificationsEnabled,
                                        requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                        TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                    )
                                }
                                if (!args.channelId.isNullOrBlank() && !notificationsEnabled) {
                                    withLiveNotificationPermission(enableChannelNotifications)
                                } else {
                                    enableChannelNotifications()
                                }
                            }
                        }
                        true
                    }
                    R.id.followButton -> {
                        viewModel.isFollowing.value?.let {
                            if (it) {
                                requireContext().getAlertDialogBuilder()
                                    .setMessage(getString(R.string.unfollow_channel,
                                        if (args.channelLogin != null && !args.channelLogin.equals(args.channelName, true)) {
                                            when (requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                                                "0" -> "${args.channelName}(${args.channelLogin})"
                                                "1" -> args.channelName
                                                else -> args.channelLogin
                                            }
                                        } else {
                                            args.channelName
                                        }
                                    ))
                                    .setNegativeButton(getString(R.string.no), null)
                                    .setPositiveButton(getString(R.string.yes)) { _, _ ->
                                        viewModel.deleteFollowChannel(
                                            requireContext().tokenPrefs().getString(C.USER_ID, null),
                                            args.channelId,
                                            setting,
                                            requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                            TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                        )
                                    }
                                    .show()
                            } else {
                                viewModel.saveFollowChannel(
                                    requireContext().tokenPrefs().getString(C.USER_ID, null),
                                    args.channelId,
                                    args.channelLogin,
                                    args.channelName,
                                    setting,
                                    requireContext().prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false),
                                    !requireContext().prefs().getBoolean(C.UI_ACTIVATE_NOTIFICATIONS_WHEN_FOLLOWING, true),
                                    requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                    TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                )
                            }
                        }
                        true
                    }
                    R.id.search -> {
                        findNavController().navigate(SearchPagerFragmentDirections.actionGlobalSearchPagerFragment())
                        true
                    }
                    R.id.settings -> {
                        activity.settingsResultLauncher?.launch(Intent(activity, SettingsActivity::class.java))
                        true
                    }
                    R.id.login -> {
                        if (isLoggedIn) {
                            activity.getAlertDialogBuilder().apply {
                                setTitle(getString(R.string.logout_title))
                                requireContext().tokenPrefs().getString(C.USERNAME, null)?.let { setMessage(getString(R.string.logout_msg, it)) }
                                setNegativeButton(getString(R.string.no), null)
                                setPositiveButton(getString(R.string.yes)) { _, _ -> activity.logoutResultLauncher?.launch(Intent(activity, TwitchWebLoginActivity::class.java).putExtra(TwitchWebLoginActivity.EXTRA_LOGOUT, true)) }
                            }.show()
                        } else {
                            activity.loginResultLauncher?.launch(Intent(activity, TwitchWebLoginActivity::class.java))
                        }
                        true
                    }
                    R.id.share -> {
                        startActivity(Intent.createChooser(Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, "https://twitch.tv/${args.channelLogin}")
                            args.channelName?.let {
                                putExtra(Intent.EXTRA_TITLE, it)
                            }
                            type = "text/plain"
                        }, null))
                        true
                    }
                    R.id.download -> {
                        viewModel.stream.value?.let {
                            DownloadDialog.newStreamInstance(
                                id = it.id,
                                channelId = it.channelId,
                                channelLogin = it.channelLogin,
                                channelName = it.channelName,
                                channelImage = it.channelImage,
                                gameId = it.gameId,
                                gameSlug = it.gameSlug,
                                gameName = it.gameName,
                                title = it.title,
                                thumbnail = it.thumbnail,
                                createdAt = it.createdAt,
                            ).show(childFragmentManager, null)
                        }
                        true
                    }
                    else -> false
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.authenticationRequired.collect {
                        requestTwitchReauthorization()
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.notificationsEnabled.collectLatest {
                        if (it != null) {
                            toolbar.menu.findItem(R.id.toggleNotifications)?.apply {
                                if (it) {
                                    icon = ContextCompat.getDrawable(requireContext(), R.drawable.baseline_notifications_black_24)
                                    title = getString(R.string.disable_notifications)
                                } else {
                                    icon = ContextCompat.getDrawable(requireContext(), R.drawable.baseline_notifications_none_black_24)
                                    title = getString(R.string.enable_notifications)
                                }
                            }
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.notifications.collectLatest { pair ->
                        if (pair != null) {
                            val enabled = pair.first
                            val errorMessage = pair.second
                            if (!errorMessage.isNullOrBlank()) {
                                enableLiveNotificationsAfterChannel = false
                                Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_SHORT).show()
                            } else {
                                if (enabled) {
                                    Toast.makeText(requireContext(), R.string.enabled_notifications, Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(requireContext(), R.string.disabled_notifications, Toast.LENGTH_SHORT).show()
                                }
                                if (enabled && enableLiveNotificationsAfterChannel) {
                                    enableLiveNotificationsAfterChannel = false
                                    requireContext().prefs().edit { putBoolean(C.LIVE_NOTIFICATIONS_ENABLED, true) }
                                    LiveNotificationScheduler.enable(requireContext(), baselineOnly = true)
                                } else {
                                    LiveNotificationScheduler.refresh(requireContext())
                                }
                            }
                            viewModel.notifications.value = null
                        }
                    }
                }
            }
            if (setting == 0 || setting == 1) {
                val followButton = toolbar.menu.findItem(R.id.followButton)
                followButton?.isVisible = true
                followButton?.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
                profileFollow.isVisible = true
                profileFollow.isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.isFollowing.collectLatest {
                            if (it != null) {
                                profileFollow.isEnabled = true
                                profileFollow.text = getString(if (it) R.string.channel_following else R.string.follow)
                                profileFollow.setIconResource(if (it) R.drawable.baseline_favorite_black_24 else R.drawable.baseline_favorite_border_black_24)
                                followButton?.apply {
                                    if (it) {
                                        icon = ContextCompat.getDrawable(requireContext(), R.drawable.baseline_favorite_black_24)
                                        title = getString(R.string.unfollow)
                                    } else {
                                        icon = ContextCompat.getDrawable(requireContext(), R.drawable.baseline_favorite_border_black_24)
                                        title = getString(R.string.follow)
                                    }
                                }
                            }
                        }
                    }
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.follow.collectLatest { pair ->
                            if (pair != null) {
                                val following = pair.first
                                val errorMessage = pair.second
                                if (!errorMessage.isNullOrBlank()) {
                                    Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_SHORT).show()
                                } else {
                                    if (following) {
                                        Toast.makeText(requireContext(),
                                            getString(
                                                R.string.now_following,
                                                if (args.channelLogin != null && !args.channelLogin.equals(args.channelName, true)) {
                                                    when (requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                                                        "0" -> "${args.channelName}(${args.channelLogin})"
                                                        "1" -> args.channelName
                                                        else -> args.channelLogin
                                                    }
                                                } else {
                                                    args.channelName
                                                }
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Toast.makeText(requireContext(),
                                            getString(
                                                R.string.unfollowed,
                                                if (args.channelLogin != null && !args.channelLogin.equals(args.channelName, true)) {
                                                    when (requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                                                        "0" -> "${args.channelName}(${args.channelLogin})"
                                                        "1" -> args.channelName
                                                        else -> args.channelLogin
                                                    }
                                                } else {
                                                    args.channelName
                                                }
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                                viewModel.follow.value = null
                            }
                        }
                    }
                }
            }
            val tabList = requireContext().prefs().getString(C.UI_CHANNEL_TABS, null).let { tabPref ->
                val defaultTabs = C.DEFAULT_CHANNEL_TABS.split(',')
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
            val tabs = tabList.mapNotNull {
                val split = it.split(':')
                val key = split[0]
                val enabled = split[2] != "0"
                if (enabled) {
                    key
                } else {
                    null
                }
            }
            if (tabs.size <= 1) {
                tabLayout.visibility = View.GONE
            } else {
                if (tabs.size >= 5) {
                    tabLayout.tabGravity = TabLayout.GRAVITY_CENTER
                    tabLayout.tabMode = TabLayout.MODE_SCROLLABLE
                }
            }
            val adapter = ChannelPagerAdapter(this@ChannelPagerFragment, args, tabs)
            viewPager.adapter = adapter
            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                private val layoutParams = channelHero.layoutParams as AppBarLayout.LayoutParams
                private val originalScrollFlags = layoutParams.scrollFlags

                override fun onPageScrollStateChanged(state: Int) {
                    dispatchPagerScrollState(state != ViewPager2.SCROLL_STATE_IDLE)
                }

                override fun onPageSelected(position: Int) {
                    layoutParams.scrollFlags = if (tabs.getOrNull(position) != "3") {
                        originalScrollFlags
                    } else {
                        appBar.setExpanded(false, isResumed)
                        appBar.background = null
                        AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL
                    }
                    viewPager.doOnLayout {
                        childFragmentManager.findFragmentByTag("f${position}").let { fragment ->
                            if (fragment is Sortable) {
                                fragment.setupSortBar(sortBar)
                            } else {
                                sortBar.root.visibility = View.GONE
                            }
                        }
                    }
                }
            })
            if (firstLaunch) {
                val defaultItem = tabList.find { it.split(':')[1] != "0" }?.split(':')[0] ?: "1"
                viewPager.setCurrentItem(
                    tabs.indexOf(defaultItem).takeIf { it != -1 } ?: tabs.indexOf("1").takeIf { it != -1 } ?: 0,
                    false
                )
                firstLaunch = false
            }
            viewPager.configureForSmoothPaging()
            TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                tab.text = when (tabs.getOrNull(position)) {
                    "0" -> getString(R.string.suggestions)
                    "1" -> getString(R.string.videos)
                    "2" -> getString(R.string.clips)
                    "3" -> getString(R.string.chat)
                    "4" -> getString(R.string.about)
                    else -> getString(R.string.videos)
                }
            }.attach()
            tabLayout.setTabCustomizationLongPress(requireContext(), C.UI_CHANNEL_TABS)
            ViewCompat.setOnApplyWindowInsetsListener(coordinatorLayout) { _, windowInsets ->
                val topInset = maxOf(
                    windowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top,
                    windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout()).top,
                )
                val toolbarHeight = requireContext().obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.actionBarSize)).use {
                    it.getDimensionPixelSize(0, (56 * resources.displayMetrics.density).toInt())
                }
                toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = topInset }
                coordinatorLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = topInset + toolbarHeight }
                statusBarScrim.updateLayoutParams { height = topInset }
                val navigationRailIsVisible = activity.findViewById<View>(R.id.navBarContainer)?.isVisible == false
                toolbarContainer2.updatePadding(bottom = if (navigationRailIsVisible) {
                    windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                } else 0)
                windowInsets
            }
            ViewCompat.requestApplyInsets(coordinatorLayout)
            appBar.addOnOffsetChangedListener { bar, offset ->
                val collapsed = bar.totalScrollRange > 0 && -offset >= bar.totalScrollRange
                val name = if (collapsed) userName.text else null
                if (toolbar.title != name) toolbar.title = name
            }
        }
        view.doOnLayout {
            if (isAdded) scrollToTop()
        }
    }

    override fun initialize() {
        viewModel.loadStream(
            requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            TwitchApiHelper.getGQLHeaders(requireContext()),
            TwitchApiHelper.getHelixHeaders(requireContext()),
            false,
        )
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.stream.collectLatest { stream ->
                    if (stream != null) {
                        updateStreamLayout(stream)
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.user.collectLatest { user ->
                    if (user != null) {
                        updateUserLayout(user)
                    }
                }
            }
        }
        viewModel.isFollowingChannel(
            requireContext().tokenPrefs().getString(C.USER_ID, null),
            args.channelId,
            args.channelLogin,
            requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
            requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            TwitchApiHelper.getGQLHeaders(requireContext(), true),
            TwitchApiHelper.getHelixHeaders(requireContext()),
        )
    }

    private fun updateStreamLayout(stream: Stream?) {
        with(binding) {
            val activity = requireActivity() as MainActivity
            val isLive = stream?.viewerCount != null
            liveStatus.isVisible = isLive
            streamLayout.isVisible = isLive
            lastBroadcast.isVisible = !isLive && !lastBroadcast.text.isNullOrBlank()
            watchLive.text = getString(if (isLive) R.string.watch_live else R.string.open_player)
            watchLive.setOnClickListener { activity.startStream(stream ?: Stream(channelId = args.channelId, channelLogin = args.channelLogin)) }
            stream?.channelImage.let {
                if (it != null) {
                    userLayout.visibility = View.VISIBLE
                    userImage.visibility = View.VISIBLE
                    if (userImage.tag != it || userImage.drawable == null) {
                        userImage.tag = it
                        requireContext().imageLoader.enqueue(
                            ImageRequest.Builder(requireContext()).apply {
                                data(it)
                                if (requireContext().prefs().getBoolean(C.UI_ROUND_USER_IMAGE, true)) {
                                    transformations(CircleCropTransformation())
                                }
                                crossfade(true)
                                target(userImage)
                            }.build()
                        )
                    }
                    requireArguments().putString(C.CHANNEL_IMAGE, it)
                }
            }
            stream?.channelName.let {
                if (it != null && it != args.channelName) {
                    userLayout.visibility = View.VISIBLE
                    userName.visibility = View.VISIBLE
                    userName.text = if (stream?.channelLogin != null && !stream.channelLogin.equals(it, true)) {
                        when (requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                            "0" -> "${it}(${stream.channelLogin})"
                            "1" -> it
                            else -> stream.channelLogin
                        }
                    } else {
                        it
                    }
                    requireArguments().putString(C.CHANNEL_NAME, it)
                }
            }
            stream?.channelLogin.let {
                if (it != null && it != args.channelLogin) {
                    requireArguments().putString(C.CHANNEL_LOGIN, it)
                }
            }
            stream?.id.let {
                if (it != null && it != args.streamId) {
                    requireArguments().putString(C.STREAM_ID, it)
                }
            }
            if (!stream?.title.isNullOrBlank()) {
                streamLayout.visibility = View.VISIBLE
                title.visibility = View.VISIBLE
                title.text = stream.title?.trim()
            } else {
                title.visibility = View.GONE
            }
            if (!stream?.gameName.isNullOrBlank()) {
                streamLayout.visibility = View.VISIBLE
                gameName.visibility = View.VISIBLE
                gameName.text = stream.gameName
                gameName.setOnClickListener {
                    findNavController().navigate(GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                        gameId = stream.gameId,
                        gameSlug = stream.gameSlug,
                        gameName = stream.gameName
                    ))
                }
            } else {
                gameName.visibility = View.GONE
            }
            if (stream?.viewerCount != null) {
                streamLayout.visibility = View.VISIBLE
                viewers.visibility = View.VISIBLE
                val count = stream.viewerCount ?: 0
                viewers.text = resources.getQuantityString(
                    R.plurals.viewers,
                    count,
                    TwitchApiHelper.formatCount(count, requireContext().prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true))
                )
            } else {
                viewers.visibility = View.GONE
            }
            if (requireContext().prefs().getBoolean(C.UI_UPTIME, true)) {
                if (stream?.createdAt != null) {
                    val text = stream.createdAt?.let {
                        Instant.parseOrNull(it)?.takeIf { time -> time.toEpochMilliseconds() > 0 }?.let { createdAt ->
                            val uptime = Clock.System.now() - createdAt
                            if (uptime.isPositive()) {
                                DateUtils.formatElapsedTime(uptime.inWholeSeconds)
                            } else null
                        }
                    }
                    if (text != null) {
                        streamLayout.visibility = View.VISIBLE
                        uptime.visibility = View.VISIBLE
                        uptime.text = getString(R.string.uptime, text)
                    } else {
                        uptime.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun updateUserLayout(user: User) {
        with(binding) {
            if (viewModel.stream.value?.viewerCount == null && user.lastBroadcast != null) {
                val text = user.lastBroadcast?.let {
                    Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 }?.let { time ->
                        TwitchApiHelper.formatDate(requireContext(), time)
                    }
                }
                if (text != null)  {
                    lastBroadcast.visibility = View.VISIBLE
                    lastBroadcast.text = getString(R.string.last_broadcast_date, text)
                } else {
                    lastBroadcast.visibility = View.GONE
                }
            }
            if (!user.profileImage.isNullOrBlank()) {
                userLayout.visibility = View.VISIBLE
                userImage.visibility = View.VISIBLE
                if (userImage.tag != user.profileImage || userImage.drawable == null) {
                    userImage.tag = user.profileImage
                    requireContext().imageLoader.enqueue(
                        ImageRequest.Builder(requireContext()).apply {
                            data(user.profileImage)
                            if (requireContext().prefs().getBoolean(C.UI_ROUND_USER_IMAGE, true)) {
                                transformations(CircleCropTransformation())
                            }
                            crossfade(true)
                            target(userImage)
                        }.build()
                    )
                }
                requireArguments().putString(C.CHANNEL_IMAGE, user.profileImage)
            }
            if (user.bannerImageURL != null) {
                bannerImage.visibility = View.VISIBLE
                requireContext().imageLoader.enqueue(
                    ImageRequest.Builder(requireContext()).apply {
                        data(user.bannerImageURL)
                        crossfade(true)
                        target(bannerImage)
                    }.build()
                )
            } else {
                bannerImage.visibility = View.VISIBLE
                bannerImage.setImageDrawable(null)
            }
            if (user.createdAt != null) {
                val text = Instant.parseOrNull(user.createdAt)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 }?.let {
                    TwitchApiHelper.formatDate(requireContext(), it)
                }
                userCreated.isVisible = text != null
                userCreated.text = getString(R.string.created_at, text)
            } else {
                userCreated.visibility = View.GONE
            }
            if (user.followerCount != null) {
                val count = user.followerCount
                userFollowers.visibility = View.VISIBLE
                userFollowers.text = resources.getQuantityString(
                    R.plurals.followers,
                    count,
                    TwitchApiHelper.formatCount(count, requireContext().prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true))
                )
            } else {
                userFollowers.visibility = View.GONE
            }
            val broadcasterType = when (user.broadcasterType?.lowercase()) {
                "partner" -> getString(R.string.user_partner)
                "affiliate" -> getString(R.string.user_affiliate)
                else -> null
            }
            val type = when (user.type?.lowercase()) {
                "staff" -> getString(R.string.user_staff)
                else -> null
            }
            val typeString = if (broadcasterType != null && type != null) "$broadcasterType, $type" else broadcasterType ?: type
            if (typeString != null) {
                userType.visibility = View.VISIBLE
                userType.text = typeString
            } else {
                userType.visibility = View.GONE
            }
            updateCompactHero()
            if (args.updateLocal) {
                viewModel.updateLocalUser(requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP), requireContext().filesDir.path, user)
            }
        }
    }

    override fun onNetworkRestored() {
        viewModel.loadStream(
            requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            TwitchApiHelper.getGQLHeaders(requireContext()),
            TwitchApiHelper.getHelixHeaders(requireContext()),
            revalidate = true,
        )
    }

    private fun isCompactLandscape(): Boolean = resources.configuration.let {
        it.orientation == Configuration.ORIENTATION_LANDSCAPE && it.screenHeightDp < 480
    }

    private fun updateCompactHero() {
        val compact = isCompactLandscape()
        val density = resources.displayMetrics.density
        with(binding) {
            bannerImage.isVisible = !compact
            bannerImage.updateLayoutParams { height = ((if (compact) 0 else if (resources.configuration.screenWidthDp < 360) 72 else 88) * density).toInt() }
            userImage.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                width = ((if (compact) 48 else 72) * density).toInt()
                height = width
                topMargin = ((if (compact) 0 else -24) * density).toInt()
            }
            creatorIdentity.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = ((if (compact) 0 else 12) * density).toInt()
            }
            userName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 20f else 24f)
            userCreated.isVisible = !compact && viewModel.user.value?.createdAt?.let { Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 } } != null
            title.maxLines = if (compact) 1 else 2
            watchLive.setIconResource(if (compact || resources.configuration.screenWidthDp < 360) 0 else R.drawable.baseline_play_arrow_black_48)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateCompactHero()
        binding.appBar.setExpanded(!isCompactLandscape(), false)
    }

    override fun scrollToTop() {
        if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            binding.appBar.setExpanded(true, true)
        }
        (currentFragment as? Scrollable)?.scrollToTop()
    }

    override fun onDestroyView() {
        dispatchPagerScrollState(false)
        pendingNotificationEnable = null
        enableLiveNotificationsAfterChannel = false
        super.onDestroyView()
        _binding = null
    }

    private fun withLiveNotificationPermission(action: () -> Unit) {
        val context = requireContext()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationEnable = action
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else if (LiveNotificationScheduler.canPostNotifications(context)) {
            action()
        } else {
            Toast.makeText(context, R.string.live_notifications_blocked, Toast.LENGTH_LONG).show()
        }
    }
}




