package com.github.andreyasadchy.xtra.ui.search.all

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
import androidx.recyclerview.widget.ConcatAdapter
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.CommonRecyclerViewLayoutBinding
import com.github.andreyasadchy.xtra.model.ui.SearchHistoryItem
import com.github.andreyasadchy.xtra.model.ui.ranked
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.search.SearchHistoryRecorder
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragment
import com.github.andreyasadchy.xtra.ui.search.SearchPagerViewModel
import com.github.andreyasadchy.xtra.ui.search.SearchPagerViewModel.Companion.SearchPagerViewModelFactory
import com.github.andreyasadchy.xtra.ui.search.SearchRow
import com.github.andreyasadchy.xtra.ui.search.SearchRowAdapter
import com.github.andreyasadchy.xtra.ui.search.SearchSection
import com.github.andreyasadchy.xtra.ui.search.Searchable
import com.github.andreyasadchy.xtra.ui.search.all.AllSearchViewModel.Companion.AllSearchViewModelFactory
import com.github.andreyasadchy.xtra.ui.search.sameAs
import com.github.andreyasadchy.xtra.ui.search.searchEntry
import com.github.andreyasadchy.xtra.ui.search.sectionOrder
import com.github.andreyasadchy.xtra.ui.search.streamEntry
import com.github.andreyasadchy.xtra.ui.search.videoEntry
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * One search for everything: your own channels first, then the groups from Twitch ordered by what
 * the query most likely is (a channel, a category, or the title of a stream or video).
 */
class AllSearchFragment : BaseNetworkFragment(), Searchable {

    override val initializeWithoutNetwork = true

    private var _binding: CommonRecyclerViewLayoutBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AllSearchViewModel by viewModels { AllSearchViewModelFactory }
    private val pagerViewModel: SearchPagerViewModel by viewModels({ requireParentFragment() }) { SearchPagerViewModelFactory }
    private val rowAdapter = SearchRowAdapter(
        onOpen = { SearchHistoryRecorder.open(this, it) },
        onAction = ::onRowAction,
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = CommonRecyclerViewLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(binding) {
            recyclerView.itemAnimator = null
            recyclerView.setTemporarilySingleColumn(true)
            recyclerView.adapter = ConcatAdapter(rowAdapter)
            swipeRefresh.isEnabled = false
            retryButton.setOnClickListener { viewModel.retry() }
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
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    pagerViewModel.history,
                    pagerViewModel.followed,
                    pagerViewModel.live,
                    viewModel.remote,
                    viewModel.query,
                ) { history, followed, live, remote, query ->
                    Snapshot(history, followed, live, remote, query)
                }.collectLatest(::render)
            }
        }
    }

    override fun search(query: String) {
        if (query.isNotBlank()) pagerViewModel.ensureFollowedLoaded()
        viewModel.setQuery(query)
    }

    override fun onNetworkRestored() {
        if (viewModel.remote.value.failed) viewModel.retry()
    }

    private class Snapshot(
        val history: List<SearchHistoryItem>,
        val followed: List<SearchHistoryItem>,
        val live: Map<String, Int>,
        val remote: AllSearchViewModel.Remote,
        val query: String,
    )

    private fun render(state: Snapshot) {
        if (_binding == null) return
        val context = requireContext()
        val query = state.query
        if (query.isBlank()) {
            rowAdapter.submitList(emptyList())
            return
        }
        val remote = state.remote.takeIf { it.query == query } ?: AllSearchViewModel.Remote(query, loading = true)
        val storeHistory = context.prefs().getBoolean(C.UI_STORE_RECENT_SEARCHES, true)
        val compact = context.prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true)

        // Things you already know come first, whatever Twitch ranks them.
        val recent = if (storeHistory) state.history.ranked(query).sortedBy { it.isVideo }.take(MAX_LOCAL) else emptyList()
        val following = state.followed.ranked(query).filter { f -> recent.none { it.sameAs(f) } }.take(MAX_LOCAL)
        val known = recent + following

        val channelRows = remote.channels.mapNotNull { user ->
            val item = SearchHistoryItem.channel(user.id, user.login, user.name, user.profileImage) ?: return@mapNotNull null
            if (known.any { it.sameAs(item) }) return@mapNotNull null
            val detail = user.followerCount?.let {
                context.resources.getQuantityString(R.plurals.followers, it, TwitchApiHelper.formatCount(it, compact))
            }
            searchEntry(context, item, state.live, section = "channels", detail = detail, forceLive = user.isLive == true)
        }.take(MAX_CHANNELS)
        val gameRows = remote.games.mapNotNull { game ->
            val item = SearchHistoryItem.game(game.id, game.slug, game.name, game.boxArt) ?: return@mapNotNull null
            if (known.any { it.sameAs(item) }) return@mapNotNull null
            val detail = game.viewerCount?.takeIf { it > 0 }?.let {
                context.resources.getQuantityString(R.plurals.viewers, it, TwitchApiHelper.formatCount(it, compact))
            }
            searchEntry(context, item, section = "games", detail = detail)
        }.take(MAX_GAMES)
        val streamRows = remote.streams.mapNotNull { streamEntry(context, it, "streams") }.take(MAX_MEDIA)
        val videoRows = remote.videos.mapNotNull { videoEntry(it, "videos") }.take(MAX_MEDIA)

        val rows = mutableListOf<SearchRow>()
        if (recent.isNotEmpty()) {
            rows += SearchRow.Header(getString(R.string.search_section_recent))
            rows += recent.map { searchEntry(context, it, state.live, section = "recent") }
        }
        if (following.isNotEmpty()) {
            rows += SearchRow.Header(getString(R.string.search_section_following))
            rows += following.map { searchEntry(context, it, state.live, section = "following") }
        }
        // The rest of the groups, most likely intent first.
        val order = sectionOrder(
            query,
            remote.channels.mapNotNull { SearchHistoryItem.channel(it.id, it.login, it.name, it.profileImage) },
            remote.games.mapNotNull { SearchHistoryItem.game(it.id, it.slug, it.name, it.boxArt) },
        )
        order.forEach { section ->
            val (title, tab, entries) = when (section) {
                SearchSection.CHANNELS -> Triple(getString(R.string.channels), "2", channelRows)
                SearchSection.CATEGORIES -> Triple(getString(R.string.search_section_categories), "3", gameRows)
                SearchSection.STREAMS -> Triple(getString(R.string.search_section_streams), "1", streamRows)
                SearchSection.VIDEOS -> Triple(getString(R.string.search_section_videos), "0", videoRows)
            }
            if (entries.isEmpty()) return@forEach
            rows += SearchRow.Header(
                title,
                getString(R.string.search_see_all).takeIf { pager()?.isTabEnabled(tab) == true },
                "tab:$tab",
            )
            rows += entries
        }
        val hasEntries = rows.isNotEmpty()
        rowAdapter.submitList(rows)

        binding.progressBar.isVisible = remote.loading && !hasEntries
        binding.errorContainer.isVisible = remote.failed && !remote.loading && !hasEntries
        binding.retryButton.isVisible = binding.errorContainer.isVisible
        if (binding.errorContainer.isVisible) binding.errorMessage.setText(R.string.list_load_error)
        binding.nothingHere.isVisible = !remote.loading && !remote.failed && !hasEntries
        binding.nothingHere.setText(R.string.search_all_empty)
    }

    private fun onRowAction(key: String) {
        if (key.startsWith("tab:")) pager()?.openTab(key.removePrefix("tab:"))
    }

    private fun pager() = parentFragment as? SearchPagerFragment

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val MAX_LOCAL = 3
        const val MAX_CHANNELS = 5
        const val MAX_GAMES = 4
        const val MAX_MEDIA = 4
    }
}
