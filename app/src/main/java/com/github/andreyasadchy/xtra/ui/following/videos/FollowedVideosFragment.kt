package com.github.andreyasadchy.xtra.ui.following.videos

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.PagingData
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.CommonRecyclerViewLayoutBinding
import com.github.andreyasadchy.xtra.databinding.SortBarBinding
import com.github.andreyasadchy.xtra.model.ui.ChannelSort
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.common.FragmentHost
import com.github.andreyasadchy.xtra.ui.common.PagedListFragment
import com.github.andreyasadchy.xtra.ui.common.PagerScrollStateAware
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.StreamPreloadViewportController
import com.github.andreyasadchy.xtra.ui.common.StreamPreviewCandidate
import com.github.andreyasadchy.xtra.ui.common.Sortable
import com.github.andreyasadchy.xtra.ui.common.VideosAdapter
import com.github.andreyasadchy.xtra.ui.common.VideosSortDialog
import com.github.andreyasadchy.xtra.ui.download.DownloadDialog
import com.github.andreyasadchy.xtra.ui.following.videos.FollowedVideosViewModel.Companion.FollowedVideosViewModelFactory
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FollowedVideosFragment : PagedListFragment(), Scrollable, Sortable, PagerScrollStateAware, VideosSortDialog.OnFilter {

    private var _binding: CommonRecyclerViewLayoutBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FollowedVideosViewModel by viewModels { FollowedVideosViewModelFactory }
    private lateinit var pagingAdapter: PagingDataAdapter<Video, out RecyclerView.ViewHolder>
    private lateinit var videoPreviewViewportController: StreamPreloadViewportController

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = CommonRecyclerViewLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pagingAdapter = VideosAdapter(this, {
            DownloadDialog.newVideoInstance(
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
                durationSeconds = it.durationSeconds,
                type = it.type,
                animatedPreviewUrl = it.animatedPreviewURL,
            ).show(childFragmentManager, null)
        }, {
            viewModel.saveBookmark(
                requireContext().filesDir.path,
                it,
                requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                TwitchApiHelper.getGQLHeaders(requireContext()),
                TwitchApiHelper.getHelixHeaders(requireContext()),
            )
        })
        setAdapter(binding.recyclerView, pagingAdapter)
        videoPreviewViewportController = StreamPreloadViewportController(
            fragment = this,
            coordinator = null,
            viewportKey = "followed-videos",
            recyclerView = binding.recyclerView,
            previewAtPosition = { position, surface ->
                pagingAdapter.peek(position)?.let { video ->
                    video.id?.trim()?.takeIf { it.isNotEmpty() }?.let { videoId ->
                        StreamPreviewCandidate(
                            streamKey = "vod:$videoId",
                            channelLogin = video.channelLogin.orEmpty(),
                            visibleFraction = 0f,
                            centerProximity = 0f,
                            title = video.title,
                            channelName = video.channelName,
                            channelLogo = video.channelImage,
                            videoId = videoId,
                            surface = surface,
                        )
                    }
                }
            },
        ).also { it.start() }
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            if (activity?.findViewById<LinearLayout>(R.id.navBarContainer)?.isVisible == false) {
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                binding.recyclerView.updatePadding(bottom = insets.bottom)
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun initialize() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (viewModel.filter.value == null) {
                val sortValues = viewModel.getChannelSort("followed_videos")
                viewModel.setFilter(
                    sort = sortValues?.videoSort,
                    type = sortValues?.videoType,
                )
                viewModel.sortText.value = getString(
                    R.string.sort_and_type,
                    getString(
                        when (viewModel.sort) {
                            VideosSortDialog.SORT_TIME -> R.string.upload_date
                            VideosSortDialog.SORT_VIEWS -> R.string.view_count
                            else -> R.string.upload_date
                        }
                    ),
                    getString(R.string.all)
                )
            }
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.flow.collectLatest { pagingData ->
                    pagingAdapter.submitData(pagingData)
                }
            }
        }
        initializeAdapter(binding, pagingAdapter, enableScrollTopButton = false)
        if (requireContext().prefs().getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.positions.collectLatest {
                        (pagingAdapter as VideosAdapter).setVideoPositions(it)
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.bookmarks.collectLatest {
                    (pagingAdapter as VideosAdapter).setBookmarksList(it)
                }
            }
        }
    }

    override fun setupSortBar(sortBar: SortBarBinding) {
        sortBar.root.visibility = View.VISIBLE
        sortBar.root.setOnClickListener {
            VideosSortDialog.newInstance(
                sort = viewModel.sort,
                period = viewModel.period,
                type = viewModel.type,
            ).show(childFragmentManager, null)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sortText.collectLatest {
                    sortBar.sortText.text = it
                }
            }
        }
    }

    override fun onChange(sort: String, sortText: CharSequence, period: String, periodText: CharSequence, type: String, typeText: CharSequence, languages: Array<String>, changed: Boolean, saveSort: Boolean, saveDefault: Boolean) {
        if ((parentFragment as? FragmentHost)?.currentFragment == this) {
            viewLifecycleOwner.lifecycleScope.launch {
                if (changed) {
                    binding.scrollTop.visibility = View.GONE
                    pagingAdapter.submitData(PagingData.empty())
                    viewModel.setFilter(sort, type)
                    viewModel.sortText.value = getString(R.string.sort_and_type, sortText, typeText)
                }
                if (saveDefault) {
                    val item = viewModel.getChannelSort("followed_videos")?.apply {
                        videoSort = sort
                        videoType = type
                    } ?: ChannelSort(
                        id = "followed_videos",
                        videoSort = sort,
                        videoType = type
                    )
                    viewModel.saveChannelSort(item)
                }
            }
        }
    }

    override fun deleteSavedSort() {}

    override fun scrollToTop() {
        binding.recyclerView.scrollToPosition(0)
    }

    override fun onNetworkRestored() {
        pagingAdapter.retry()
    }

    override fun onPagerScrollStateChanged(scrolling: Boolean) {
        if (::videoPreviewViewportController.isInitialized) {
            videoPreviewViewportController.onParentScrollStateChanged(scrolling)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::videoPreviewViewportController.isInitialized) {
            videoPreviewViewportController.onResume()
        }
    }

    override fun onPause() {
        if (::videoPreviewViewportController.isInitialized) {
            videoPreviewViewportController.onPause()
        }
        super.onPause()
    }

    override fun onDestroyView() {
        if (::videoPreviewViewportController.isInitialized) {
            videoPreviewViewportController.stop()
        }
        super.onDestroyView()
        _binding = null
    }
}


