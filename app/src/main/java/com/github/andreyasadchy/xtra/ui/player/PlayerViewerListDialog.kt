package com.github.andreyasadchy.xtra.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentViewerListBinding
import com.github.andreyasadchy.xtra.model.ui.ChannelViewer
import com.github.andreyasadchy.xtra.model.ui.ChannelViewerList
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.player.PlayerViewerListViewModel.Companion.PlayerViewerListViewModelFactory
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

@OptIn(FlowPreview::class)
class PlayerViewerListDialog : BottomSheetDialogFragment() {
    private var _binding: FragmentViewerListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PlayerViewerListViewModel by viewModels { PlayerViewerListViewModelFactory }
    private val query = MutableStateFlow("")
    private var profileJob: Job? = null
    private lateinit var adapter: ViewerListAdapter
    private var catalog: ChannelViewerList? = null
    private var matchCount = 0
    private var renderedQuery = ""
    private var networkLibrary: String? = null
    private var gqlHeaders: Map<String, String> = emptyMap()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentViewerListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), includeToken = true)
        query.value = savedInstanceState?.getString(FILTER).orEmpty()
        adapter = ViewerListAdapter(::openViewer)
        binding.viewerList.layoutManager = LinearLayoutManager(requireContext())
        binding.viewerList.adapter = adapter
        binding.filterInput.setText(query.value)
        if (savedInstanceState == null) binding.root.requestFocus()
        binding.filterInput.doAfterTextChanged { query.value = it?.toString().orEmpty() }
        binding.filterInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                dialog?.window?.let { WindowInsetsControllerCompat(it, binding.root).hide(WindowInsetsCompat.Type.ime()) }
                binding.root.requestFocus()
                true
            } else false
        }
        binding.retryButton.setOnClickListener { loadViewerList() }
        binding.viewerList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = requestProfiles()
        })
        loadViewerList()
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    var previousQuery: String? = null
                    combine(viewModel.viewerList, viewModel.profiles, query.debounce(150)) { list, profiles, text ->
                        Triple(list, profiles, text.trim())
                    }.collectLatest { (list, profiles, text) ->
                        val rows = withContext(Dispatchers.Default) { buildRows(list, profiles, text) }
                        val page = binding
                        catalog = list
                        renderedQuery = text
                        matchCount = rows.count { it is ViewerRow.Person }
                        val scrollToTop = previousQuery != null && previousQuery != text
                        previousQuery = text
                        adapter.submitList(rows) {
                            if (_binding === page) {
                                if (scrollToTop) page.viewerList.scrollToPosition(0)
                                page.viewerList.post { if (_binding === page) requestProfiles() }
                            }
                        }
                        renderStatus()
                    }
                }
                launch {
                    combine(viewModel.isLoading, viewModel.hasError) { _, _ -> Unit }.collect { renderStatus() }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.layoutParams = sheet.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
            BottomSheetBehavior.from(sheet).apply { skipCollapsed = true; state = BottomSheetBehavior.STATE_EXPANDED }
        }
        binding.viewerList.post { if (_binding != null) requestProfiles() }
    }

    private fun loadViewerList() = viewModel.loadViewerList(requireArguments().getString(LOGIN), networkLibrary, gqlHeaders)

    private suspend fun buildRows(list: ChannelViewerList?, profiles: Map<String, ChannelViewer>, text: String): List<ViewerRow> {
        if (list == null) return emptyList()
        val groups = listOf(
            R.string.broadcaster to list.broadcasters, R.string.moderators to list.moderators,
            R.string.vips to list.vips, R.string.viewers to list.viewers,
        )
        return buildList {
            groups.forEachIndexed { group, (title, people) ->
                val matches = buildList {
                    people.forEachIndexed { index, source ->
                        if (index and 255 == 0) coroutineContext.ensureActive()
                        val person = profiles[source.login.lowercase()] ?: source
                        if (text.isEmpty() || person.login.contains(text, ignoreCase = true) ||
                            person.displayName?.contains(text, ignoreCase = true) == true
                        ) add(ViewerRow.Person(group, person))
                    }
                }
                if (matches.isNotEmpty()) {
                    add(ViewerRow.Header(group, title, matches.size))
                    addAll(matches)
                }
            }
        }
    }

    private fun renderStatus() = with(binding) {
        val data = catalog
        val loading = data == null && (viewModel.isLoading.value || viewModel.viewerList.value != null)
        val failed = data == null && viewModel.hasError.value
        loadingState.isVisible = loading
        errorState.isVisible = failed && !loading
        emptyState.isVisible = data != null && matchCount == 0
        emptyState.setText(if (renderedQuery.isBlank()) R.string.viewer_list_empty else R.string.viewer_filter_no_matches)
        viewerList.isVisible = data != null && matchCount > 0
        filterScope.isVisible = data != null
        data?.let {
            filterScope.text = getString(R.string.viewer_filter_scope, it.broadcasters.size + it.moderators.size + it.vips.size + it.viewers.size)
        }
        userCount.isVisible = data?.count != null
        data?.count?.let {
            userCount.text = getString(R.string.user_count, TwitchApiHelper.formatCount(it, requireContext().prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true)))
        }
    }

    private fun requestProfiles() {
        val page = _binding ?: return
        if (!page.viewerList.isShown || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || profileJob?.isActive == true) return
        val layout = page.viewerList.layoutManager as LinearLayoutManager
        val start = layout.findFirstVisibleItemPosition().coerceAtLeast(0)
        val end = (layout.findLastVisibleItemPosition().coerceAtLeast(start) + 50).coerceAtMost(adapter.itemCount)
        if (start >= end) return
        val batch = adapter.currentList.subList(start, end).mapNotNull { (it as? ViewerRow.Person)?.value }
            .filter { !viewModel.hasLoadedProfile(it.login) }.distinctBy { it.login.lowercase() }.take(100)
        if (batch.isEmpty()) return
        profileJob = viewLifecycleOwner.lifecycleScope.launch {
            val job = coroutineContext[Job]
            try {
                val enriched = viewModel.enrichProfiles(networkLibrary, gqlHeaders, batch)
                coroutineContext.ensureActive()
                viewModel.recordProfiles(batch, enriched)
            } finally {
                if (profileJob === job) profileJob = null
                page.viewerList.post { if (_binding === page) requestProfiles() }
            }
        }
    }

    private fun openViewer(viewer: ChannelViewer) {
        val parent = parentFragment ?: return
        val current = viewModel.profiles.value[viewer.login.lowercase()] ?: viewer
        parent.findNavController().navigate(ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
            channelId = current.id, channelLogin = current.login, channelName = current.displayName ?: current.login,
            channelImage = current.profileImageURL,
        ))
        (parent as? Media3PlayerFragment)?.minimize()
        dismiss()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(FILTER, query.value)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        profileJob?.cancel()
        profileJob = null
        super.onStop()
    }

    override fun onDestroyView() {
        binding.viewerList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val LOGIN = "login"
        private const val FILTER = "viewer_filter"
        fun newInstance(login: String) = PlayerViewerListDialog().apply { arguments = Bundle().apply { putString(LOGIN, login) } }
    }
}




