package com.github.andreyasadchy.xtra.ui.notifications

import android.content.Intent
import android.net.Uri
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
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.FragmentTwitchNotificationsBinding
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchNotification
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchNotificationAction
import com.github.andreyasadchy.xtra.ui.inbox.messageRes
import com.github.andreyasadchy.xtra.ui.login.TwitchWebLoginActivity
import com.github.andreyasadchy.xtra.ui.main.TwitchInboxMenuBinder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class TwitchNotificationsFragment : Fragment() {
    private var _binding: FragmentTwitchNotificationsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TwitchNotificationsViewModel by viewModels {
        TwitchNotificationsViewModel.factory((requireActivity().application as XtraApp).xtraModule.twitchNotificationsRepository)
    }
    private lateinit var adapter: TwitchNotificationsAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTwitchNotificationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val navController = findNavController()
        binding.toolbar.setupWithNavController(navController)
        binding.toolbar.title = getString(R.string.notifications)
        adapter = TwitchNotificationsAdapter(
            onClick = { item -> viewModel.markRead(item, TwitchInboxMenuBinder::invalidateSummary); openAction(item) },
            onAvatarClick = { item -> (item.action as? TwitchNotificationAction.Channel)?.let(::openChannel) },
            onMarkRead = { item -> viewModel.markRead(item, TwitchInboxMenuBinder::invalidateSummary) },
            onDismiss = viewModel::dismiss,
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.markAllNotificationsRead) {
                confirmMarkAllAsSeen()
                true
            } else {
                false
            }
        }
        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
        binding.retryButton.setOnClickListener {
            if (viewModel.uiState.value.error?.let { it is com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxError.RequiresReauth || it is com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxError.SignedOut } == true) {
                startActivity(Intent(requireContext(), TwitchWebLoginActivity::class.java).putExtra(TwitchWebLoginActivity.EXTRA_REAUTHORIZE, true))
            } else viewModel.refresh()
        }
        binding.recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                val layout = recyclerView.layoutManager as LinearLayoutManager
                if (layout.findLastVisibleItemPosition() >= adapter.itemCount - 5) viewModel.loadMore()
            }
        })
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = insets.top }
            binding.recyclerView.updatePadding(bottom = insets.bottom)
            windowInsets
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { render(it) }
        }
        TwitchInboxMenuBinder.invalidateSummary()
    }

    override fun onResume() {
        super.onResume()
        if (this::adapter.isInitialized) viewModel.refresh()
    }

    private fun render(state: NotificationsUiState) {
        adapter.submitList(state.items)
        binding.progress.visibility = if (state.initialLoading && state.items.isEmpty()) View.VISIBLE else View.GONE
        binding.swipeRefresh.isRefreshing = state.refreshing
        binding.emptyState.visibility = if (!state.initialLoading && state.items.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyText.visibility = if (state.error == null) View.VISIBLE else View.GONE
        binding.errorText.visibility = if (state.error != null) View.VISIBLE else View.GONE
        binding.retryButton.visibility = if (state.error != null) View.VISIBLE else View.GONE
        binding.toolbar.menu.findItem(R.id.markAllNotificationsRead)?.apply {
            isVisible = state.items.isNotEmpty()
            isEnabled = !state.markingAllAsSeen && !state.initialLoading && !state.refreshing && !state.loadingNextPage &&
                (state.items.any { it.isUnread } || state.canLoadMore)
        }
        state.error?.let { binding.errorText.setText(it.messageRes()) }
        if (state.error != null && state.items.isNotEmpty()) {
            Snackbar.make(binding.root, state.error.messageRes(), Snackbar.LENGTH_LONG)
                .setAction(R.string.retry) { viewModel.refresh() }
                .show()
        }
    }

    private fun openAction(item: TwitchNotification) {
        val action = item.action ?: return
        when (action) {
            is TwitchNotificationAction.Channel -> openChannel(action)
            is TwitchNotificationAction.Game -> findNavController().navigate(R.id.action_global_gamePagerFragment, Bundle().apply {
                putString("gameId", action.id)
                putString("gameName", action.name)
            })
            is TwitchNotificationAction.Video -> openTwitchUrl("https://www.twitch.tv/videos/${action.id}")
            is TwitchNotificationAction.Clip -> openTwitchUrl("https://clips.twitch.tv/${action.slug}")
            is TwitchNotificationAction.TwitchWebUrl -> openTwitchUrl(action.url)
            TwitchNotificationAction.None -> Unit
        }
    }

    private fun openChannel(action: TwitchNotificationAction.Channel) {
        findNavController().navigate(R.id.action_global_channelPagerFragment, Bundle().apply {
            putString("channelId", action.id)
            putString("channelLogin", action.login)
            putString("channelName", action.displayName)
            putString("channelImage", action.imageUrl)
        })
    }

    private fun confirmMarkAllAsSeen() {
        val state = viewModel.uiState.value
        if (state.markingAllAsSeen || state.initialLoading || state.refreshing || state.loadingNextPage || state.items.isEmpty() || (state.items.none { it.isUnread } && !state.canLoadMore)) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mark_all_notifications_seen_title)
            .setMessage(R.string.mark_all_notifications_seen_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.mark_all_as_seen) { _, _ ->
                viewModel.markAllAsSeen(TwitchInboxMenuBinder::invalidateSummary)
            }
            .show()
    }

    private fun openTwitchUrl(url: String) {
        if (com.github.andreyasadchy.xtra.repository.isSafeTwitchUrl(url)) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    override fun onDestroyView() {
        binding.recyclerView.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
