package com.github.andreyasadchy.xtra.ui.saved.bookmarks

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
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.CommonRecyclerViewLayoutBinding
import com.github.andreyasadchy.xtra.databinding.SortBarBinding
import com.github.andreyasadchy.xtra.model.ui.Bookmark
import com.github.andreyasadchy.xtra.model.ui.ChannelSort
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.FragmentHost
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.Sortable
import com.github.andreyasadchy.xtra.ui.common.installVisibleViewportStatePositioning
import com.github.andreyasadchy.xtra.ui.download.DownloadDialog
import com.github.andreyasadchy.xtra.ui.view.GridPage
import com.github.andreyasadchy.xtra.ui.view.GridRecyclerView
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.saved.installSavedDeleteFeedback
import com.github.andreyasadchy.xtra.ui.saved.SavedItemSelection
import com.github.andreyasadchy.xtra.ui.saved.SavedSelectablePage
import com.github.andreyasadchy.xtra.ui.saved.SavedSelectionHost
import com.github.andreyasadchy.xtra.ui.saved.bookmarks.BookmarksViewModel.Companion.BookmarksViewModelFactory
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class BookmarksFragment : BaseNetworkFragment(), Scrollable, Sortable, BookmarksSortDialog.OnFilter, SavedSelectablePage {

    private var _binding: CommonRecyclerViewLayoutBinding? = null
    private val binding get() = _binding!!
    private val viewModel: BookmarksViewModel by viewModels { BookmarksViewModelFactory }
    private lateinit var adapter: ListAdapter<Bookmark, out RecyclerView.ViewHolder>
    private lateinit var selection: SavedItemSelection
    private var bookmarksEmpty = true
    private var sortBarBinding: SortBarBinding? = null
    override var enableNetworkCheck = false
    override val initializeOfflineOncePerView = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = CommonRecyclerViewLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        selection = SavedItemSelection(savedInstanceState)
        installSavedDeleteFeedback(view, viewModel.deleteFailed, viewModel::acknowledgeDeleteFailure)
        binding.installVisibleViewportStatePositioning(viewLifecycleOwner)
        binding.nothingHere.textAlignment = View.TEXT_ALIGNMENT_CENTER
        adapter = BookmarksAdapter(this, {
            viewModel.updateVideo(
                requireContext().filesDir.path,
                it,
                requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                TwitchApiHelper.getGQLHeaders(requireContext()),
                TwitchApiHelper.getHelixHeaders(requireContext()),
            )
        }, {
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
            viewModel.vodIgnoreUser(it)
        }, {
            val delete = getString(R.string.delete)
            requireActivity().getAlertDialogBuilder()
                .setTitle(delete)
                .setMessage(getString(R.string.are_you_sure))
                .setPositiveButton(delete) { _, _ -> viewModel.delete(it) }
                .setNegativeButton(getString(android.R.string.cancel), null)
                .show()
        }, selection)
        selection.loadedIds = { adapter.currentList.map { it.id.toString() } }
        selection.hasLoadedItems = { adapter.currentList.isNotEmpty() }
        selection.onChanged = (adapter as BookmarksAdapter)::notifySelectionChanged
        selection.onDelete = { ids ->
            requireActivity().getAlertDialogBuilder()
                .setTitle(R.string.delete)
                .setMessage(getString(R.string.saved_delete_bookmarks_message, ids.size))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    viewModel.deleteSelected(ids)
                    selection.finish()
                }.show()
        }
        view.post { if (_binding?.root === view && selection.active) startSavedSelection() }
        with(binding) {
            nothingHere.setText(R.string.bookmarks_empty)
            adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    adapter.unregisterAdapterDataObserver(this)
                    adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                            if (positionStart == 0) {
                                recyclerView.smoothScrollToPosition(0)
                            }
                        }
                    })
                }
            })
            recyclerView.adapter = adapter
            recyclerView.usePageGrid(GridPage.SAVED_BOOKMARKS)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                if (activity?.findViewById<LinearLayout>(R.id.navBarContainer)?.isVisible == false) {
                    val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                    recyclerView.updatePadding(bottom = insets.bottom)
                }
                WindowInsetsCompat.CONSUMED
            }
        }
    }

    override fun initialize() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (viewModel.filter.value == null) {
                val sortValues = viewModel.getChannelSort("bookmarks")
                viewModel.setFilter(
                    sort = sortValues?.videoSort,
                    order = sortValues?.videoType,
                )
                viewModel.sortText.value = getString(
                    R.string.sort_and_order,
                    getString(
                        when (viewModel.sort) {
                            BookmarksSortDialog.SORT_EXPIRES_AT -> R.string.deletion_date
                            BookmarksSortDialog.SORT_CREATED_AT -> R.string.creation_date
                            BookmarksSortDialog.SORT_SAVED_AT -> R.string.saved_date
                            else -> R.string.saved_date
                        }
                    ),
                    getString(
                        when (viewModel.order) {
                            BookmarksSortDialog.ORDER_DESC -> R.string.descending
                            BookmarksSortDialog.ORDER_ASC -> R.string.ascending
                            else -> R.string.descending
                        }
                    )
                )
            }
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.flow.collectLatest { list ->
                    val sort = viewModel.sort
                    val ascending = viewModel.order == BookmarksSortDialog.ORDER_ASC
                    val sorted = withContext(Dispatchers.Default) {
                        if (sort != BookmarksSortDialog.SORT_EXPIRES_AT && sort != BookmarksSortDialog.SORT_CREATED_AT) {
                            var comparisons = 0
                            return@withContext list.sortedWith { left, right ->
                                if (++comparisons % 1024 == 0) ensureActive()
                                if (ascending) left.id.compareTo(right.id) else right.id.compareTo(left.id)
                            }
                        }
                        val now = Clock.System.now()
                        val keyed = list.map { bookmark ->
                            ensureActive()
                            val key = when (sort) {
                                BookmarksSortDialog.SORT_EXPIRES_AT -> bookmarkExpirationKey(bookmark, now)
                                BookmarksSortDialog.SORT_CREATED_AT -> bookmark.createdAt?.let {
                                    Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 }
                                }
                                else -> bookmark.id.toLong()
                            }
                            bookmark to key
                        }
                        val comparator = if (ascending) {
                            compareBy<Pair<Bookmark, Long?>, Long?>(nullsLast()) { it.second }
                        } else {
                            compareByDescending<Pair<Bookmark, Long?>, Long?>(nullsFirst()) { it.second }
                        }
                        var comparisons = 0
                        keyed.sortedWith { left, right ->
                            if (++comparisons % 1024 == 0) ensureActive()
                            comparator.compare(left, right)
                        }.map { it.first }
                    }
                    selection.retain(sorted.mapTo(HashSet()) { it.id.toString() })
                    adapter.submitList(sorted)
                    bookmarksEmpty = sorted.isEmpty()
                    binding.nothingHere.isVisible = bookmarksEmpty
                    if ((parentFragment as? FragmentHost)?.currentFragment === this@BookmarksFragment) {
                        sortBarBinding?.root?.isVisible = !bookmarksEmpty
                    }
                }
            }
        }
        if (requireContext().prefs().getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.positions.collectLatest {
                        (adapter as BookmarksAdapter).setVideoPositions(it)
                    }
                }
            }
        }
        if (requireContext().prefs().getBoolean(C.UI_BOOKMARK_TIME_LEFT, true)) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.ignoredUsers.collectLatest {
                        (adapter as BookmarksAdapter).setIgnoredUsers(it)
                    }
                }
            }
        }
    }

    private fun bookmarkExpirationKey(bookmark: Bookmark, now: Instant): Long? {
        if (bookmark.type?.lowercase() != "archive") return null
        val createdAt = bookmark.createdAt?.let(Instant::parseOrNull)
            ?.takeIf { it.toEpochMilliseconds() > 0 } ?: return null
        val userType = bookmark.userType ?: bookmark.userBroadcasterType
        val retentionDays = when {
            userType.isNullOrBlank() -> 7
            userType.lowercase() == "affiliate" -> 14
            else -> 60 // Partners, Prime, Turbo
        }
        val timeLeft = createdAt + retentionDays.days - now
        return timeLeft.takeIf { it.isPositive() }?.inWholeSeconds
    }

    override fun onResume() {
        super.onResume()
        // Refresh remote metadata on return without registering the view's collectors again.
        if (requireContext().prefs().getBoolean(C.UI_BOOKMARK_TIME_LEFT, true)) {
            viewModel.updateUsers(
                requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                TwitchApiHelper.getGQLHeaders(requireContext()),
                TwitchApiHelper.getHelixHeaders(requireContext()),
            )
        }
        val helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext())
        if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            viewModel.updateVideos(requireContext().filesDir.path, requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP), helixHeaders)
        }
    }

    override fun setupSortBar(sortBar: SortBarBinding) {
        sortBarBinding = sortBar
        sortBar.root.isVisible = !bookmarksEmpty
        sortBar.root.setOnClickListener {
            BookmarksSortDialog.newInstance(
                sort = viewModel.sort,
                order = viewModel.order,
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

    override fun onChange(sort: String, sortText: CharSequence, order: String, orderText: CharSequence, changed: Boolean, saveDefault: Boolean) {
        if ((parentFragment as? FragmentHost)?.currentFragment == this) {
            viewLifecycleOwner.lifecycleScope.launch {
                if (changed) {
                    adapter.submitList(emptyList())
                    viewModel.setFilter(sort, order)
                    viewModel.sortText.value = getString(R.string.sort_and_order, sortText, orderText)
                }
                if (saveDefault) {
                    val item = viewModel.getChannelSort("bookmarks")?.apply {
                        videoSort = sort
                        videoType = order
                    } ?: ChannelSort(
                        id = "bookmarks",
                        videoSort = sort,
                        videoType = order
                    )
                    viewModel.saveChannelSort(item)
                }
            }
        }
    }

    override fun startSavedSelection() {
        selection.start((parentFragment as? SavedSelectionHost)?.savedSelectionChrome)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (this::selection.isInitialized) selection.save(outState)
        super.onSaveInstanceState(outState)
    }

    override fun scrollToTop() {
        binding.recyclerView.scrollToPosition(0)
    }

    override fun onNetworkRestored() {
    }

    override fun onDestroyView() {
        super.onDestroyView()
        sortBarBinding = null
        _binding = null
    }
}




