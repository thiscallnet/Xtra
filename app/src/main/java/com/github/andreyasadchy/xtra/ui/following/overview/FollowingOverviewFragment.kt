package com.github.andreyasadchy.xtra.ui.following.overview

import android.content.Context
import android.os.Bundle
import android.content.res.Configuration
import android.text.format.DateUtils
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
import com.github.andreyasadchy.xtra.model.ui.UpcomingStream
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.FragmentFollowingOverviewBinding
import com.github.andreyasadchy.xtra.model.VideoHistory
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.RecommendationSource
import com.github.andreyasadchy.xtra.repository.streamfeed.RefreshReason
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.StreamFeedScreenController
import com.github.andreyasadchy.xtra.ui.common.StreamPreloadViewportController
import com.github.andreyasadchy.xtra.ui.common.StreamPreviewCandidate
import com.github.andreyasadchy.xtra.ui.following.FollowMediaFragment
import com.github.andreyasadchy.xtra.ui.following.FollowPagerFragment
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.overview.OverviewFragment
import com.github.andreyasadchy.xtra.ui.top.TopStreamsFragmentDirections
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.ui.following.overview.FollowingOverviewViewModel.Companion.FollowingOverviewViewModelFactory
import kotlin.time.Instant
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private data class RecommendationState(
    val streams: List<Stream>,
    val isLoading: Boolean,
    val hasResolved: Boolean,
    val source: RecommendationSource,
    val hasFailed: Boolean,
)

private data class LoadingState(
    val isLoading: Boolean,
    val hasResolved: Boolean,
)

private data class UpcomingState(
    val streams: List<com.github.andreyasadchy.xtra.model.ui.UpcomingStream>,
    val isLoading: Boolean,
    val hasResolved: Boolean,
)

class FollowingOverviewFragment : BaseNetworkFragment(), Scrollable {

    override val initializeWithoutNetwork = true

    private var _binding: FragmentFollowingOverviewBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FollowingOverviewViewModel by viewModels { FollowingOverviewViewModelFactory }
    private lateinit var overviewAdapter: FollowingOverviewAdapter
    private lateinit var streamFeedScreenController: StreamFeedScreenController
    private val streamShelfPreloadControllers = mutableMapOf<String, StreamPreloadViewportController>()
    private val videoShelfPreviewControllers = mutableMapOf<String, StreamPreloadViewportController>()
    private var overviewScrolling = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFollowingOverviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        overviewAdapter = FollowingOverviewAdapter(
            fragment = this,
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
            onStreamTagClick = { tag ->
                findNavController().navigate(
                    TopStreamsFragmentDirections.actionGlobalTopFragment(tags = arrayOf(tag)),
                )
            },
            onStreamShelfAttached = { key, recyclerView, streamAtPosition, _ ->
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
            useFollowedStreamCardLayout = true,
            onVideoShelfAttached = { key, recyclerView, videoAtPosition ->
                videoShelfPreviewControllers.remove(key)?.stop()
                StreamPreloadViewportController(
                    fragment = this,
                    coordinator = null,
                    viewportKey = "following-overview-videos:$key",
                    recyclerView = recyclerView,
                    previewAtPosition = { position, surface ->
                        videoAtPosition(position)?.let { video ->
                            StreamPreviewCandidate(
                                streamKey = "vod:${video.id}",
                                channelLogin = video.channelLogin.orEmpty(),
                                visibleFraction = 0f,
                                centerProximity = 0f,
                                title = video.title,
                                channelName = video.channelName,
                                channelLogo = video.channelImageURL?.let(TwitchApiHelper::getProfileImage),
                                videoId = video.id.toString(),
                                surface = surface,
                            )
                        }
                    },
                    isParentScrolling = { overviewScrolling },
                ).also {
                    videoShelfPreviewControllers[key] = it
                    it.start()
                }
            },
            onVideoShelfDetached = { key ->
                videoShelfPreviewControllers.remove(key)?.stop()
            },
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = overviewAdapter
            itemAnimator = null
            setHasFixedSize(true)
            clipToPadding = false
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    overviewScrolling = newState != RecyclerView.SCROLL_STATE_IDLE
                    streamShelfPreloadControllers.values.forEach(StreamPreloadViewportController::onParentScrollStateChanged)
                    videoShelfPreviewControllers.values.forEach(StreamPreloadViewportController::onParentScrollStateChanged)
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
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateTopInsetGuard() }
        view.post { updateTopInsetGuard() }
        streamFeedScreenController = StreamFeedScreenController(
            fragment = this,
            coordinator = viewModel.refreshCoordinator,
            specProvider = viewModel::currentFeedSpec,
        ).also { it.start() }
    }

    /** Covers the status bar area whenever this screen reaches the top of the window. */
    private fun updateTopInsetGuard() {
        val binding = _binding ?: return
        if (!binding.root.isAttachedToWindow) return
        val topInset = ViewCompat.getRootWindowInsets(binding.root)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            ?.top ?: 0
        val location = IntArray(2)
        binding.root.getLocationInWindow(location)
        val guardHeight = (topInset - location[1]).coerceAtLeast(0)
        if (binding.topInsetGuard.layoutParams.height != guardHeight) {
            binding.topInsetGuard.layoutParams = binding.topInsetGuard.layoutParams.apply { height = guardHeight }
        }
        binding.topInsetGuard.visibility = if (guardHeight > 0) android.view.View.VISIBLE else android.view.View.GONE
    }

    override fun initialize() {
        viewModel.syncCurrentAccount()
        viewModel.refreshOverviewSections()
        streamFeedScreenController.onSpecChanged(force = false, reason = RefreshReason.SCREEN_VISIBLE)
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val recommendationState = combine(
                    viewModel.recommendedStreams,
                    viewModel.recommendationsLoading,
                    viewModel.recommendationsResolved,
                    viewModel.recommendationSource,
                    viewModel.recommendationsFailed,
                ) { recommended, recommendationsLoading, recommendationsResolved, recommendationSource, recommendationsFailed ->
                    RecommendationState(recommended, recommendationsLoading, recommendationsResolved, recommendationSource, recommendationsFailed)
                }
                val sections = combine(
                    viewModel.liveStreams,
                    recommendationState,
                    viewModel.continueWatching,
                    viewModel.overviewSectionKeys,
                ) { live, recommendation, continueWatching, sectionKeys ->
                    val recommended = recommendation.streams
                    val recommendationTitle = if (recommendation.source == RecommendationSource.FALLBACK) {
                        R.string.following_popular_live_channels
                    } else {
                        R.string.following_recommended_channels
                    }
                    val availableSections = mapOf(
                        FollowingOverviewSections.LIVE to FollowingOverviewSection(
                            key = FollowingOverviewSections.LIVE,
                            titleRes = R.string.following_live_channels,
                            emptyRes = R.string.following_no_live_channels,
                            streams = live,
                        ),
                        FollowingOverviewSections.RECOMMENDED to FollowingOverviewSection(
                            key = FollowingOverviewSections.RECOMMENDED,
                            titleRes = recommendationTitle,
                            title = if (recommendation.hasFailed && recommended.isNotEmpty()) {
                                getString(R.string.following_cached_recommendations, getString(recommendationTitle))
                            } else null,
                            emptyRes = R.string.following_no_recommended_channels,
                            streams = recommended,
                            isLoading = recommendation.isLoading && recommended.isEmpty() && !recommendation.hasResolved,
                            hasResolved = recommendation.hasResolved,
                            showSeeAll = false,
                        ),
                        FollowingOverviewSections.CONTINUE to FollowingOverviewSection(
                            key = FollowingOverviewSections.CONTINUE,
                            titleRes = R.string.following_continue_watching,
                            emptyRes = R.string.following_no_continue_watching,
                            videos = continueWatching,
                            loadingType = FollowingOverviewLoadingType.VIDEO,
                        ),
                        FollowingOverviewSections.UPCOMING to FollowingOverviewSection(
                            key = FollowingOverviewSections.UPCOMING,
                            titleRes = R.string.following_upcoming_streams,
                            emptyRes = R.string.following_no_upcoming_streams,
                            showSeeAll = false,
                            loadingType = FollowingOverviewLoadingType.UPCOMING,
                        ),
                        FollowingOverviewSections.RECENTLY_OFFLINE to FollowingOverviewSection(
                            key = FollowingOverviewSections.RECENTLY_OFFLINE,
                            titleRes = R.string.following_recently_offline,
                            emptyRes = R.string.following_no_recently_offline,
                            showSeeAll = false,
                            loadingType = FollowingOverviewLoadingType.VIDEO,
                        ),
                        FollowingOverviewSections.EXPECTED_SOON to FollowingOverviewSection(
                            key = FollowingOverviewSections.EXPECTED_SOON,
                            titleRes = R.string.following_expected_soon,
                            emptyRes = R.string.following_no_expected_soon,
                            showSeeAll = false,
                            loadingType = FollowingOverviewLoadingType.UPCOMING,
                        ),
                    )
                    sectionKeys.mapNotNull(availableSections::get)
                }
                val recentVideosState = combine(
                    viewModel.recentVideosLoading,
                    viewModel.recentVideosResolved,
                ) { isLoading, hasResolved -> LoadingState(isLoading, hasResolved) }
                val channelVodsState = combine(
                    viewModel.recentlyOfflineVideos,
                    viewModel.expectedStreams,
                    viewModel.channelVodsLoading,
                    viewModel.channelVodsResolved,
                ) { videos, expected, isLoading, hasResolved ->
                    ChannelVodsState(videos, expected, isLoading, hasResolved)
                }
                val upcomingStreamsState = combine(
                    viewModel.upcomingStreams,
                    viewModel.upcomingStreamsLoading,
                    viewModel.upcomingStreamsResolved,
                ) { streams, isLoading, hasResolved -> UpcomingState(streams, isLoading, hasResolved) }
                combine(
                    sections,
                    recentVideosState,
                    upcomingStreamsState,
                    channelVodsState,
                ) { currentSections, recentVideos, upcoming, channelVods ->
                    currentSections.map { section ->
                        when (section.key) {
                            FollowingOverviewSections.CONTINUE -> section.copy(
                                isLoading = recentVideos.isLoading && section.videos.isEmpty() && !recentVideos.hasResolved,
                                hasResolved = recentVideos.hasResolved,
                            )
                            FollowingOverviewSections.UPCOMING -> section.copy(
                                scheduledStreams = upcoming.streams,
                                isLoading = upcoming.isLoading && upcoming.streams.isEmpty() && !upcoming.hasResolved,
                                hasResolved = upcoming.hasResolved,
                            )
                            FollowingOverviewSections.RECENTLY_OFFLINE -> section.copy(
                                videos = channelVods.videos.mapNotNull { video ->
                                    video.toRecentlyOfflineCard(requireContext(), System.currentTimeMillis())
                                },
                                isLoading = channelVods.isLoading && channelVods.videos.isEmpty() && !channelVods.hasResolved,
                                hasResolved = channelVods.hasResolved,
                            )
                            FollowingOverviewSections.EXPECTED_SOON -> section.copy(
                                scheduledStreams = channelVods.expected,
                                isLoading = channelVods.isLoading && channelVods.expected.isEmpty() && !channelVods.hasResolved,
                                hasResolved = channelVods.hasResolved,
                            )
                            else -> section
                        }
                    }
                }
                    .distinctUntilChanged(::followingOverviewSectionsSame)
                    .collectLatest { sections ->
                    binding.emptyState.isVisible = sections.isEmpty()
                    overviewAdapter.submitList(sections)
                }
            }
        }
    }

    override fun onNetworkRestored() {
        viewModel.syncCurrentAccount()
        viewModel.refreshOverviewSections(force = true)
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
        videoShelfPreviewControllers.values.forEach(StreamPreloadViewportController::onResume)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::overviewAdapter.isInitialized) {
            overviewAdapter.notifyDataSetChanged()
        }
    }

    override fun onPause() {
        if (::streamFeedScreenController.isInitialized) {
            streamFeedScreenController.onPause()
        }
        streamShelfPreloadControllers.values.forEach(StreamPreloadViewportController::onPause)
        videoShelfPreviewControllers.values.forEach(StreamPreloadViewportController::onPause)
        super.onPause()
    }

    override fun onDestroyView() {
        if (::streamFeedScreenController.isInitialized) {
            streamFeedScreenController.onDestroyView()
        }
        streamShelfPreloadControllers.values.forEach(StreamPreloadViewportController::stop)
        streamShelfPreloadControllers.clear()
        videoShelfPreviewControllers.values.forEach(StreamPreloadViewportController::stop)
        videoShelfPreviewControllers.clear()
        overviewScrolling = false
        super.onDestroyView()
        _binding = null
    }

    private fun showAll(key: String) {
        val tabKey = FollowingOverviewSections.followingTabKey(key)
        when (val parent = parentFragment) {
            is FollowPagerFragment -> tabKey?.let(parent::selectFollowingTab)
            is FollowMediaFragment -> tabKey?.let(parent::selectFollowingTab)
            is OverviewFragment -> parent.showAll(key)
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

private data class ChannelVodsState(
    val videos: List<Video>,
    val expected: List<UpcomingStream>,
    val isLoading: Boolean,
    val hasResolved: Boolean,
)

private fun Video.toRecentlyOfflineCard(context: Context, nowMs: Long): VideoHistory? {
    val videoId = id?.toLongOrNull() ?: return null
    val startedAt = createdAt?.let(Instant::parseOrNull)?.toEpochMilliseconds() ?: return null
    val endedAt = startedAt + (durationSeconds ?: 0) * 1000L
    val endedAgo = DateUtils.getRelativeTimeSpanString(
        endedAt,
        nowMs,
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
    return VideoHistory(
        id = videoId,
        position = 0,
        durationSeconds = durationSeconds,
        channelId = channelId,
        channelLogin = channelLogin,
        channelName = channelName,
        channelImageURL = channelImageURL,
        title = title,
        thumbnailURL = thumbnailURL,
        gameId = gameId,
        gameSlug = gameSlug,
        gameName = listOfNotNull(context.getString(R.string.following_ended_ago, endedAgo), gameName)
            .joinToString(" \u00B7 "),
        createdAt = createdAt,
        updatedAt = 0,
    )
}
