package com.github.andreyasadchy.xtra.ui.search.games

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
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.CommonRecyclerViewLayoutBinding
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.SearchHistoryItem
import com.github.andreyasadchy.xtra.ui.common.GamesAdapter
import com.github.andreyasadchy.xtra.ui.common.PagedListFragment
import com.github.andreyasadchy.xtra.ui.games.GamesFragmentDirections
import com.github.andreyasadchy.xtra.ui.search.SearchHistoryRecorder
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragment
import com.github.andreyasadchy.xtra.ui.search.SearchPagerViewModel
import com.github.andreyasadchy.xtra.ui.search.SearchPagerViewModel.Companion.SearchPagerViewModelFactory
import com.github.andreyasadchy.xtra.ui.search.SearchRowAdapter
import com.github.andreyasadchy.xtra.ui.search.Searchable
import com.github.andreyasadchy.xtra.ui.search.matchRows
import com.github.andreyasadchy.xtra.ui.view.GridPage
import com.github.andreyasadchy.xtra.ui.search.games.GameSearchViewModel.Companion.GameSearchViewModelFactory
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class GameSearchFragment : PagedListFragment(), Searchable {

    override val initializeWithoutNetwork = true

    private var _binding: CommonRecyclerViewLayoutBinding? = null
    private val binding get() = _binding!!
    private val viewModel: GameSearchViewModel by viewModels { GameSearchViewModelFactory }
    private lateinit var pagingAdapter: PagingDataAdapter<Game, out RecyclerView.ViewHolder>
    private val pagerViewModel: SearchPagerViewModel by viewModels({ requireParentFragment() }) { SearchPagerViewModelFactory }
    private val matchesAdapter = SearchRowAdapter({ SearchHistoryRecorder.open(this, it.item) })

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = CommonRecyclerViewLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pagingAdapter = GamesAdapter(this, recordSearchHistory = true) {
            findNavController().navigate(
                GamesFragmentDirections.actionGlobalGamesFragment(
                    tags = arrayOf(it)
                )
            )
        }
        setAdapter(binding.recyclerView, ConcatAdapter(matchesAdapter, pagingAdapter))
        binding.recyclerView.usePageGrid(GridPage.SEARCH_GAMES) { true }
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            if (activity?.findViewById<LinearLayout>(R.id.navBarContainer)?.isVisible == false) {
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                binding.recyclerView.updatePadding(bottom = insets.bottom)
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun initialize() {
        if (requireContext().prefs().getBoolean(C.UI_STORE_RECENT_SEARCHES, true)) observeMatches()
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
                        (parentFragment as? SearchPagerFragment)?.bindEmptyHint(nothingHere, "3")
                    }
                }
            }
        }
    }

    override fun search(query: String) {
        viewModel.setQuery(query)
    }

    private fun observeMatches() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(pagerViewModel.history, viewModel.query) { history, query ->
                    matchRows(
                        requireContext(), query, history, emptyList(), emptyMap(),
                        setOf(SearchHistoryItem.KIND_GAME), MAX_MATCHES,
                    )
                }.collectLatest { matchesAdapter.submitList(it) }
            }
        }
    }

    override fun onNetworkRestored() {
        pagingAdapter.retry()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val MAX_MATCHES = 3
    }
}


