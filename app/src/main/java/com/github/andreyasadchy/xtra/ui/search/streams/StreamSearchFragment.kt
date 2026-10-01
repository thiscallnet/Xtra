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
import androidx.recyclerview.widget.ConcatAdapter
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
import com.github.andreyasadchy.xtra.ui.view.GridPage
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
    private lateinit var suggestionsHeaderAdapter: SearchStreamSuggestionsHeaderAdapter
    private lateinit var landingAdapter: ConcatAdapter
    private val initialDropsFilters: List<DropStreamFilter>
        get() = arguments?.parcelableArrayList<DropStreamFilter>(FILTERS).orEmpty()

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
        suggestionsHeaderAdapter = SearchStreamSuggestionsHeaderAdapter(this)
        landingAdapter = ConcatAdapter(suggestionsHeaderAdapter, recentSearchAdapter)
        setAdapter(binding.recyclerView, pagingAdapter)
        binding.recyclerView.usePageGrid(GridPage.SEARCH_STREAMS) {
            binding.recyclerView.adapter !is RecentSearchAdapter
        }
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
        viewModel.setDropsFilters(
            if (searchPager != null) searchPager.currentDropsFilters() else initialDropsFilters,
        )
        with(binding) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.cachedSuggestions.collectLatest(suggestionsHeaderAdapter::submitStreams)
                }
            }
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
                        val hasActiveSearch = viewModel.query.value.isNotBlank() ||
                            viewModel.dropsFilters.value.isNotEmpty()
                        updatePagingState(binding, pagingAdapter, loadState, showEmpty = hasActiveSearch)
                        updateDisplayedAdapter()
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
        viewModel.refreshCachedSuggestions()
    }

    override fun search(query: String) {
        val changed = viewModel.setQuery(query)
        updateDisplayedAdapter()
        if (changed && query.isNotBlank() && requireContext().prefs().getBoolean(C.UI_STORE_RECENT_SEARCHES, true)) {
            viewModel.saveRecentSearch(query)
        }
    }

    fun searchWithoutSaving(query: String) {
        viewModel.setQuery(query)
        updateDisplayedAdapter()
    }

    override fun onNetworkRestored() {
        pagingAdapter.retry()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun clearDropsFilter() {
        viewModel.setDropsFilters(emptyList())
        updateDisplayedAdapter()
    }

    fun applyDropsFilters(filters: List<DropStreamFilter>) {
        viewModel.setDropsFilters(filters)
        updateDisplayedAdapter()
    }

    private fun setDisplayedAdapter(adapter: RecyclerView.Adapter<*>) {
        binding.recyclerView.setTemporarilySingleColumn(adapter !== pagingAdapter)
        binding.recyclerView.adapter = adapter
    }

    private fun updateDisplayedAdapter() {
        if (_binding == null || !::landingAdapter.isInitialized || !::pagingAdapter.isInitialized) return
        val showLanding = viewModel.query.value.isBlank() && viewModel.dropsFilters.value.isEmpty()
        setDisplayedAdapter(if (showLanding) landingAdapter else pagingAdapter)
    }

    companion object {
        private const val FILTERS = "drops_filters"

        fun newInstance(filters: List<DropStreamFilter>) = StreamSearchFragment().apply {
            if (filters.isEmpty()) return@apply
            arguments = Bundle().apply {
                putParcelableArrayList(FILTERS, ArrayList(filters))
            }
        }
    }
}

private inline fun <reified T : android.os.Parcelable> Bundle.parcelableArrayList(key: String): ArrayList<T>? =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayList(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayList(key)
    }


