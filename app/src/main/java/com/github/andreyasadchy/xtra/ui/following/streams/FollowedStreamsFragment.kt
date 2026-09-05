package com.github.andreyasadchy.xtra.ui.following.streams

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.CommonRecyclerViewLayoutBinding
import com.github.andreyasadchy.xtra.databinding.SortBarBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.common.PagedListFragment
import com.github.andreyasadchy.xtra.ui.common.PagerScrollStateAware
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.Sortable
import com.github.andreyasadchy.xtra.ui.common.StreamFeedScreenController
import com.github.andreyasadchy.xtra.ui.common.StreamPreloadViewportController
import com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog
import com.github.andreyasadchy.xtra.ui.following.streams.FollowedStreamsViewModel.Companion.FollowedStreamsViewModelFactory
import com.github.andreyasadchy.xtra.ui.top.TopStreamsFragmentDirections
import com.github.andreyasadchy.xtra.repository.streamfeed.RefreshReason
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class FollowedStreamsFragment : PagedListFragment(), Scrollable, PagerScrollStateAware, Sortable, StreamsSortDialog.OnFilter {

    override val initializeWithoutNetwork = true

    private var _binding: CommonRecyclerViewLayoutBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FollowedStreamsViewModel by viewModels { FollowedStreamsViewModelFactory }
    private lateinit var streamsAdapter: FollowingStreamsListAdapter
    private lateinit var streamFeedScreenController: StreamFeedScreenController
    private lateinit var streamPreloadViewportController: StreamPreloadViewportController

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = CommonRecyclerViewLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val selectTag: (String) -> Unit = {
            findNavController().navigate(
                TopStreamsFragmentDirections.actionGlobalTopFragment(
                    tags = arrayOf(it),
                )
            )
        }
        streamsAdapter = FollowingStreamsListAdapter(
            fragment = this,
            selectTag = selectTag,
            compact = requireContext().prefs().getString(C.COMPACT_STREAMS, "disabled") != "disabled",
        )
        setAdapter(binding.recyclerView, streamsAdapter)
        streamPreloadViewportController = StreamPreloadViewportController(
            fragment = this,
            coordinator = (requireActivity().application as XtraApp).xtraModule.streamPreloadCoordinator,
            viewportKey = "followed-streams",
            recyclerView = binding.recyclerView,
            streamAtPosition = streamsAdapter::itemAt,
        ).also { it.start() }
        streamFeedScreenController = StreamFeedScreenController(
            fragment = this,
            coordinator = viewModel.refreshCoordinator,
            specProvider = viewModel::currentFeedSpec,
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
        viewModel.syncCurrentAccount()
        streamFeedScreenController.onSpecChanged(force = false, reason = RefreshReason.SCREEN_VISIBLE)
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.flow.collect { streams ->
                    streamsAdapter.submitStreams(streams)
                    binding.swipeRefresh.isRefreshing = false
                }
            }
        }
        initializeListAdapter(
            binding = binding,
            onRefresh = {
                binding.swipeRefresh.isRefreshing = true
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.refreshCurrent(RefreshReason.USER_PULL, force = true).join()
                    if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        binding.swipeRefresh.isRefreshing = false
                    }
                }
            },
            enableScrollTopButton = false,
        )
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || streamsAdapter.itemCount == 0) return
                val layoutManager = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
                if (layoutManager.findLastVisibleItemPosition() >= streamsAdapter.itemCount - APPEND_THRESHOLD) {
                    viewModel.appendNextPage()
                }
            }
        })
    }

    override fun setupSortBar(sortBar: SortBarBinding) {
        sortBar.root.visibility = View.VISIBLE
        sortBar.sortText.text = null
        sortBar.filtersText.visibility = View.GONE
        sortBar.root.setOnClickListener {
            StreamsSortDialog.newInstance(
                sort = viewModel.sort,
                tags = emptyArray(),
                languages = emptyArray(),
                showFilters = false,
            ).show(childFragmentManager, null)
        }
    }

    override fun onChange(
        sort: String,
        sortText: CharSequence,
        tags: Array<String>,
        languages: Array<String>,
        changed: Boolean,
        saveFilters: Boolean,
        saveSort: Boolean,
        saveDefault: Boolean,
    ) {
        if (!isAdded) return
        if (changed) {
            viewModel.setSort(sort)
            streamFeedScreenController.onSpecChanged(force = true)
        }
        if (saveDefault) {
            requireContext().prefs().edit { putString(C.UI_STREAM_SORT, sort) }
        }
    }

    override fun deleteSavedSort() = Unit

    override fun scrollToTop() {
        binding.recyclerView.scrollToPosition(0)
    }

    override fun onNetworkRestored() {
        viewModel.syncCurrentAccount()
        viewModel.refreshCurrent(RefreshReason.NETWORK_RESTORED, force = true)
    }

    override fun onPagerScrollStateChanged(scrolling: Boolean) {
        if (::streamPreloadViewportController.isInitialized) {
            streamPreloadViewportController.onParentScrollStateChanged(scrolling)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.syncCurrentAccount()
        if (::streamFeedScreenController.isInitialized) {
            streamFeedScreenController.onResume()
        }
        if (::streamPreloadViewportController.isInitialized) {
            streamPreloadViewportController.onResume()
        }
    }

    override fun onPause() {
        if (::streamFeedScreenController.isInitialized) {
            streamFeedScreenController.onPause()
        }
        if (::streamPreloadViewportController.isInitialized) {
            streamPreloadViewportController.onPause()
        }
        super.onPause()
    }

    override fun onDestroyView() {
        if (::streamFeedScreenController.isInitialized) {
            streamFeedScreenController.onDestroyView()
        }
        if (::streamPreloadViewportController.isInitialized) {
            streamPreloadViewportController.stop()
        }
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val APPEND_THRESHOLD = 5
    }
}


