package com.github.andreyasadchy.xtra.ui.search.channels

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
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.CommonRecyclerViewLayoutBinding
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.ui.common.PagedListFragment
import com.github.andreyasadchy.xtra.model.ui.SearchHistoryItem
import com.github.andreyasadchy.xtra.ui.search.SearchRowAdapter
import com.github.andreyasadchy.xtra.ui.search.matchRows
import com.github.andreyasadchy.xtra.ui.search.SearchHistoryRecorder
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragment
import com.github.andreyasadchy.xtra.ui.search.SearchPagerViewModel
import com.github.andreyasadchy.xtra.ui.search.SearchPagerViewModel.Companion.SearchPagerViewModelFactory
import com.github.andreyasadchy.xtra.ui.search.Searchable
import com.github.andreyasadchy.xtra.ui.search.channels.ChannelSearchViewModel.Companion.ChannelSearchViewModelFactory
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ChannelSearchFragment : PagedListFragment(), Searchable {

    override val initializeWithoutNetwork = true

    private var _binding: CommonRecyclerViewLayoutBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ChannelSearchViewModel by viewModels { ChannelSearchViewModelFactory }
    private lateinit var pagingAdapter: PagingDataAdapter<User, out RecyclerView.ViewHolder>
    private val pagerViewModel: SearchPagerViewModel by viewModels({ requireParentFragment() }) { SearchPagerViewModelFactory }
    private val matchesAdapter = SearchRowAdapter({ SearchHistoryRecorder.open(this, it.item) })

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = CommonRecyclerViewLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pagingAdapter = ChannelSearchAdapter(this)
        setAdapter(binding.recyclerView, ConcatAdapter(matchesAdapter, pagingAdapter))
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
                        updatePagingState(binding, pagingAdapter, loadState, showEmpty = viewModel.query.value.isNotBlank() && matchesAdapter.itemCount == 0)
                        (parentFragment as? SearchPagerFragment)?.bindEmptyHint(nothingHere, "2")
                    }
                }
            }
        }
        if (requireContext().prefs().getBoolean(C.UI_STORE_RECENT_SEARCHES, true)) {
            // Channels you opened before that match what is being typed are pinned on top,
            // instantly and offline, ahead of the network results.
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    combine(
                        pagerViewModel.history,
                        pagerViewModel.followed,
                        pagerViewModel.live,
                        viewModel.query,
                    ) { history, followed, live, query ->
                        matchRows(
                            requireContext(), query, history, followed, live,
                            setOf(SearchHistoryItem.KIND_CHANNEL), MAX_MATCHES,
                        )
                    }.collectLatest { matchesAdapter.submitList(it) }
                }
            }
        }
    }

    override fun search(query: String) {
        if (query.isNotBlank()) pagerViewModel.ensureFollowedLoaded()
        viewModel.setQuery(query)
    }

    override fun onNetworkRestored() {
        pagingAdapter.retry()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val MAX_MATCHES = 4
    }
}


