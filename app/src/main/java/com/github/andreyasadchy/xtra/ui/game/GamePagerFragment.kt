package com.github.andreyasadchy.xtra.ui.game

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.constraintlayout.helper.widget.Flow
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
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
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentGamePagerBinding
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.FragmentHost
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.Sortable
import com.github.andreyasadchy.xtra.ui.common.dispatchPagerScrollState
import com.github.andreyasadchy.xtra.ui.game.GamePagerViewModel.Companion.GamePagerViewModelFactory
import com.github.andreyasadchy.xtra.ui.games.GamesFragmentDirections
import com.github.andreyasadchy.xtra.ui.login.TwitchWebLoginActivity
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.ui.settings.setTabCustomizationLongPress
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.applyStableTopSystemBarMargin
import com.github.andreyasadchy.xtra.util.configureForSmoothPaging
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class GamePagerFragment : BaseNetworkFragment(), Scrollable, FragmentHost {

    override val initializeWithoutNetwork = true

    private var _binding: FragmentGamePagerBinding? = null
    private val binding get() = _binding!!
    private val args: GamePagerFragmentArgs by navArgs()
    private val viewModel: GamePagerViewModel by viewModels { GamePagerViewModelFactory }
    private var firstLaunch = true
    private var gameTitle = ""
    private var compactLandscapeAutoCollapseApplied = false

    override val currentFragment: Fragment?
        get() = childFragmentManager.findFragmentByTag("f${binding.viewPager.currentItem}")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firstLaunch = savedInstanceState == null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGamePagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(binding) {
            val activity = requireActivity() as MainActivity
            gameTitle = args.gameName.orEmpty()
            val visibilityOwnedIds = intArrayOf(
                gameLayout.id,
                gameImage.id,
                gameName.id,
                viewers.id,
                broadcastersCount.id,
                followers.id,
                tagsLayout.id,
            )
            val compactHeroConstraints = ConstraintSet().apply {
                clone(toolbarContainer)
                visibilityOwnedIds.forEach { setVisibilityMode(it, ConstraintSet.VISIBILITY_MODE_IGNORE) }
            }
            val wideHeroConstraints = ConstraintSet().apply {
                clone(toolbarContainer)
                visibilityOwnedIds.forEach { setVisibilityMode(it, ConstraintSet.VISIBILITY_MODE_IGNORE) }
                val gutter = resources.getDimensionPixelSize(R.dimen.channel_hero_wide_gutter)
                connect(gameLayout.id, ConstraintSet.END, gameHeroSplit.id, ConstraintSet.START, gutter)
                connect(tagsLayout.id, ConstraintSet.START, gameHeroSplit.id, ConstraintSet.END, gutter)
                connect(tagsLayout.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            }
            var heroUsesWideLayout: Boolean? = null
            var heroOrientation: Int? = null
            toolbarContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val useWideLayout = toolbarContainer.width >= resources.getDimensionPixelSize(
                    R.dimen.game_hero_expanded_min_width,
                )
                val orientation = resources.configuration.orientation
                val heroLayoutChanged = heroUsesWideLayout != useWideLayout
                val orientationChanged = heroOrientation != orientation
                if (!heroLayoutChanged && !orientationChanged) return@addOnLayoutChangeListener
                if (heroLayoutChanged) {
                    heroUsesWideLayout = useWideLayout
                    if (useWideLayout) wideHeroConstraints.applyTo(toolbarContainer)
                    else compactHeroConstraints.applyTo(toolbarContainer)
                }
                heroOrientation = orientation
                val shouldAutoCollapse = orientation == Configuration.ORIENTATION_LANDSCAPE && !useWideLayout
                if (shouldAutoCollapse != compactLandscapeAutoCollapseApplied) {
                    appBar.post {
                        val isCompactLandscape =
                            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                                toolbarContainer.width < resources.getDimensionPixelSize(
                                    R.dimen.game_hero_expanded_min_width,
                                )
                        if (isCompactLandscape && !compactLandscapeAutoCollapseApplied) {
                            appBar.setExpanded(false, false)
                            compactLandscapeAutoCollapseApplied = true
                        } else if (!isCompactLandscape && compactLandscapeAutoCollapseApplied) {
                            appBar.setExpanded(true, false)
                            compactLandscapeAutoCollapseApplied = false
                        }
                    }
                }
            }
            if (args.gameName != null) {
                gameLayout.visibility = View.VISIBLE
                gameName.visibility = View.GONE
                gameName.text = args.gameName
            } else {
                gameName.visibility = View.GONE
            }
            if (args.boxArt != null) {
                gameLayout.visibility = View.VISIBLE
                gameImage.visibility = View.VISIBLE
                gameImage.tag = args.boxArt
                requireContext().imageLoader.enqueue(
                    ImageRequest.Builder(requireContext()).apply {
                        data(args.boxArt)
                        crossfade(true)
                        target(gameImage)
                    }.build()
                )
            } else {
                gameImage.visibility = View.GONE
                gameImage.tag = null
            }
            val isLoggedIn = !TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                    !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank()
            val setting = requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0
            val navController = findNavController()
            val appBarConfiguration = AppBarConfiguration(setOf(R.id.rootGamesFragment, R.id.rootTopFragment, R.id.followPagerFragment, R.id.followMediaFragment, R.id.savedPagerFragment, R.id.savedMediaFragment))
            toolbar.setupWithNavController(navController, appBarConfiguration)
            collapsingToolbar.setTitleEnabled(false)
            toolbar.title = gameTitle
            toolbar.menu.findItem(R.id.login).title = if (isLoggedIn) getString(R.string.log_out) else getString(R.string.log_in)
            toolbar.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.followButton -> {
                        viewModel.isFollowing.value?.let {
                            if (it) {
                                requireContext().getAlertDialogBuilder()
                                    .setMessage(getString(R.string.unfollow_channel, args.gameName))
                                    .setNegativeButton(getString(R.string.no), null)
                                    .setPositiveButton(getString(R.string.yes)) { _, _ ->
                                        viewModel.deleteFollowGame(
                                            args.gameId,
                                            setting,
                                            requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                            TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                        )
                                    }
                                    .show()
                            } else {
                                viewModel.saveFollowGame(
                                    args.gameId,
                                    args.gameSlug,
                                    args.gameName,
                                    setting,
                                    requireContext().filesDir.path,
                                    requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                    TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                    TwitchApiHelper.getHelixHeaders(requireContext()),
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
                    else -> false
                }
            }
            if (setting < 2) {
                val followButton = toolbar.menu.findItem(R.id.followButton)
                followButton?.isVisible = true
                viewLifecycleOwner.lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.isFollowing.collectLatest {
                            if (it != null) {
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
                                        Toast.makeText(requireContext(), getString(R.string.now_following, args.gameName), Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(requireContext(), getString(R.string.unfollowed, args.gameName), Toast.LENGTH_SHORT).show()
                                    }
                                }
                                viewModel.follow.value = null
                            }
                        }
                    }
                }
            }
            val tabList = requireContext().prefs().getString(C.UI_GAME_TABS, null).let { tabPref ->
                val defaultTabs = C.DEFAULT_GAME_TABS.split(',')
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
            val adapter = GamePagerAdapter(this@GamePagerFragment, tabs)
            viewPager.adapter = adapter
            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrollStateChanged(state: Int) {
                    dispatchPagerScrollState(state != ViewPager2.SCROLL_STATE_IDLE)
                }

                override fun onPageSelected(position: Int) {
                    viewPager.doOnLayout {
                        childFragmentManager.findFragmentByTag("f${position}")?.let { fragment ->
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
                    "0" -> getString(R.string.videos)
                    "1" -> getString(R.string.live)
                    "2" -> getString(R.string.clips)
                    else -> getString(R.string.live)
                }
            }.attach()
            tabLayout.setTabCustomizationLongPress(requireContext(), C.UI_GAME_TABS)
            view.applyStableTopSystemBarMargin(collapsingToolbar)
        }
    }

    override fun initialize() {
        viewModel.loadGame(
            requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            TwitchApiHelper.getGQLHeaders(requireContext()),
            TwitchApiHelper.getHelixHeaders(requireContext()),
            false,
        )
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.game.collectLatest { game ->
                    if (game != null) {
                        updateGameLayout(game)
                    }
                }
            }
        }
        val setting = requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0
        if (setting < 2) {
            viewModel.isFollowingGame(
                args.gameId,
                setting,
                requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                TwitchApiHelper.getGQLHeaders(requireContext(), true),
            )
        }
        if (args.updateLocal) {
            viewModel.updateLocalGame(
                requireContext().filesDir.path,
                args.gameId,
                args.gameName,
                requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                TwitchApiHelper.getGQLHeaders(requireContext()),
                TwitchApiHelper.getHelixHeaders(requireContext()),
            )
        }
    }

    private fun updateGameLayout(game: Game?) {
        with(binding) {
            gameTitle = game?.name ?: args.gameName.orEmpty()
            toolbar.title = gameTitle
            val boxArt = game?.boxArtURL
                ?.takeIf { it.isNotBlank() }
                ?.let(TwitchApiHelper::getGameBoxArt)
            if (!boxArt.isNullOrBlank()) {
                gameLayout.visibility = View.VISIBLE
                gameImage.visibility = View.VISIBLE
                if (gameImage.tag != boxArt || gameImage.drawable == null) {
                    gameImage.tag = boxArt
                    requireContext().imageLoader.enqueue(
                        ImageRequest.Builder(requireContext()).apply {
                            data(boxArt)
                            crossfade(true)
                            target(gameImage)
                        }.build()
                    )
                }
            }
            if (game?.name != null && game.name != args.gameName) {
                gameLayout.visibility = View.VISIBLE
                gameName.visibility = View.GONE
                gameName.text = game.name
            }
            if (game?.viewerCount != null) {
                viewers.visibility = View.VISIBLE
                val count = game.viewerCount ?: 0
                viewers.text = resources.getQuantityString(
                    R.plurals.viewers,
                    count,
                    TwitchApiHelper.formatCount(count, requireContext().prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true))
                )
            } else {
                viewers.visibility = View.GONE
            }
            if (game?.broadcasterCount != null && requireContext().prefs().getBoolean(C.UI_BROADCASTERS_COUNT, true)) {
                broadcastersCount.visibility = View.VISIBLE
                val count = game.broadcasterCount ?: 0
                broadcastersCount.text = resources.getQuantityString(
                    R.plurals.broadcasters,
                    count,
                    TwitchApiHelper.formatCount(count, requireContext().prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true))
                )
            } else {
                broadcastersCount.visibility = View.GONE
            }
            if (game?.followerCount != null) {
                followers.visibility = View.VISIBLE
                val count = game.followerCount
                followers.text = resources.getQuantityString(
                    R.plurals.followers,
                    count,
                    TwitchApiHelper.formatCount(count, requireContext().prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true))
                )
            } else {
                followers.visibility = View.GONE
            }
            if (!game?.tags.isNullOrEmpty() && requireContext().prefs().getBoolean(C.UI_TAGS, true)) {
                tagsLayout.removeAllViews()
                tagsLayout.visibility = View.VISIBLE
                val tagsFlowLayout = Flow(requireContext()).apply {
                    layoutParams = ConstraintLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topToTop = tagsLayout.id
                        bottomToBottom = tagsLayout.id
                        startToStart = tagsLayout.id
                        endToEnd = tagsLayout.id
                    }
                    setWrapMode(Flow.WRAP_CHAIN)
                    setHorizontalStyle(ConstraintSet.CHAIN_PACKED)
                    setHorizontalBias(0f)
                    setVerticalStyle(ConstraintSet.CHAIN_PACKED)
                }
                tagsLayout.addView(tagsFlowLayout)
                val ids = mutableListOf<Int>()
                for (tag in game.tags) {
                    val chip = Chip(requireContext())
                    val id = View.generateViewId()
                    chip.id = id
                    ids.add(id)
                    chip.text = tag.name
                    chip.isClickable = tag.id != null
                    chip.isFocusable = tag.id != null
                    if (tag.id != null) {
                        chip.setOnClickListener {
                            findNavController().navigate(
                                GamesFragmentDirections.actionGlobalGamesFragment(
                                    tags = arrayOf(tag)
                                )
                            )
                        }
                    }
                    tagsLayout.addView(chip)
                }
                tagsFlowLayout.referencedIds = ids.toIntArray()
            } else {
                tagsLayout.visibility = View.GONE
            }
        }
    }

    override fun scrollToTop() {
        binding.appBar.setExpanded(true, true)
        (currentFragment as? Scrollable)?.scrollToTop()
    }

    override fun onNetworkRestored() {
        viewModel.loadGame(
            requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            TwitchApiHelper.getGQLHeaders(requireContext()),
            TwitchApiHelper.getHelixHeaders(requireContext()),
            revalidate = true,
        )
    }

    override fun onDestroyView() {
        dispatchPagerScrollState(false)
        super.onDestroyView()
        _binding = null
    }
}




