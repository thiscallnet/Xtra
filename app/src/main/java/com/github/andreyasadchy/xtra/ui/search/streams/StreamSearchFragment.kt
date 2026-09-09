package com.github.andreyasadchy.xtra.ui.search.streams

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
import com.github.andreyasadchy.xtra.databinding.CommonRecyclerViewLayoutBinding
import com.github.andreyasadchy.xtra.model.ui.DropStreamFilter
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.common.PagedListFragment
import com.github.andreyasadchy.xtra.ui.common.StreamsAdapter
import com.github.andreyasadchy.xtra.ui.common.StreamsCompactAdapter
import com.github.andreyasadchy.xtra.ui.search.RecentSearchAdapter
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragment
import com.github.andreyasadchy.xtra.ui.search.Searchable
import com.github.andreyasadchy.xtra.ui.search.streams.StreamSearchViewModel.Companion.StreamSearchViewModelFactory
import com.github.andreyasadchy.xtra.ui.top.TopStreamsFragmentDirections
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class StreamSearchFragment : PagedListFragment(), Searchable {

    private var _binding: CommonRecyclerViewLayoutBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StreamSearchViewModel by viewModels { StreamSearchViewModelFactory }
    private lateinit var pagingAdapter: PagingDataAdapter<Stream, out RecyclerView.ViewHolder>
    private var recentSearchAdapter = RecentSearchAdapter({ (parentFragment as? SearchPagerFragment)?.setQuery(it.query) }, { viewModel.deleteRecentSearch(it) })
    private val initialDropsFilter: DropStreamFilter?
        get() = arguments?.getString(CAMPAIGN_ID)?.takeIf { it.isNotBlank() }?.let { campaignId ->
            DropStreamFilter(
                campaignId = campaignId,
                campaignName = arguments?.getString(CAMPAIGN_NAME).orEmpty(),
                gameId = arguments?.getString(GAME_ID),
                gameName = arguments?.getString(GAME_NAME).orEmpty(),
                dropIds = arguments?.getStringArrayList(DROP_IDS).orEmpty().toSet(),
            )
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = CommonRecyclerViewLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pagingAdapter = if (requireContext().prefs().getString(C.COMPACT_STREAMS, "disabled") != "disabled") {
            StreamsCompactAdapter(this, {
                findNavController().navigate(
                    TopStreamsFragmentDirections.actionGlobalTopFragment(
                        tags = arrayOf(it)
                    )
                )
            })
        } else {
            StreamsAdapter(this, {
                findNavController().navigate(
                    TopStreamsFragmentDirections.actionGlobalTopFragment(
                        tags = arrayOf(it)
                    )
                )
            })
        }
        setAdapter(binding.recyclerView, pagingAdapter)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            if (activity?.findViewById<LinearLayout>(R.id.navBarContainer)?.isVisible == false) {
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                binding.recyclerView.updatePadding(bottom = insets.bottom)
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun initialize() {
        val searchPager = parentFragment as? SearchPagerFragment
        viewModel.setDropsFilter(
            if (searchPager != null) searchPager.currentDropsFilter() else initialDropsFilter,
        )
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
    }

    override fun search(query: String) {
        val changed = viewModel.setQuery(query)
        if (changed && query.isNotBlank() && requireContext().prefs().getBoolean(C.UI_STORE_RECENT_SEARCHES, true)) {
            viewModel.saveRecentSearch(query)
        }
    }

    fun searchWithoutSaving(query: String) {
        viewModel.setQuery(query)
    }

    override fun onNetworkRestored() {
        pagingAdapter.retry()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun clearDropsFilter() {
        viewModel.setDropsFilter(null)
    }

    companion object {
        private const val CAMPAIGN_ID = "drops_campaign_id"
        private const val CAMPAIGN_NAME = "drops_campaign_name"
        private const val GAME_ID = "drops_game_id"
        private const val GAME_NAME = "drops_game_name"
        private const val DROP_IDS = "drops_drop_ids"

        fun newInstance(filter: DropStreamFilter?) = StreamSearchFragment().apply {
            filter ?: return@apply
            arguments = Bundle().apply {
                putString(CAMPAIGN_ID, filter.campaignId)
                putString(CAMPAIGN_NAME, filter.campaignName)
                putString(GAME_ID, filter.gameId)
                putString(GAME_NAME, filter.gameName)
                putStringArrayList(DROP_IDS, ArrayList(filter.dropIds))
            }
        }
    }
}


