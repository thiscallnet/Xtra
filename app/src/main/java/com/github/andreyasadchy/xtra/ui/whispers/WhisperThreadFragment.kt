package com.github.andreyasadchy.xtra.ui.whispers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.FragmentWhisperThreadBinding
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchUserSummary
import com.github.andreyasadchy.xtra.ui.inbox.messageRes
import com.github.andreyasadchy.xtra.ui.main.TwitchInboxMenuBinder
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class WhisperThreadFragment : Fragment() {
    private val args: WhisperThreadFragmentArgs by navArgs()
    private var _binding: FragmentWhisperThreadBinding? = null
    private val binding get() = _binding!!
    private val viewModel: WhisperThreadViewModel by viewModels {
        WhisperThreadViewModel.factory(
            (requireActivity().application as XtraApp).xtraModule.whispersRepository,
            TwitchUserSummary(args.peerId, args.peerLogin, args.peerDisplayName, args.peerImageUrl),
            args.threadId,
        )
    }
    private lateinit var adapter: WhisperMessagesAdapter
    private var previousCount = 0
    private var loadingOlder = false
    private var anchorPosition = 0
    private var anchorOffset = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWhisperThreadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setupWithNavController(findNavController())
        binding.toolbar.title = args.peerDisplayName
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.composerBar.visibility = if (WHISPER_SEND_ENABLED) View.VISIBLE else View.GONE
        adapter = WhisperMessagesAdapter(viewModel::retry)
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
            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = insets.top }
            binding.recyclerView.updatePadding(bottom = insets.bottom)
            binding.composerBar.updatePadding(bottom = composerBasePadding + insets.bottom)
            windowInsets
        }
        viewLifecycleOwner.lifecycleScope.launch { viewModel.uiState.collectLatest { render(it, layout) } }
        TwitchInboxMenuBinder.invalidateSummary()
    }

    override fun onResume() {
        super.onResume()
        if (this::adapter.isInitialized) viewModel.refreshLatest()
    }

    private fun render(state: WhisperThreadUiState, layout: LinearLayoutManager) {
        adapter.submitList(state.messages)
        binding.progress.visibility = if (state.initialLoading) View.VISIBLE else View.GONE
        binding.send.isEnabled = state.composer.trim().isNotEmpty()
        if (binding.composer.text?.toString() != state.composer) binding.composer.setText(state.composer)
        if (state.error == null) binding.composerLayout.error = null
        state.error?.let { binding.composerLayout.error = getString(it.messageRes()) }
        if (loadingOlder && !state.loadingOlder) {
            val added = state.messages.size - previousCount
            if (added > 0) layout.scrollToPositionWithOffset(anchorPosition + added, anchorOffset)
            loadingOlder = false
        } else if (previousCount == 0 && state.messages.isNotEmpty()) {
            layout.scrollToPosition(state.messages.lastIndex)
        }
        previousCount = state.messages.size
    }

    override fun onDestroyView() {
        binding.recyclerView.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
