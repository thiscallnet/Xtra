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
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ConcatAdapter
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.FragmentWhispersBinding
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchUserSummary
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.inbox.messageRes
import com.github.andreyasadchy.xtra.ui.login.TwitchWebLoginActivity
import com.google.android.material.snackbar.Snackbar
import androidx.lifecycle.lifecycleScope
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWhispersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
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
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = insets.top }
            windowInsets
        }
        viewLifecycleOwner.lifecycleScope.launch { viewModel.uiState.collectLatest(::render) }
    }

    override fun onResume() {
        super.onResume()
        if (this::threadsAdapter.isInitialized) viewModel.refresh()
    }

    private fun render(state: WhispersUiState) {
        threadsAdapter.submitList(state.filteredConversations)
        searchThreadsAdapter.submitList(state.filteredConversations)
        usersAdapter.submitList(state.searchResults)
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

    private fun openThread(thread: com.github.andreyasadchy.xtra.model.twitchinbox.WhisperThread) = openUser(thread.peer, thread.id)

    private fun openUser(user: TwitchUserSummary) = openUser(user, null)

    private fun openPeerChannel(user: TwitchUserSummary) {
        findNavController().navigate(
            ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                channelId = user.id,
                channelLogin = user.login,
                channelName = user.displayName,
                channelImage = user.profileImageUrl,
            ),
        )
    }

    private fun openUser(user: TwitchUserSummary, threadId: String?) {
        val args = Bundle().apply {
            putString("peerId", user.id)
            putString("peerLogin", user.login)
            putString("peerDisplayName", user.displayName)
            putString("peerImageUrl", user.profileImageUrl)
            putString("threadId", threadId)
        }
        findNavController().navigate(R.id.whisperThreadFragment, args)
    }

    override fun onDestroyView() {
        binding.recyclerView.adapter = null
        binding.searchResults.adapter = null
        _binding = null
        super.onDestroyView()
    }
}

private class SimpleTextWatcher(private val onChanged: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = onChanged(s?.toString().orEmpty())
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}
