package com.github.andreyasadchy.xtra.ui.following.streams

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
import androidx.navigation.fragment.findNavController
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.CommonRecyclerViewLayoutBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.common.PagedListFragment
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.StreamFeedScreenController
import com.github.andreyasadchy.xtra.ui.common.StreamPreloadViewportController
import com.github.andreyasadchy.xtra.ui.common.StreamsCompactAdapter
import com.github.andreyasadchy.xtra.ui.following.streams.FollowedStreamsViewModel.Companion.FollowedStreamsViewModelFactory
import com.github.andreyasadchy.xtra.ui.top.TopStreamsFragmentDirections
import com.github.andreyasadchy.xtra.repository.streamfeed.RefreshReason
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FollowedStreamsFragment : PagedListFragment(), Scrollable {

    override val initializeWithoutNetwork = true

    private var _binding: CommonRecyclerViewLayoutBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FollowedStreamsViewModel by viewModels { FollowedStreamsViewModelFactory }
    private lateinit var pagingAdapter: PagingDataAdapter<Stream, out RecyclerView.ViewHolder>
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
        pagingAdapter = if (requireContext().prefs().getString(C.COMPACT_STREAMS, "disabled") != "disabled") {
            StreamsCompactAdapter(this, selectTag)
        } else {
            StreamsShelfPagingAdapter(this, selectTag)
        }
        setAdapter(binding.recyclerView, pagingAdapter)
        streamPreloadViewportController = StreamPreloadViewportController(
            fragment = this,
            coordinator = (requireActivity().application as XtraApp).xtraModule.streamPreloadCoordinator,
            viewportKey = "followed-streams",
            recyclerView = binding.recyclerView,
            streamAtPosition = pagingAdapter::peek,
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
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.flow.collectLatest { pagingData ->
                    pagingAdapter.submitData(pagingData)
                }
            }
        }
        initializeAdapter(binding, pagingAdapter, enableScrollTopButton = false)
    }

    override fun scrollToTop() {
        binding.recyclerView.scrollToPosition(0)
    }

    override fun onNetworkRestored() {
        viewModel.syncCurrentAccount()
        viewModel.refreshCurrent(RefreshReason.NETWORK_RESTORED, force = true)
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

    override fun onIntegrityTokenLoaded(callback: String?) {
        when (callback) {
            "refresh" -> {
                pagingAdapter.refresh()
            }
        }
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
}
