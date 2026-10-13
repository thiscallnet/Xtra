package com.github.andreyasadchy.xtra.ui.whispers

import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.FragmentWhisperThreadBinding
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchUserSummary
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperMessage
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.common.personPlaceholder
import com.github.andreyasadchy.xtra.ui.inbox.messageRes
import com.github.andreyasadchy.xtra.ui.main.TwitchInboxMenuBinder
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.tokenPrefs
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
    private var followLatest = true
    private var hasNewMessages = false
    private var positioningMessages = false
    private var committedMessages: List<WhisperMessage>? = null
    private var jumpLabel = 0
    private var readingAnchorId: String? = null
    private var readingAnchorOffset = 0
    private var compactControls = false
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
        savedInstanceState?.let {
            followLatest = it.getBoolean(STATE_FOLLOW_LATEST, true)
            hasNewMessages = it.getBoolean(STATE_NEW_MESSAGES, false)
            readingAnchorId = it.getString(STATE_ANCHOR_ID)
            readingAnchorOffset = it.getInt(STATE_ANCHOR_OFFSET)
        }
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
        val avatarPlaceholder = binding.peerAvatar.personPlaceholder()
        binding.peerAvatar.setImageDrawable(avatarPlaceholder)
        peer.profileImageUrl?.takeIf { it.isNotBlank() }?.let { url ->
            requireContext().imageLoader.enqueue(
                ImageRequest.Builder(requireContext())
                    .data(url)
                    .placeholder(avatarPlaceholder)
                    .error(avatarPlaceholder)
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
        adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        binding.recyclerView.adapter = adapter
        binding.jumpLatest.setOnClickListener {
            followLatest = true
            hasNewMessages = false
            positionMessages { layout.scrollToPosition(adapter.currentList.lastIndex) }
        }
        binding.send.setOnClickListener { viewModel.send() }
        binding.composer.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { viewModel.setComposer(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        val composerBasePadding = binding.composerBar.paddingBottom
        binding.recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (_binding?.recyclerView !== recyclerView) return
                if (!positioningMessages && !binding.root.isInLayout && dy != 0) {
                    followLatest = !recyclerView.canScrollVertically(1)
                    rememberReadingAnchor()
                    updateJumpLatest()
                }
                val state = viewModel.uiState.value
                if (state.hasOlder && !state.loadingOlder && !state.initialLoading &&
                    layout.findFirstVisibleItemPosition() <= 1
                ) viewModel.loadOlder()
            }
        })
        binding.recyclerView.addOnLayoutChangeListener { recyclerView, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (_binding?.recyclerView !== recyclerView) return@addOnLayoutChangeListener
            if (oldBottom > oldTop && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)) {
                if (followLatest) {
                    positionMessages { layout.scrollToPosition(adapter.currentList.lastIndex) }
                } else {
                    // A different width reflows message text. Keep the same message visible
                    // rather than restoring an offset beyond its newly measured height.
                    if (right - left != oldRight - oldLeft) readingAnchorOffset = 0
                    positionMessages {
                        readingAnchorId?.let { id ->
                            val position = adapter.currentList.indexOfFirst { it.id == id }
                            if (position >= 0) layout.scrollToPositionWithOffset(position, readingAnchorOffset)
                        }
                    }
                }
            }
        }
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
        val oldMessages = submittedAdapter.currentList
        val previousLastId = oldMessages.lastOrNull()?.id
        val visiblePosition = layout.findFirstVisibleItemPosition()
        val visibleAnchor = oldMessages.getOrNull(visiblePosition)?.id
        val anchorId = visibleAnchor ?: readingAnchorId
        val anchorOffset = if (visibleAnchor != null) {
            (layout.findViewByPosition(visiblePosition)?.top ?: binding.recyclerView.paddingTop) - binding.recyclerView.paddingTop
        } else readingAnchorOffset
        submittedAdapter.submitList(state.messages) {
            if (_binding == null || binding.recyclerView.adapter !== submittedAdapter) return@submitList
            if (committedMessages === submittedAdapter.currentList) {
                updateJumpLatest()
                return@submitList
            }
            committedMessages = submittedAdapter.currentList
            val previousLastPosition = state.messages.indexOfLast { it.id == previousLastId }
            if (!followLatest && previousLastPosition >= 0 &&
                (previousLastPosition + 1 until state.messages.size).any { !state.messages[it].isMine }
            ) hasNewMessages = true
            if (followLatest && state.messages.isNotEmpty()) {
                positionMessages { layout.scrollToPosition(state.messages.lastIndex) }
            } else if (anchorId != null) {
                val position = state.messages.indexOfFirst { it.id == anchorId }
                if (position >= 0) positionMessages { layout.scrollToPositionWithOffset(position, anchorOffset) }
            }
            binding.recyclerView.doOnLayout { updateJumpLatest() }
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

    private fun positionMessages(position: () -> Unit) {
        if (_binding == null || adapter.currentList.isEmpty()) return
        val recyclerView = binding.recyclerView
        val root = binding.root
        positioningMessages = true
        val applyPosition = {
            if (_binding?.recyclerView === recyclerView) {
                position()
                recyclerView.doOnLayout {
                    if (_binding?.recyclerView === recyclerView) {
                        positioningMessages = false
                        rememberReadingAnchor()
                        updateJumpLatest()
                    }
                }
            }
        }
        if (root.isInLayout) root.post { applyPosition() } else applyPosition()
    }

    private fun rememberReadingAnchor() {
        if (_binding == null || followLatest || positioningMessages) return
        val layout = binding.recyclerView.layoutManager as LinearLayoutManager
        val first = layout.findFirstVisibleItemPosition()
        val item = adapter.currentList.getOrNull(first) ?: return
        val view = layout.findViewByPosition(first) ?: return
        readingAnchorId = item.id
        readingAnchorOffset = view.top - binding.recyclerView.paddingTop
    }

    private fun updateJumpLatest() {
        if (_binding == null) return
        val root = binding.root
        // Text and visibility changes need a fresh measure after the parent's layout pass.
        if (root.isInLayout) {
            root.post { if (_binding?.root === root) updateJumpLatest() }
            return
        }
        val atLatest = !binding.recyclerView.canScrollVertically(1)
        if (atLatest && followLatest && !positioningMessages) hasNewMessages = false
        binding.jumpLatest.isVisible = adapter.currentList.isNotEmpty() && !atLatest
        val label = if (hasNewMessages) R.string.whisper_new_messages else R.string.whisper_jump_latest
        if (jumpLabel != label) {
            jumpLabel = label
            binding.jumpLatest.setText(label)
            binding.jumpLatest.contentDescription = if (hasNewMessages) {
                getString(R.string.whisper_new_messages) + ". " + getString(R.string.chat_scroll_to_latest)
            } else getString(R.string.chat_scroll_to_latest)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_FOLLOW_LATEST, followLatest)
        outState.putBoolean(STATE_NEW_MESSAGES, hasNewMessages)
        outState.putString(STATE_ANCHOR_ID, readingAnchorId)
        outState.putInt(STATE_ANCHOR_OFFSET, readingAnchorOffset)
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateAdaptivePresentation()
    }

    internal fun updateAdaptivePresentation() {
        if (_binding == null) return
        val expanded = isEmbedded && (parentFragment as? WhispersFragment)?.isDetailPaneExpanded == true
        val compact = !expanded && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            resources.configuration.screenHeightDp < 480
        if (compactControls != compact) {
            compactControls = compact
            (binding.peerHeader.parent as ViewGroup).removeView(binding.peerHeader)
            (binding.jumpLatest.parent as ViewGroup).removeView(binding.jumpLatest)
            if (compact) {
                binding.toolbar.title = null
                binding.toolbar.addView(binding.peerHeader, Toolbar.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
                binding.composerBar.addView(binding.jumpLatest, 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.BOTTOM
                    val margin = (4 * resources.displayMetrics.density).toInt()
                    marginStart = margin
                    marginEnd = margin
                })
            } else {
                binding.toolbar.title = getString(R.string.whispers)
                binding.root.addView(binding.peerHeader, 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                (binding.recyclerView.parent as FrameLayout).addView(binding.jumpLatest, 2, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    val margin = (12 * resources.displayMetrics.density).toInt()
                    setMargins(margin, margin, margin, margin)
                })
            }
        }
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
        positioningMessages = false
        committedMessages = null
        jumpLabel = 0
        compactControls = false
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val ARG_EMBEDDED = "embedded"
        const val STATE_FOLLOW_LATEST = "whisper_follow_latest"
        const val STATE_NEW_MESSAGES = "whisper_new_messages"
        const val STATE_ANCHOR_ID = "whisper_anchor_id"
        const val STATE_ANCHOR_OFFSET = "whisper_anchor_offset"
    }
}
