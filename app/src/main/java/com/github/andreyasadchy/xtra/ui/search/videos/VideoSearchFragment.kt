package com.github.andreyasadchy.xtra.ui.search.videos

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
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.CommonRecyclerViewLayoutBinding
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.common.PagedListFragment
import com.github.andreyasadchy.xtra.ui.common.StreamPreloadViewportController
import com.github.andreyasadchy.xtra.ui.common.StreamPreviewCandidate
import com.github.andreyasadchy.xtra.ui.common.VideosAdapter
import com.github.andreyasadchy.xtra.ui.download.DownloadDialog
import com.github.andreyasadchy.xtra.ui.search.RecentSearchAdapter
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragment
import com.github.andreyasadchy.xtra.ui.search.Searchable
import com.github.andreyasadchy.xtra.ui.search.videos.VideoSearchViewModel.Companion.VideoSearchViewModelFactory
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class VideoSearchFragment : PagedListFragment(), Searchable {

    private var _binding: CommonRecyclerViewLayoutBinding? = null
    private val binding get() = _binding!!
    private val viewModel: VideoSearchViewModel by viewModels { VideoSearchViewModelFactory }
    private lateinit var pagingAdapter: PagingDataAdapter<Video, out RecyclerView.ViewHolder>
    private lateinit var videoPreviewViewportController: StreamPreloadViewportController
    private var recentSearchAdapter = RecentSearchAdapter({ (parentFragment as? SearchPagerFragment)?.setQuery(it.query) }, { viewModel.deleteRecentSearch(it) })

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
            viewportKey = "search-videos",
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
        with(binding) {
            setupPagingControls(binding, pagingAdapter)
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.flow.collectLatest { pagingData ->
                        pagingAdapter.submitData(pagingData)
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    pagingAdapter.loadStateFlow.collectLatest { loadState ->
                        updatePagingState(binding, pagingAdapter, loadState, showEmpty = viewModel.query.value.isNotBlank())
                        if (viewModel.query.value.isBlank() && requireContext().prefs().getBoolean(C.UI_STORE_RECENT_SEARCHES, true)) {
                            recyclerView.adapter = recentSearchAdapter
                        } else {
                            if (recyclerView.adapter is RecentSearchAdapter) {
                                recyclerView.adapter = pagingAdapter
                            }
                        }
                    }
                }
            }
        }
        if (requireContext().prefs().getBoolean(C.UI_STORE_RECENT_SEARCHES, true)) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.recentSearches.collectLatest {
                        recentSearchAdapter.submitList(it)
                    }
                }
            }
        }
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

    override fun search(query: String) {
        viewModel.setQuery(query)
        if (requireContext().prefs().getBoolean(C.UI_STORE_RECENT_SEARCHES, true)) {
            viewModel.saveRecentSearch(query)
        }
    }

    override fun onNetworkRestored() {
        pagingAdapter.retry()
    }

    override fun onIntegrityTokenLoaded(callback: String?) {
        when (callback) {
            "refresh" -> {
                pagingAdapter.refresh()
            }
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
