package com.github.andreyasadchy.xtra.ui.whispers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.slidingpanelayout.widget.SlidingPaneLayout
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.FragmentWhispersBinding
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchUserSummary
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperThread
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.inbox.messageRes
import com.github.andreyasadchy.xtra.ui.login.TwitchWebLoginActivity
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class WhispersFragment : Fragment() {
    private var _binding: FragmentWhispersBinding? = null
    private val binding get() = _binding!!
    private val viewModel: WhispersViewModel by viewModels {
        WhispersViewModel.factory((requireActivity().application as XtraApp).xtraModule.whispersRepository)
    }
    private lateinit var threadsAdapter: WhisperThreadsAdapter
    private lateinit var searchThreadsAdapter: WhisperThreadsAdapter
    private lateinit var usersAdapter: TwitchUsersAdapter
    private lateinit var paneBackCallback: OnBackPressedCallback
    private var selectedPeerId: String? = null
    private var selectedPeerLogin: String? = null
    private var selectedPeerName: String? = null
    private var selectedPeerImageUrl: String? = null
    private var selectedThreadId: String? = null
    private var compactDetailOpen = false
    private var navSelectionRestored = false
    private var lastMeasuredPaneSlideable: Boolean? = null
    private var paneModeMeasuredForCurrentView = false
    private var recyclerBottomPadding = 0
    private var searchResultsBottomPadding = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedPeerId = savedInstanceState?.getString(STATE_SELECTED_PEER_ID)
        selectedPeerLogin = savedInstanceState?.getString(STATE_SELECTED_PEER_LOGIN)
        selectedPeerName = savedInstanceState?.getString(STATE_SELECTED_PEER_NAME)
        selectedPeerImageUrl = savedInstanceState?.getString(STATE_SELECTED_PEER_IMAGE_URL)
        selectedThreadId = savedInstanceState?.getString(STATE_SELECTED_THREAD_ID)
        compactDetailOpen = savedInstanceState?.getBoolean(STATE_COMPACT_DETAIL_OPEN) ?: false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWhispersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        paneModeMeasuredForCurrentView = false
        restoreSelectionFromNavEntry()
        recyclerBottomPadding = binding.recyclerView.paddingBottom
        searchResultsBottomPadding = binding.searchResults.paddingBottom
        binding.toolbar.title = getString(R.string.whispers)
        binding.toolbar.setupWithNavController(findNavController())
        threadsAdapter = WhisperThreadsAdapter(::openThread, ::openPeerChannel)
        searchThreadsAdapter = WhisperThreadsAdapter(::openThread, ::openPeerChannel)
        usersAdapter = TwitchUsersAdapter(::openUser, ::openPeerChannel)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = threadsAdapter
        binding.searchResults.layoutManager = LinearLayoutManager(requireContext())
        binding.searchResults.adapter = ConcatAdapter(
            WhisperSectionHeaderAdapter(R.string.conversations),
            searchThreadsAdapter,
            WhisperSectionHeaderAdapter(R.string.people),
            usersAdapter,
        )
        binding.searchInput.addTextChangedListener(SimpleTextWatcher { viewModel.setSearchQuery(it) })
        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
        binding.emptyText.setOnClickListener {
            when (viewModel.uiState.value.error) {
                com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxError.SignedOut,
                com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxError.RequiresReauth ->
                    startActivity(android.content.Intent(requireContext(), TwitchWebLoginActivity::class.java).putExtra(TwitchWebLoginActivity.EXTRA_REAUTHORIZE, true))
                else -> viewModel.refresh()
            }
        }
        binding.recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                val layout = recyclerView.layoutManager as LinearLayoutManager
                if (layout.findLastVisibleItemPosition() >= threadsAdapter.itemCount - 5) viewModel.loadMore()
            }
        })
        paneBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeDetailPane()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, paneBackCallback)
        binding.slidingPaneLayout.addPanelSlideListener(object : SlidingPaneLayout.PanelSlideListener {
            override fun onPanelSlide(panel: View, slideOffset: Float) = Unit

            override fun onPanelOpened(panel: View) {
                if (binding.slidingPaneLayout.isSlideable) compactDetailOpen = true
                updatePaneState()
            }

            override fun onPanelClosed(panel: View) {
                if (binding.slidingPaneLayout.isSlideable) compactDetailOpen = false
                updatePaneState()
            }
        })
        binding.slidingPaneLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val isSlideable = binding.slidingPaneLayout.isSlideable
            val previousWasSlideable = lastMeasuredPaneSlideable
            if (!paneModeMeasuredForCurrentView) {
                paneModeMeasuredForCurrentView = true
                lastMeasuredPaneSlideable = isSlideable
                // In wide mode the selected conversation is already visible. When
                // that layout becomes compact, carry the visible detail forward.
                if (isSlideable && previousWasSlideable == false && selectedPeerId != null) {
                    compactDetailOpen = true
                }
                updatePaneState()
            } else if (isSlideable != previousWasSlideable) {
                lastMeasuredPaneSlideable = isSlideable
                if (isSlideable && previousWasSlideable == false && selectedPeerId != null) {
                    compactDetailOpen = true
                }
                updatePaneState()
            }
            if (isSlideable && compactDetailOpen && !binding.slidingPaneLayout.isOpen) {
                binding.slidingPaneLayout.post {
                    if (_binding != null && binding.slidingPaneLayout.isSlideable &&
                        compactDetailOpen && !binding.slidingPaneLayout.isOpen
                    ) {
                        binding.slidingPaneLayout.openPane()
                    }
                }
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = insets.top }
            val bottomInset = if (requireActivity().findViewById<View>(R.id.navBarContainer)?.isVisible == false) {
                insets.bottom
            } else {
                0
            }
            binding.recyclerView.updatePadding(bottom = recyclerBottomPadding + bottomInset)
            binding.searchResults.updatePadding(bottom = searchResultsBottomPadding + bottomInset)
            windowInsets
        }
        viewLifecycleOwner.lifecycleScope.launch { viewModel.uiState.collectLatest(::render) }
        restoreSelectedDetail()
    }

    override fun onResume() {
        super.onResume()
        if (this::threadsAdapter.isInitialized) viewModel.refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SELECTED_PEER_ID, selectedPeerId)
        outState.putString(STATE_SELECTED_PEER_LOGIN, selectedPeerLogin)
        outState.putString(STATE_SELECTED_PEER_NAME, selectedPeerName)
        outState.putString(STATE_SELECTED_PEER_IMAGE_URL, selectedPeerImageUrl)
        outState.putString(STATE_SELECTED_THREAD_ID, selectedThreadId)
        outState.putBoolean(STATE_COMPACT_DETAIL_OPEN, compactDetailOpenForSave())
        super.onSaveInstanceState(outState)
    }

    private fun compactDetailOpenForSave(): Boolean {
        if (_binding == null) return compactDetailOpen
        return if (binding.slidingPaneLayout.isSlideable) {
            binding.slidingPaneLayout.isOpen || compactDetailOpen
        } else {
            selectedPeerId != null
        }
    }

    private fun render(state: WhispersUiState) {
        threadsAdapter.submitList(state.filteredConversations, selectedPeerId)
        searchThreadsAdapter.submitList(state.filteredConversations, selectedPeerId)
        usersAdapter.submitList(state.searchResults, selectedPeerId)
        val searching = state.searchQuery.isNotBlank()
        binding.recyclerView.visibility = if (searching) View.GONE else View.VISIBLE
        binding.searchResults.visibility = if (searching) View.VISIBLE else View.GONE
        binding.progress.visibility = if (state.loading && state.conversations.isEmpty()) View.VISIBLE else View.GONE
        binding.swipeRefresh.isRefreshing = state.refreshing
        binding.emptyText.visibility = if (!state.loading && !searching && state.conversations.isEmpty()) View.VISIBLE else View.GONE
        if (searching && !state.searching && state.searchResults.isEmpty() && state.filteredConversations.isEmpty()) {
            binding.emptyText.visibility = View.VISIBLE
            binding.emptyText.setText(R.string.no_people_found)
        } else if (!searching) binding.emptyText.setText(R.string.no_whispers_yet)
        state.error?.let { binding.emptyText.text = getString(it.messageRes()) }
        if (state.error != null && state.conversations.isNotEmpty()) {
            Snackbar.make(binding.root, state.error.messageRes(), Snackbar.LENGTH_LONG)
                .setAction(R.string.retry) { viewModel.refresh() }
                .show()
        }
    }

    private fun restoreSelectedDetail() {
        val restored = childFragmentManager.findFragmentById(R.id.detailContainer) as? WhisperThreadFragment
        if (restored != null) {
            selectedPeerId = selectedPeerId ?: restored.arguments?.getString(ARG_PEER_ID)
            selectedPeerLogin = selectedPeerLogin ?: restored.arguments?.getString(ARG_PEER_LOGIN)
            selectedPeerName = selectedPeerName ?: restored.arguments?.getString(ARG_PEER_DISPLAY_NAME)
            selectedPeerImageUrl = selectedPeerImageUrl ?: restored.arguments?.getString(ARG_PEER_IMAGE_URL)
            selectedThreadId = selectedThreadId ?: restored.arguments?.getString(ARG_THREAD_ID)
            binding.detailPlaceholder.isVisible = false
            binding.detailContainer.isVisible = true
            updatePaneState()
            if (navSelectionRestored) clearNavigationSelectionState()
            return
        }
        val peerId = selectedPeerId ?: return
        val login = selectedPeerLogin ?: return
        val name = selectedPeerName ?: return
        showDetailFragment(
            TwitchUserSummary(peerId, login, name, selectedPeerImageUrl),
            selectedThreadId,
            openPane = false,
            afterCommit = { if (navSelectionRestored) clearNavigationSelectionState() },
        )
    }

    private fun restoreSelectionFromNavEntry() {
        if (selectedPeerId != null) {
            clearNavigationSelectionState()
            return
        }
        val state = runCatching {
            findNavController().getBackStackEntry(R.id.whispersFragment).savedStateHandle
        }.getOrNull() ?: return
        selectedPeerId = state.get(NAV_SELECTED_PEER_ID)
        if (selectedPeerId == null) return
        selectedPeerLogin = state.get(NAV_SELECTED_PEER_LOGIN)
        selectedPeerName = state.get(NAV_SELECTED_PEER_NAME)
        selectedPeerImageUrl = state.get(NAV_SELECTED_PEER_IMAGE_URL)
        selectedThreadId = state.get(NAV_SELECTED_THREAD_ID)
        compactDetailOpen = state.get<Boolean>(NAV_COMPACT_DETAIL_OPEN) ?: false
        navSelectionRestored = true
    }

    private fun clearNavigationSelectionState() {
        val state = runCatching {
            findNavController().getBackStackEntry(R.id.whispersFragment).savedStateHandle
        }.getOrNull() ?: return
        state.remove<String>(NAV_SELECTED_PEER_ID)
        state.remove<String>(NAV_SELECTED_PEER_LOGIN)
        state.remove<String>(NAV_SELECTED_PEER_NAME)
        state.remove<String>(NAV_SELECTED_PEER_IMAGE_URL)
        state.remove<String>(NAV_SELECTED_THREAD_ID)
        state.remove<Boolean>(NAV_COMPACT_DETAIL_OPEN)
        navSelectionRestored = false
    }

    private fun renderSelection() {
        val state = viewModel.uiState.value
        threadsAdapter.submitList(state.filteredConversations, selectedPeerId)
        searchThreadsAdapter.submitList(state.filteredConversations, selectedPeerId)
        usersAdapter.submitList(state.searchResults, selectedPeerId)
    }

    private fun updatePaneState() {
        if (_binding == null) return
        val layout = binding.slidingPaneLayout
        paneBackCallback.isEnabled = layout.isSlideable && layout.isOpen
        updateDetailPresentation()
        val detail = childFragmentManager.findFragmentById(R.id.detailContainer) ?: return
        if (detail.arguments?.getString(ARG_PEER_ID) != selectedPeerId) return
        if (childFragmentManager.isStateSaved) return
        childFragmentManager.commit {
            setMaxLifecycle(
                detail,
                if (paneModeMeasuredForCurrentView) {
                    if (!layout.isSlideable || layout.isOpen) Lifecycle.State.RESUMED else Lifecycle.State.STARTED
                } else if (compactDetailOpen) {
                    Lifecycle.State.RESUMED
                } else {
                    Lifecycle.State.STARTED
                },
            )
        }
    }

    private fun openThread(thread: WhisperThread) = showDetailFragment(thread.peer, thread.id, openPane = true)

    private fun openUser(user: TwitchUserSummary) = showDetailFragment(user, threadId = null, openPane = true)

    private fun showDetailFragment(
        user: TwitchUserSummary,
        threadId: String?,
        openPane: Boolean,
        afterCommit: (() -> Unit)? = null,
    ) {
        if (binding.searchInput.hasFocus()) {
            WindowInsetsControllerCompat(requireActivity().window, binding.root)
                .hide(WindowInsetsCompat.Type.ime())
            binding.searchInput.clearFocus()
        }
        selectedPeerId = user.id
        selectedPeerLogin = user.login
        selectedPeerName = user.displayName
        selectedPeerImageUrl = user.profileImageUrl
        renderSelection()

        binding.detailPlaceholder.isVisible = false
        binding.detailContainer.isVisible = true
        val existing = childFragmentManager.findFragmentById(R.id.detailContainer) as? WhisperThreadFragment
        val existingPeerMatches = existing?.arguments?.getString(ARG_PEER_ID) == user.id
        val existingThreadId = existing?.currentThreadId.takeIf { existingPeerMatches }
        selectedThreadId = threadId ?: existingThreadId
        val replaceDetail = !existingPeerMatches ||
            (threadId != null && existingThreadId != null && threadId != existingThreadId)
        if (replaceDetail) {
            val args = Bundle().apply {
                putString(ARG_PEER_ID, user.id)
                putString(ARG_PEER_LOGIN, user.login)
                putString(ARG_PEER_DISPLAY_NAME, user.displayName)
                putString(ARG_PEER_IMAGE_URL, user.profileImageUrl)
                putString(ARG_THREAD_ID, threadId)
                putBoolean(ARG_EMBEDDED, true)
            }
            childFragmentManager.commit {
                setReorderingAllowed(true)
                replace(R.id.detailContainer, WhisperThreadFragment::class.java, args, DETAIL_FRAGMENT_TAG)
                runOnCommit {
                    updatePaneState()
                    afterCommit?.invoke()
                }
            }
        } else if (threadId != null && existingThreadId == null) {
            existing.adoptThread(threadId)
        }
        if (openPane) {
            compactDetailOpen = true
            if (binding.slidingPaneLayout.isSlideable && !binding.slidingPaneLayout.isOpen) {
                binding.slidingPaneLayout.openPane()
            }
        }
        updatePaneState()
        if (!replaceDetail) afterCommit?.invoke()
    }

    internal fun closeDetailPane() {
        if (_binding != null && binding.slidingPaneLayout.isSlideable && binding.slidingPaneLayout.isOpen) {
            compactDetailOpen = false
            binding.slidingPaneLayout.closePane()
        }
    }

    internal val isDetailPaneExpanded: Boolean
        get() = _binding != null && !binding.slidingPaneLayout.isSlideable

    internal fun detailThreadIdFor(peerId: String): String? =
        selectedThreadId.takeIf { selectedPeerId == peerId }

    internal fun onThreadRead(receipt: WhisperThreadReadReceipt) {
        if (selectedPeerId != null && receipt.threadId == selectedThreadId) {
            viewModel.markThreadRead(receipt.threadId)
            viewModel.refreshWhenIdle()
        }
    }

    internal fun onThreadIdResolved(peerId: String, threadId: String) {
        if (selectedPeerId != peerId || selectedThreadId == threadId) return
        selectedThreadId = threadId
        viewModel.refreshWhenIdle()
    }

    internal fun onWhisperSent(peerId: String) {
        if (selectedPeerId == peerId) viewModel.refreshWhenIdle()
    }

    internal fun openPeerChannelFromDetail(user: TwitchUserSummary) {
        openPeerChannel(user)
    }

    internal fun updateDetailPresentation() {
        (childFragmentManager.findFragmentById(R.id.detailContainer) as? WhisperThreadFragment)
            ?.updateAdaptivePresentation()
    }

    private fun openPeerChannel(user: TwitchUserSummary) {
        saveSelectionForChannelReturn()
        findNavController().navigate(
            ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                channelId = user.id,
                channelLogin = user.login,
                channelName = user.displayName,
                channelImage = user.profileImageUrl,
            ),
        )
    }

    private fun saveSelectionForChannelReturn() {
        val peerId = selectedPeerId ?: return
        val login = selectedPeerLogin ?: return
        val name = selectedPeerName ?: return
        val state = runCatching {
            findNavController().getBackStackEntry(R.id.whispersFragment).savedStateHandle
        }.getOrNull() ?: return
        state[NAV_SELECTED_PEER_ID] = peerId
        state[NAV_SELECTED_PEER_LOGIN] = login
        state[NAV_SELECTED_PEER_NAME] = name
        state[NAV_SELECTED_PEER_IMAGE_URL] = selectedPeerImageUrl
        state[NAV_SELECTED_THREAD_ID] = selectedThreadId
        state[NAV_COMPACT_DETAIL_OPEN] = compactDetailOpenForSave()
    }

    override fun onDestroyView() {
        binding.recyclerView.adapter = null
        binding.searchResults.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val ARG_PEER_ID = "peerId"
        const val ARG_PEER_LOGIN = "peerLogin"
        const val ARG_PEER_DISPLAY_NAME = "peerDisplayName"
        const val ARG_PEER_IMAGE_URL = "peerImageUrl"
        const val ARG_THREAD_ID = "threadId"
        const val ARG_EMBEDDED = "embedded"
        const val DETAIL_FRAGMENT_TAG = "whisper-detail"
        const val STATE_SELECTED_PEER_ID = "selectedPeerId"
        const val STATE_SELECTED_PEER_LOGIN = "selectedPeerLogin"
        const val STATE_SELECTED_PEER_NAME = "selectedPeerName"
        const val STATE_SELECTED_PEER_IMAGE_URL = "selectedPeerImageUrl"
        const val STATE_SELECTED_THREAD_ID = "selectedThreadId"
        const val STATE_COMPACT_DETAIL_OPEN = "compactDetailOpen"
        const val NAV_SELECTED_PEER_ID = "adaptiveWhispers.selectedPeerId"
        const val NAV_SELECTED_PEER_LOGIN = "adaptiveWhispers.selectedPeerLogin"
        const val NAV_SELECTED_PEER_NAME = "adaptiveWhispers.selectedPeerName"
        const val NAV_SELECTED_PEER_IMAGE_URL = "adaptiveWhispers.selectedPeerImageUrl"
        const val NAV_SELECTED_THREAD_ID = "adaptiveWhispers.selectedThreadId"
        const val NAV_COMPACT_DETAIL_OPEN = "adaptiveWhispers.compactDetailOpen"
    }
}

private class SimpleTextWatcher(private val onChanged: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = onChanged(s?.toString().orEmpty())
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}
