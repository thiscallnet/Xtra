package com.github.andreyasadchy.xtra.ui.following.overview

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.FragmentFollowingOverviewBinding
import com.github.andreyasadchy.xtra.model.VideoHistory
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.streamfeed.RefreshReason
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.StreamFeedScreenController
import com.github.andreyasadchy.xtra.ui.common.StreamPreloadViewportController
import com.github.andreyasadchy.xtra.ui.following.FollowMediaFragment
import com.github.andreyasadchy.xtra.ui.following.FollowPagerFragment
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.ui.following.overview.FollowingOverviewViewModel.Companion.FollowingOverviewViewModelFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class FollowingOverviewFragment : BaseNetworkFragment(), Scrollable {

    override val initializeWithoutNetwork = true

    private var _binding: FragmentFollowingOverviewBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FollowingOverviewViewModel by viewModels { FollowingOverviewViewModelFactory }
    private lateinit var overviewAdapter: FollowingOverviewAdapter
    private lateinit var streamFeedScreenController: StreamFeedScreenController
    private val streamShelfPreloadControllers = mutableMapOf<String, StreamPreloadViewportController>()
    private var overviewScrolling = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFollowingOverviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        overviewAdapter = FollowingOverviewAdapter(
            onStreamClick = { stream -> (activity as? MainActivity)?.startStream(stream) },
            onVideoClick = { item ->
                item.toVideo().let { video -> (activity as? MainActivity)?.startVideo(video, item.position, ignoreSavedPosition = true) }
            },
            onUpcomingClick = { item ->
                findNavController().navigate(
                    ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                        channelId = item.channelId,
                        channelLogin = item.channelLogin,
                        channelName = item.channelName,
                        channelImage = item.channelImageURL?.let(TwitchApiHelper::getProfileImage),
                    )
                )
            },
            onSeeAll = ::showAll,
            onStreamShelfAttached = { key, recyclerView, streamAtPosition ->
                streamShelfPreloadControllers.remove(key)?.stop()
                StreamPreloadViewportController(
                    fragment = this,
                    coordinator = (requireActivity().application as XtraApp).xtraModule.streamPreloadCoordinator,
                    viewportKey = "following-overview:$key",
                    recyclerView = recyclerView,
                    streamAtPosition = streamAtPosition,
                    isParentScrolling = { overviewScrolling },
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
            adapter = overviewAdapter
            itemAnimator = null
            clipToPadding = false
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    overviewScrolling = newState != RecyclerView.SCROLL_STATE_IDLE
                    streamShelfPreloadControllers.values.forEach(StreamPreloadViewportController::onParentScrollStateChanged)
                }
            })
            addOnLayoutChangeListener { recyclerView, _, _, right, _, _, _, _, _ ->
                val density = resources.displayMetrics.density
                val maxContentWidth = (1200 * density).toInt()
                val minimumGutter = (16 * density).toInt()
                val sidePadding = maxOf(minimumGutter, (right - maxContentWidth) / 2)
                if (paddingLeft != sidePadding || paddingRight != sidePadding) {
                    updatePadding(left = sidePadding, right = sidePadding)
                }
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.recyclerView.updatePadding(bottom = insets.bottom + resources.getDimensionPixelSize(R.dimen.following_overview_bottom_padding))
            windowInsets
        }
        streamFeedScreenController = StreamFeedScreenController(
            fragment = this,
            coordinator = viewModel.refreshCoordinator,
            specProvider = viewModel::currentFeedSpec,
        ).also { it.start() }
    }

    override fun initialize() {
        viewModel.syncCurrentAccount()
        viewModel.refreshOverviewSections()
        streamFeedScreenController.onSpecChanged(force = false, reason = RefreshReason.SCREEN_VISIBLE)
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val sections = combine(
                    viewModel.liveStreams,
                    viewModel.recommendedStreams,
                    viewModel.recommendationsLoading,
                    viewModel.continueWatching,
                    viewModel.overviewSectionKeys,
                ) { live, recommended, recommendationsLoading, continueWatching, sectionKeys ->
                    val availableSections = mapOf(
                        FollowingOverviewSections.LIVE to FollowingOverviewSection(
                            key = FollowingOverviewSections.LIVE,
                            titleRes = R.string.following_live_channels,
                            emptyRes = R.string.following_no_live_channels,
                            streams = live,
                        ),
                        FollowingOverviewSections.RECOMMENDED to FollowingOverviewSection(
                            key = FollowingOverviewSections.RECOMMENDED,
                            titleRes = R.string.following_recommended_channels,
                            emptyRes = R.string.following_no_recommended_channels,
                            streams = recommended,
                            isLoading = recommendationsLoading && recommended.isEmpty(),
                            showSeeAll = false,
                        ),
                        FollowingOverviewSections.CONTINUE to FollowingOverviewSection(
                            key = FollowingOverviewSections.CONTINUE,
                            titleRes = R.string.following_continue_watching,
                            emptyRes = R.string.following_no_continue_watching,
                            videos = continueWatching,
                        ),
                        FollowingOverviewSections.UPCOMING to FollowingOverviewSection(
                            key = FollowingOverviewSections.UPCOMING,
                            titleRes = R.string.following_upcoming_streams,
                            emptyRes = R.string.following_no_upcoming_streams,
                            showSeeAll = false,
                        ),
                    )
                    sectionKeys.mapNotNull(availableSections::get)
                }
                combine(
                    sections,
                    viewModel.recentVideosLoading,
                    viewModel.upcomingStreams,
                    viewModel.upcomingStreamsLoading,
                ) { currentSections, recentVideosLoading, upcomingStreams, upcomingStreamsLoading ->
                    currentSections.map { section ->
                        when (section.key) {
                            FollowingOverviewSections.CONTINUE -> section.copy(
                                isLoading = recentVideosLoading && section.videos.isEmpty(),
                            )
                            FollowingOverviewSections.UPCOMING -> section.copy(
                                scheduledStreams = upcomingStreams,
                                isLoading = upcomingStreamsLoading && upcomingStreams.isEmpty(),
                            )
                            else -> section
                        }
                    }
                }.collectLatest { sections ->
                    binding.emptyState.isVisible = sections.isEmpty()
                    overviewAdapter.submitList(sections)
                }
            }
        }
    }

    override fun onNetworkRestored() {
        viewModel.syncCurrentAccount()
        viewModel.refreshOverviewSections()
        viewModel.refreshCurrent(RefreshReason.NETWORK_RESTORED, force = true)
    }

    override fun scrollToTop() {
        binding.recyclerView.scrollToPosition(0)
    }

    override fun onResume() {
        val wasInitialized = isInitialized
        super.onResume()
        if (wasInitialized) {
            viewModel.refreshOverviewSections()
        }
        if (::streamFeedScreenController.isInitialized) {
            streamFeedScreenController.onResume()
        }
        streamShelfPreloadControllers.values.forEach(StreamPreloadViewportController::onResume)
    }

    override fun onPause() {
        if (::streamFeedScreenController.isInitialized) {
            streamFeedScreenController.onPause()
        }
        streamShelfPreloadControllers.values.forEach(StreamPreloadViewportController::onPause)
        super.onPause()
    }

    override fun onDestroyView() {
        if (::streamFeedScreenController.isInitialized) {
            streamFeedScreenController.onDestroyView()
        }
        streamShelfPreloadControllers.values.forEach(StreamPreloadViewportController::stop)
        streamShelfPreloadControllers.clear()
        overviewScrolling = false
        super.onDestroyView()
        _binding = null
    }

    private fun showAll(key: String) {
        when (val parent = parentFragment) {
            is FollowPagerFragment -> when (key) {
                FollowingOverviewSections.LIVE -> parent.selectFollowingTab("1")
                FollowingOverviewSections.CONTINUE -> parent.selectFollowingTab("2")
            }
            is FollowMediaFragment -> when (key) {
                FollowingOverviewSections.LIVE -> parent.selectFollowingTab("1")
                FollowingOverviewSections.CONTINUE -> parent.selectFollowingTab("2")
            }
        }
    }

    private fun VideoHistory.toVideo() = com.github.andreyasadchy.xtra.model.ui.Video(
        id = id.toString(),
        channelId = channelId,
        channelLogin = channelLogin,
        channelName = channelName,
        channelImageURL = channelImageURL,
        gameId = gameId,
        gameSlug = gameSlug,
        gameName = gameName,
        title = title,
        thumbnailURL = thumbnailURL,
        createdAt = createdAt,
        durationSeconds = durationSeconds,
    )

}
