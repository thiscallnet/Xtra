package com.github.andreyasadchy.xtra.ui.discover

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.FragmentDiscoverBinding
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.StreamPreviewCandidate
import com.github.andreyasadchy.xtra.ui.common.StreamPreloadViewportController
import com.github.andreyasadchy.xtra.repository.streamfeed.RefreshReason
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.following.overview.FollowingOverviewAdapter
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.login.TwitchWebLoginActivity
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DiscoverFragment : BaseNetworkFragment(), Scrollable {

    override val initializeWithoutNetwork = true

    private var _binding: FragmentDiscoverBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DiscoverViewModel by viewModels { DiscoverViewModel.Factory }
    private lateinit var adapter: FollowingOverviewAdapter
    private val streamShelfPreloadControllers = mutableMapOf<String, StreamPreloadViewportController>()
    private var discoverScrolling = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDiscoverBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as MainActivity
        val navController = findNavController()
        val isLoggedIn = !TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
            !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank()
        binding.toolbar.setupWithNavController(
            navController,
            AppBarConfiguration(
                setOf(
                    R.id.rootGamesFragment,
                    R.id.rootDiscoverFragment,
                    R.id.rootTopFragment,
                    R.id.followPagerFragment,
                    R.id.followMediaFragment,
                    R.id.savedPagerFragment,
                    R.id.savedMediaFragment,
                ),
            ),
        )
        binding.toolbar.menu.findItem(R.id.login).title = if (isLoggedIn) getString(R.string.log_out) else getString(R.string.log_in)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.search -> {
                    navController.navigate(SearchPagerFragmentDirections.actionGlobalSearchPagerFragment())
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
                            setPositiveButton(getString(R.string.yes)) { _, _ ->
                                activity.logoutResultLauncher?.launch(Intent(activity, TwitchWebLoginActivity::class.java).putExtra(TwitchWebLoginActivity.EXTRA_LOGOUT, true))
                            }
                        }.show()
                    } else {
                        activity.loginResultLauncher?.launch(Intent(activity, TwitchWebLoginActivity::class.java))
                    }
                    true
                }
                else -> false
            }
        }
        adapter = FollowingOverviewAdapter(
            onStreamClick = { stream -> activity.startStream(stream) },
            onVideoClick = {},
            onUpcomingClick = { upcoming ->
                navController.navigate(
                    ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                        channelId = upcoming.channelId,
                        channelLogin = upcoming.channelLogin,
                        channelName = upcoming.channelName,
                        channelImage = upcoming.channelImageURL?.let(TwitchApiHelper::getProfileImage),
                    ),
                )
            },
            onSeeAll = { key -> showAll(key) },
            onGameClick = { game ->
                navController.navigate(
                    com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                        gameId = game.id,
                        gameSlug = game.slug,
                        gameName = game.name,
                        boxArt = game.boxArt,
                    ),
                )
            },
            onStreamShelfAttached = { key, recyclerView, streamAtPosition, isFeatured ->
                streamShelfPreloadControllers.remove(key)?.stop()
                StreamPreloadViewportController(
                    fragment = this,
                    coordinator = (requireActivity().application as XtraApp).xtraModule.streamPreloadCoordinator,
                    viewportKey = "discover:$key",
                    recyclerView = recyclerView,
                    streamAtPosition = streamAtPosition,
                    isParentScrolling = { discoverScrolling },
                    previewAtPosition = if (isFeatured) {
                        { position, surface ->
                            if (centeredAdapterPosition(recyclerView) != position) {
                                null
                            } else {
                                streamAtPosition(position)?.let { stream ->
                                    stream.channelLogin?.trim()?.takeIf { it.isNotEmpty() }?.let { login ->
                                        StreamPreviewCandidate(
                                            streamKey = "channel:${stream.channelId ?: login}",
                                            channelLogin = login,
                                            visibleFraction = 0f,
                                            centerProximity = 0f,
                                            title = stream.title,
                                            channelName = stream.channelName,
                                            channelLogo = stream.channelImage,
                                            surface = surface,
                                        )
                                    }
                                }
                            }
                        }
                    } else null,
                ).also {
                    streamShelfPreloadControllers[key] = it
                    it.start()
                }
            },
            onStreamShelfDetached = { key ->
                streamShelfPreloadControllers.remove(key)?.stop()
            },
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = this@DiscoverFragment.adapter
            itemAnimator = null
            clipToPadding = false
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    discoverScrolling = newState != RecyclerView.SCROLL_STATE_IDLE
                    streamShelfPreloadControllers.values.forEach(StreamPreloadViewportController::onParentScrollStateChanged)
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    binding.appBar.isLifted = recyclerView.canScrollVertically(-1)
                }
            })
            addOnLayoutChangeListener { recyclerView, _, _, right, _, _, _, _, _ ->
                val density = resources.displayMetrics.density
                val maxContentWidth = (1200 * density).toInt()
                val minimumGutter = (16 * density).toInt()
                val sidePadding = maxOf(minimumGutter, (right - maxContentWidth) / 2)
                updatePadding(left = sidePadding, right = sidePadding)
            }
        }
        binding.appBar.setLiftOnScrollTargetView(binding.recyclerView)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = insets.top }
            binding.recyclerView.updatePadding(bottom = insets.bottom)
            windowInsets
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    while (isActive) {
                        delay(90_000L)
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            viewModel.refresh(RefreshReason.SCREEN_VISIBLE)
                        }
                    }
                }
                viewModel.state.collectLatest { state ->
                    adapter.submitList(state.sections)
                    binding.emptyState.isVisible = !state.isLoading && state.hasError
                }
            }
        }
    }

    override fun initialize() {
        viewModel.refresh(RefreshReason.INITIAL)
    }

    override fun onNetworkRestored() {
        viewModel.refresh(RefreshReason.NETWORK_RESTORED, force = true)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh(RefreshReason.SCREEN_VISIBLE)
        streamShelfPreloadControllers.values.forEach(StreamPreloadViewportController::onResume)
    }

    override fun onPause() {
        streamShelfPreloadControllers.values.forEach(StreamPreloadViewportController::onPause)
        super.onPause()
    }

    private fun showAll(key: String) {
        when (key) {
            DiscoverViewModel.KEY_CATEGORIES -> findNavController().navigate(R.id.action_global_gamesFragment)
            DiscoverViewModel.KEY_TRENDING -> viewModel.state.value.trendingGame?.let { game ->
                findNavController().navigate(
                    GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                        gameId = game.id,
                        gameSlug = game.slug,
                        gameName = game.name,
                        boxArt = game.boxArt,
                    ),
                )
            }
        }
    }

    private fun centeredAdapterPosition(recyclerView: RecyclerView): Int? {
        val center = recyclerView.width / 2
        return (0 until recyclerView.childCount)
            .mapNotNull { index ->
                val child = recyclerView.getChildAt(index)
                val position = recyclerView.getChildAdapterPosition(child)
                position.takeIf { it != RecyclerView.NO_POSITION }
                    ?.let { it to kotlin.math.abs((child.left + child.right) / 2 - center) }
            }
            .minByOrNull { it.second }
            ?.first
    }

    override fun scrollToTop() {
        binding.appBar.setExpanded(true, true)
        binding.recyclerView.scrollToPosition(0)
    }

    override fun onDestroyView() {
        streamShelfPreloadControllers.values.forEach(StreamPreloadViewportController::stop)
        streamShelfPreloadControllers.clear()
        discoverScrolling = false
        super.onDestroyView()
        _binding = null
    }
}
