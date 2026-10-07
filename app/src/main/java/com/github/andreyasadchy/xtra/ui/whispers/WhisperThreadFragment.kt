package com.github.andreyasadchy.xtra.ui.whispers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.FragmentWhisperThreadBinding
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchUserSummary
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.inbox.messageRes
import com.github.andreyasadchy.xtra.ui.main.TwitchInboxMenuBinder
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.tokenPrefs
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class WhisperThreadFragment : Fragment() {
    private val args: WhisperThreadFragmentArgs by navArgs()
    private val isEmbedded: Boolean get() = arguments?.getBoolean(ARG_EMBEDDED) == true
    private var _binding: FragmentWhisperThreadBinding? = null
    private val binding get() = _binding!!
    private val viewModel: WhisperThreadViewModel by viewModels {
        WhisperThreadViewModel.factory(
            (requireActivity().application as XtraApp).xtraModule.whispersRepository,
            TwitchUserSummary(args.peerId, args.peerLogin, args.peerDisplayName, args.peerImageUrl),
            args.threadId ?: (parentFragment as? WhispersFragment)?.detailThreadIdFor(args.peerId),
        )
    }
    private lateinit var adapter: WhisperMessagesAdapter
    private var previousCount = 0
    private var loadingOlder = false
    private var anchorPosition = 0
    private var anchorOffset = 0
    private var systemTopInset = 0
    private var lastReportedThreadId: String? = null
    private var lastReportedReadReceipt: WhisperThreadReadReceipt? = null
    private var lastReportedSuccessfulSendCount = 0L

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWhisperThreadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setupWithNavController(findNavController())
        binding.toolbar.title = getString(R.string.whispers)
        val embeddedHost = parentFragment as? WhispersFragment
        binding.toolbar.setNavigationOnClickListener {
            if (isEmbedded && embeddedHost != null) embeddedHost.closeDetailPane()
            else findNavController().navigateUp()
        }
        val peer = TwitchUserSummary(args.peerId, args.peerLogin, args.peerDisplayName, args.peerImageUrl)
        binding.peerName.text = peer.displayName
        binding.peerLogin.text = "@${peer.login}"
        binding.peerAvatar.setImageResource(R.drawable.baseline_person_black_24)
        peer.profileImageUrl?.takeIf { it.isNotBlank() }?.let { url ->
            requireContext().imageLoader.enqueue(
                ImageRequest.Builder(requireContext())
                    .data(url)
                    .crossfade(true)
                    .transformations(CircleCropTransformation())
                    .target(binding.peerAvatar)
                    .build(),
            )
        }
        binding.peerHeader.setOnClickListener { openPeerChannel(peer) }
        binding.peerName.setOnClickListener { openPeerChannel(peer) }
        binding.peerAvatar.setOnClickListener { openPeerChannel(peer) }
        binding.composerBar.visibility = if (WHISPER_SEND_ENABLED) View.VISIBLE else View.GONE
        val currentUser = TwitchUserSummary(
            id = requireContext().tokenPrefs().getString(C.USER_ID, null).orEmpty(),
            login = requireContext().tokenPrefs().getString(C.USERNAME, null).orEmpty(),
            displayName = requireContext().tokenPrefs().getString(C.USERNAME, null).orEmpty(),
            profileImageUrl = requireContext().tokenPrefs().getString(C.PROFILE_IMAGE_URL, null),
        )
        adapter = WhisperMessagesAdapter(peer, currentUser, viewModel::retry, ::openPeerChannel)
        val layout = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.recyclerView.layoutManager = layout
        binding.recyclerView.adapter = adapter
        binding.send.setOnClickListener { viewModel.send() }
        binding.composer.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { viewModel.setComposer(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        val composerBasePadding = binding.composerBar.paddingBottom
        binding.recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (!loadingOlder && (recyclerView.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition() <= 1) {
                    val manager = recyclerView.layoutManager as LinearLayoutManager
                    anchorPosition = manager.findFirstVisibleItemPosition()
                    anchorOffset = manager.findViewByPosition(anchorPosition)?.top ?: 0
                    loadingOlder = true
                    viewModel.loadOlder()
                }
            }
        })
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            systemTopInset = insets.top
            binding.recyclerView.updatePadding(bottom = insets.bottom)
            binding.composerBar.updatePadding(bottom = composerBasePadding + insets.bottom)
            updateAdaptivePresentation()
            windowInsets
        }
        viewLifecycleOwner.lifecycleScope.launch { viewModel.uiState.collectLatest { render(it, layout) } }
        view.post { updateAdaptivePresentation() }
        TwitchInboxMenuBinder.invalidateSummary()
    }

    override fun onResume() {
        super.onResume()
        if (this::adapter.isInitialized) {
            viewModel.refreshLatest()
            viewModel.setReadEligible(true)
        }
    }

    override fun onPause() {
        if (this::adapter.isInitialized) viewModel.setReadEligible(false)
        super.onPause()
    }

    private fun render(state: WhisperThreadUiState, layout: LinearLayoutManager) {
        val submittedAdapter = adapter
        submittedAdapter.submitList(state.messages) {
            if (_binding == null || binding.recyclerView.adapter !== submittedAdapter) return@submitList
            if (loadingOlder && !state.loadingOlder) {
                val added = state.messages.size - previousCount
                if (added > 0) layout.scrollToPositionWithOffset(anchorPosition + added, anchorOffset)
                loadingOlder = false
            } else if (previousCount == 0 && state.messages.isNotEmpty()) {
                layout.scrollToPosition(state.messages.lastIndex)
            }
            previousCount = state.messages.size
        }
        val showingFirstMessageState = isEmbedded && args.threadId == null &&
            state.threadId == null && state.messages.isEmpty()
        binding.emptyState.isVisible = showingFirstMessageState
        if (showingFirstMessageState) {
            binding.emptyDescription.text = getString(
                R.string.whisper_no_messages_description,
                state.peer.displayName.ifBlank { state.peer.login },
            )
        }
        if (isEmbedded) {
            if (state.successfulSendCount != lastReportedSuccessfulSendCount) {
                lastReportedSuccessfulSendCount = state.successfulSendCount
                if (state.successfulSendCount > 0) {
                    (parentFragment as? WhispersFragment)?.onWhisperSent(args.peerId)
                }
            }
            if (args.threadId == null && state.threadId != null && state.threadId != lastReportedThreadId) {
                lastReportedThreadId = state.threadId
                (parentFragment as? WhispersFragment)?.onThreadIdResolved(args.peerId, state.threadId)
            }
            state.lastReadReceipt?.takeIf { it != lastReportedReadReceipt }?.let { receipt ->
                lastReportedReadReceipt = receipt
                (parentFragment as? WhispersFragment)?.onThreadRead(receipt)
            }
        }
        binding.progress.visibility = if (state.initialLoading) View.VISIBLE else View.GONE
        binding.send.isEnabled = state.composer.trim().isNotEmpty()
        if (binding.composer.text?.toString() != state.composer) binding.composer.setText(state.composer)
        if (state.error == null) binding.composerLayout.error = null
        state.error?.let { binding.composerLayout.error = getString(it.messageRes()) }
    }

    internal fun updateAdaptivePresentation() {
        if (_binding == null || !isEmbedded) return
        val expanded = (parentFragment as? WhispersFragment)?.isDetailPaneExpanded == true
        binding.toolbar.isVisible = !expanded
        binding.toolbar.updateLayoutParams<MarginLayoutParams> {
            topMargin = if (expanded) 0 else systemTopInset
        }
        binding.peerHeader.updateLayoutParams<MarginLayoutParams> {
            topMargin = if (expanded) systemTopInset else 0
        }
    }

    internal val currentThreadId: String?
        get() = viewModel.uiState.value.threadId

    internal fun adoptThread(threadId: String) = viewModel.adoptThread(threadId)

    private fun openPeerChannel(user: TwitchUserSummary) {
        val host = parentFragment as? WhispersFragment
        if (isEmbedded && host != null) {
            host.openPeerChannelFromDetail(user)
            return
        }
        findNavController().navigate(
            ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                channelId = user.id,
                channelLogin = user.login,
                channelName = user.displayName,
                channelImage = user.profileImageUrl,
            ),
        )
    }

    override fun onDestroyView() {
        binding.recyclerView.adapter = null
        previousCount = 0
        loadingOlder = false
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val ARG_EMBEDDED = "embedded"
    }
}
