package com.github.andreyasadchy.xtra.ui.multiview

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentMultiviewBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.multiview.playback.MultiviewPlaybackSnapshot
import com.github.andreyasadchy.xtra.ui.multiview.playback.MultiviewQualityMode
import com.github.andreyasadchy.xtra.ui.multiview.ui.AddMultiviewStreamsSheet
import com.github.andreyasadchy.xtra.ui.multiview.ui.MultiviewLayoutManager
import com.github.andreyasadchy.xtra.ui.multiview.ui.MultiviewLayoutMode
import com.github.andreyasadchy.xtra.ui.multiview.ui.MultiviewSlotView
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MultiviewFragment : Fragment(R.layout.fragment_multiview) {
    private var _binding: FragmentMultiviewBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MultiviewViewModel by viewModels { MultiviewViewModel.MultiviewViewModelFactory }
    private val slotViews = linkedMapOf<String, MultiviewSlotView>()
    private val controlsHandler = Handler(Looper.getMainLooper())
    private val hideControls = Runnable {
        if (controlsLockCount > 0) return@Runnable
        setControlsOverlayVisible(false)
        slotViews.values.forEach { it.setControlsVisible(false) }
    }
    private var latestState = MultiviewSessionState()
    private var latestPlayback: Map<String, MultiviewPlaybackSnapshot> = emptyMap()
    private var renderedChatKey: String? = null
    private var renderedLayoutKey: String? = null
    private var previousNavBarVisibility = View.VISIBLE
    private var controlsLockCount = 0

    private val bindingOrNull: FragmentMultiviewBinding?
        get() = _binding

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentMultiviewBinding.bind(view)

        requireActivity().findViewById<View>(R.id.navBarContainer)?.let { navBar ->
            previousNavBarVisibility = navBar.visibility
            navBar.visibility = View.GONE
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.multiviewRoot) { root, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout() or
                    androidx.core.view.WindowInsetsCompat.Type.ime(),
            )
            root.updatePadding(top = bars.top, bottom = bars.bottom, left = 0, right = 0)
            insets
        }

        binding.backButton.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        binding.addStreamButton.setOnClickListener { showAddStreamSheet() }
        binding.chatButton.setOnClickListener { toggleChat() }
        binding.combinedChatButton.setOnClickListener { toggleCombinedChat() }
        binding.layoutButton.setOnClickListener { showLayoutMenu() }
        binding.moreButton.setOnClickListener { showMoreMenu(binding.moreButton) }

        childFragmentManager.setFragmentResultListener(
            AddMultiviewStreamsSheet.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, result ->
            unlockControls()
            result.parcelableArrayList<Stream>(AddMultiviewStreamsSheet.RESULT_STREAMS)?.let(viewModel::addStreams)
        }
        childFragmentManager.setFragmentResultListener(
            AddMultiviewStreamsSheet.DISMISSED_KEY,
            viewLifecycleOwner,
        ) { _, _ -> unlockControls() }

        val initialStream = requireArguments().parcelable<Stream>(ARG_STREAM)
        viewModel.initialize(initialStream)
        updateOrientationLayout()
        revealControls()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collectLatest { state ->
                        latestState = state
                        render()
                    }
                }
                launch {
                    viewModel.playback.collectLatest { playback ->
                        latestPlayback = playback
                        render()
                    }
                }
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (latestState.focusedIdentity != null) {
                        viewModel.setFocus(null)
                        revealControls()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    override fun onStart() {
        super.onStart()
        viewModel.onStart()
    }

    override fun onStop() {
        viewModel.onStop()
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (_binding != null) {
            updateOrientationLayout()
            render()
        }
    }

    override fun onDestroyView() {
        controlsHandler.removeCallbacks(hideControls)
        controlsLockCount = 0
        slotViews.forEach { (identity, slotView) ->
            viewModel.playbackCoordinator.detach(identity, slotView.playerView)
            (slotView.parent as? ViewGroup)?.removeView(slotView)
        }
        slotViews.clear()
        childFragmentManager.fragments
            .filter { it.id == R.id.chatContent }
            .forEach(::releaseChatFragment)
        renderedChatKey = null
        renderedLayoutKey = null
        requireActivity().findViewById<View>(R.id.navBarContainer)?.visibility = previousNavBarVisibility
        _binding = null
        super.onDestroyView()
    }

    private fun render() {
        val binding = _binding ?: return
        val state = latestState
        val desired = state.streams.mapNotNull { stream ->
            MultiviewSessionReducer.stableIdentity(stream)?.let { it to stream }
        }.toMap()

        slotViews.keys.toList().filterNot(desired::containsKey).forEach { identity ->
            slotViews.remove(identity)?.let { slotView ->
                viewModel.playbackCoordinator.detach(identity, slotView.playerView)
                (slotView.parent as? ViewGroup)?.removeView(slotView)
            }
        }
        desired.forEach { (identity, stream) ->
            if (identity !in slotViews) {
                slotViews[identity] = createSlotView(identity)
            }
            slotViews.getValue(identity).bind(
                identity = identity,
                stream = stream,
                snapshot = latestPlayback[identity],
                audioActive = identity.equals(state.activeIdentity, true),
                focused = identity.equals(state.focusedIdentity, true),
                fillVideo = state.fillVideo,
            )
        }

        viewModel.playbackCoordinator.sync(
            streams = state.streams,
            activeIdentity = state.activeIdentity,
            focusedIdentity = state.focusedIdentity,
            qualityMode = state.qualityMode,
            qualityOverrides = state.qualityOverrides,
        )
        applyLayout(state)
        updateOrientationLayout()
        updateToolbar(state)
        updateChat(state)
        binding.multiviewContent.doOnLayout { renderTileBounds() }
    }

    private fun createSlotView(identity: String): MultiviewSlotView {
        return MultiviewSlotView(requireContext()).apply {
            onTap = {
                viewModel.setActive(identity)
                if (latestState.chatVisible && !latestState.combinedChat) {
                    viewModel.setChat(true, combined = false, identity = identity)
                }
                revealControls(this)
            }
            onDoubleTap = {
                viewModel.setFocus(if (latestState.focusedIdentity.equals(identity, true)) null else identity)
                revealControls(this)
            }
            onLongPress = {
                revealControls(this)
                showSlotMenu(this)
            }
            onOverflow = {
                revealControls(this)
                showSlotMenu(this)
            }
            onRetry = { viewModel.playbackCoordinator.retry(identity) }
        }
    }

    private fun applyLayout(state: MultiviewSessionState) {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val plan = MultiviewLayoutManager.plan(
            identities = state.identities,
            activeIdentity = state.activeIdentity,
            mode = state.layoutMode,
            focusedIdentity = state.focusedIdentity,
            landscape = landscape,
        )
        val layoutKey = "${plan.rowCount}:${plan.columnCount}:" + plan.placements.joinToString("|") {
            "${it.identity}:${it.row},${it.column},${it.rowSpan},${it.columnSpan}"
        }
        if (layoutKey == renderedLayoutKey && binding.videoGrid.childCount == plan.placements.size) {
            renderTileBounds()
            return
        }
        renderedLayoutKey = layoutKey
        binding.videoGrid.removeAllViews()
        binding.videoGrid.rowCount = plan.rowCount.coerceAtLeast(1)
        binding.videoGrid.columnCount = plan.columnCount.coerceAtLeast(1)
        plan.placements.forEach { placement ->
            val slotView = slotViews[placement.identity] ?: return@forEach
            val params = GridLayout.LayoutParams(
                // Weight the full span, not just its first cell. This keeps
                // asymmetric and focused tiles edge-to-edge when they span
                // more than one row or column.
                GridLayout.spec(placement.row, placement.rowSpan, placement.rowSpan.toFloat()),
                GridLayout.spec(placement.column, placement.columnSpan, placement.columnSpan.toFloat()),
            ).apply {
                width = 0
                height = 0
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            binding.videoGrid.addView(slotView, params)
            slotView.doOnLayout {
                viewModel.playbackCoordinator.attach(placement.identity, slotView.playerView)
                viewModel.playbackCoordinator.updateTileBounds(placement.identity, slotView.width, slotView.height)
            }
        }
    }

    private fun renderTileBounds() {
        slotViews.forEach { (identity, view) ->
            if (view.width > 0 && view.height > 0) {
                viewModel.playbackCoordinator.updateTileBounds(identity, view.width, view.height)
            }
        }
    }

    private fun updateToolbar(state: MultiviewSessionState) {
        val active = state.streams.firstOrNull { stream ->
            MultiviewSessionReducer.stableIdentity(stream).equals(state.activeIdentity, true)
        }
        binding.activeAudio.isVisible = active != null
        binding.activeAudio.text = active?.let { getString(R.string.multiview_audio, displayName(it)) }
        binding.addStreamButton.isVisible = state.streams.size < MAX_STREAMS
        binding.chatButton.isVisible = !requireContext().prefs().getBoolean(C.CHAT_DISABLE, false) && active != null
        binding.chatContainer.isVisible = state.chatVisible
        binding.combinedChatButton.isVisible = state.chatVisible && state.streams.size > 1
        binding.chatTitle.text = if (state.combinedChat) {
            getString(R.string.multiview_all_chats)
        } else {
            state.chatIdentity?.let { identity ->
                state.streams.firstOrNull { MultiviewSessionReducer.stableIdentity(it).equals(identity, true) }
            }?.let(::displayName) ?: getString(R.string.multiview_chat)
        }
        binding.chatButton.contentDescription = getString(
            if (state.chatVisible) R.string.multiview_hide_chat else R.string.multiview_chat,
        )
        binding.combinedChatButton.contentDescription = getString(
            if (state.combinedChat) R.string.multiview_channel_chat else R.string.multiview_all_chats,
        )
    }

    private fun updateChat(state: MultiviewSessionState) {
        if (!state.chatVisible) {
            if (renderedChatKey == null && childFragmentManager.fragments.none { it.id == R.id.chatContent }) {
                return
            }
            renderedChatKey = null
            childFragmentManager.beginTransaction().apply {
                childFragmentManager.fragments
                    .filter { it.id == R.id.chatContent }
                    .forEach { fragment ->
                        releaseChatFragment(fragment)
                        remove(fragment)
                    }
            }.commit()
            return
        }
        val singleStream = if (!state.combinedChat) {
            state.streams.firstOrNull {
                MultiviewSessionReducer.stableIdentity(it).equals(state.chatIdentity, true)
            } ?: run {
                renderedChatKey = null
                return
            }
        } else {
            null
        }
        val key = if (state.combinedChat) {
            "all:${state.identities.joinToString(",")}"
        } else {
            "single:${state.chatIdentity}"
        }
        if (key == renderedChatKey) return
        renderedChatKey = key
        val transaction = childFragmentManager.beginTransaction()
        val targetTag = if (state.combinedChat) {
            COMBINED_CHAT_TAG
        } else {
            "$SINGLE_CHAT_TAG${state.chatIdentity}"
        }
        val existingTarget = childFragmentManager.findFragmentByTag(targetTag)
        childFragmentManager.fragments
            .filter { it.id == R.id.chatContent }
            .filterNot { it === existingTarget }
            .forEach { fragment ->
                releaseChatFragment(fragment)
                transaction.remove(fragment)
            }
        if (state.combinedChat) {
            val tag = COMBINED_CHAT_TAG
            val fragment = childFragmentManager.findFragmentByTag(tag)
            if (fragment is CombinedChatFragment) fragment.updateStreams(state.streams)
            val target = fragment ?: CombinedChatFragment.newInstance(state.streams).also {
                transaction.add(R.id.chatContent, it, tag)
            }
            transaction.show(target)
        } else {
            val stream = singleStream ?: return
            val tag = targetTag
            val fragment = existingTarget
                ?: ChatFragment.newInstance(stream.channelId, stream.channelLogin, displayName(stream), stream.id).also {
                    transaction.add(R.id.chatContent, it, tag)
                }
            transaction.show(fragment)
        }
        transaction.commit()
    }

    private fun releaseChatFragment(fragment: Fragment) {
        if (fragment is ChatFragment) {
            // Hiding a Fragment does not call onStop. Explicitly disconnect
            // before removing it so switching A -> B -> combined cannot leave
            // hidden IRC sessions running beside the combined sessions.
            fragment.disconnect()
        }
    }

    private fun toggleChat() {
        if (latestState.chatVisible) {
            viewModel.setChat(false)
        } else {
            val identity = latestState.activeIdentity ?: latestState.identities.firstOrNull() ?: return
            viewModel.setChat(true, combined = false, identity = identity)
        }
        revealControls()
    }

    private fun toggleCombinedChat() {
        if (latestState.combinedChat) {
            val identity = latestState.activeIdentity ?: latestState.identities.firstOrNull() ?: return
            viewModel.setChat(true, combined = false, identity = identity)
        } else if (latestState.streams.size > 1) {
            viewModel.setChat(true, combined = true, identity = null)
        }
    }

    private fun showAddStreamSheet() {
        val freeSlots = MAX_STREAMS - latestState.streams.size
        if (freeSlots <= 0) {
            Toast.makeText(requireContext(), R.string.multiview_max_streams, Toast.LENGTH_SHORT).show()
            return
        }
        revealControls()
        lockControls()
        AddMultiviewStreamsSheet.newInstance(latestState.identities, freeSlots)
            .show(childFragmentManager, AddMultiviewStreamsSheet.TAG)
    }

    private fun showLayoutMenu() {
        revealControls()
        lockControls()
        val modes = MultiviewLayoutMode.entries
        val labels = modes.map { mode -> getString(mode.labelRes()) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.multiview_layout)
            .setSingleChoiceItems(labels, modes.indexOf(latestState.layoutMode)) { dialog, which ->
                val selected = modes[which]
                if (selected == MultiviewLayoutMode.FOCUS && latestState.focusedIdentity == null) {
                    viewModel.setFocus(latestState.activeIdentity ?: latestState.identities.firstOrNull())
                }
                viewModel.setLayoutMode(selected)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { unlockControls() }
            .show()
    }

    private fun showMoreMenu(anchor: View) {
        revealControls()
        lockControls()
        PopupMenu(requireContext(), anchor, Gravity.END).apply {
            menu.add(R.string.multiview_quality_mode).setOnMenuItemClickListener {
                showQualityModeMenu()
                true
            }
            menu.add(if (latestState.fillVideo) R.string.multiview_fit else R.string.multiview_fill).setOnMenuItemClickListener {
                showAspectMenu()
                true
            }
            menu.add(R.string.multiview_reorder).setOnMenuItemClickListener {
                showReorderMenu()
                true
            }
            menu.add(R.string.multiview_open_active_player).setOnMenuItemClickListener {
                latestState.activeIdentity?.let(::openNormalPlayer)
                true
            }
            setOnDismissListener { unlockControls() }
            show()
        }
    }

    private fun showQualityModeMenu() {
        lockControls()
        val modes = MultiviewQualityMode.entries
        val labels = modes.map { getString(it.labelRes()) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.multiview_quality_mode)
            .setSingleChoiceItems(labels, modes.indexOf(latestState.qualityMode)) { dialog, which ->
                viewModel.setQualityMode(modes[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { unlockControls() }
            .show()
    }

    private fun showAspectMenu() {
        lockControls()
        val options = arrayOf(getString(R.string.multiview_fit), getString(R.string.multiview_fill))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.multiview_aspect)
            .setSingleChoiceItems(options, if (latestState.fillVideo) 1 else 0) { dialog, which ->
                viewModel.setFillVideo(which == 1)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { unlockControls() }
            .show()
    }

    private fun showReorderMenu() {
        lockControls()
        val streams = latestState.streams
        val labels = streams.map(::displayName).toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.multiview_reorder)
            .setItems(labels) { _, which -> showMoveMenu(streams[which], which) }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { unlockControls() }
            .show()
    }

    private fun showMoveMenu(stream: Stream, index: Int) {
        val identity = MultiviewSessionReducer.stableIdentity(stream) ?: return
        val actions = buildList {
            if (index > 0) add(getString(R.string.multiview_move_earlier))
            if (index < latestState.streams.lastIndex) add(getString(R.string.multiview_move_later))
        }.toTypedArray()
        if (actions.isEmpty()) return
        lockControls()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(displayName(stream))
            .setItems(actions) { _, which ->
                val target = if (index > 0 && which == 0) index - 1 else index + 1
                viewModel.reorder(identity, target)
            }
            .setOnDismissListener { unlockControls() }
            .show()
    }

    private fun showSlotMenu(slot: MultiviewSlotView) {
        val identity = slot.identity
        val stream = slot.stream ?: return
        lockControls()
        PopupMenu(requireContext(), slot.actionsAnchor, Gravity.END).apply {
            menu.add(R.string.multiview_quality).setOnMenuItemClickListener {
                showSlotQualityMenu(identity, slot)
                true
            }
            menu.add(
                if (latestState.focusedIdentity.equals(identity, true)) R.string.multiview_unfocus
                else R.string.multiview_focus,
            ).setOnMenuItemClickListener {
                viewModel.setFocus(if (latestState.focusedIdentity.equals(identity, true)) null else identity)
                true
            }
            menu.add(R.string.multiview_open_player).setOnMenuItemClickListener {
                openNormalPlayer(identity)
                true
            }
            val index = latestState.identities.indexOfFirst { it.equals(identity, true) }
            if (index > 0) {
                menu.add(R.string.multiview_move_earlier).setOnMenuItemClickListener {
                    viewModel.reorder(identity, index - 1)
                    true
                }
            }
            if (index in 0 until latestState.streams.lastIndex) {
                menu.add(R.string.multiview_move_later).setOnMenuItemClickListener {
                    viewModel.reorder(identity, index + 1)
                    true
                }
            }
            if (latestState.streams.size > 1) {
                menu.add(R.string.multiview_remove_stream).setOnMenuItemClickListener {
                    viewModel.remove(identity)
                    true
                }
            }
            setOnDismissListener { unlockControls(slot) }
            show()
        }
    }

    private fun showSlotQualityMenu(identity: String, slot: MultiviewSlotView) {
        val stream = slot.stream ?: return
        val autoLabel = getString(R.string.multiview_quality_auto)
        val qualities = listOf(autoLabel) + latestPlayback[identity]
            ?.availableQualities.orEmpty()
        lockControls()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.multiview_quality_for, displayName(stream)))
            .setItems(qualities.toTypedArray()) { _, which ->
                viewModel.setQualityOverride(identity, qualities[which].takeUnless { it == autoLabel })
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { unlockControls(slot) }
            .show()
    }

    private fun openNormalPlayer(identity: String) {
        val stream = latestState.streams.firstOrNull {
            MultiviewSessionReducer.stableIdentity(it).equals(identity, true)
        } ?: return
        (activity as? MainActivity)?.let { mainActivity ->
            pauseForExternalPlayer()
            mainActivity.startStream(stream)
        }
    }

    private fun revealControls(slot: MultiviewSlotView? = null) {
        val binding = _binding ?: return
        setControlsOverlayVisible(true)
        slotViews.values.forEach { it.setControlsVisible(it === slot) }
        controlsHandler.removeCallbacks(hideControls)
        if (controlsLockCount == 0) {
            controlsHandler.postDelayed(hideControls, CONTROLS_TIMEOUT_MS)
        }
    }

    private fun setControlsOverlayVisible(visible: Boolean) {
        val binding = _binding ?: return
        binding.controlsOverlay.isVisible = visible
        if (!visible) {
            binding.videoGrid.updatePadding(top = 0)
            binding.multiviewContent.doOnLayout { renderTileBounds() }
            return
        }

        // The toolbar is an overlay, while each tile also owns a top info bar.
        // Reserve the toolbar's measured height so those two rows cannot cover
        // each other when controls are revealed.
        binding.controlsOverlay.doOnLayout { controls ->
            if (_binding == null || !controls.isVisible) return@doOnLayout
            binding.videoGrid.updatePadding(top = controls.height + dp(16))
            binding.multiviewContent.doOnLayout { renderTileBounds() }
        }
    }

    private fun lockControls() {
        controlsLockCount++
        revealControls()
    }

    private fun unlockControls(slot: MultiviewSlotView? = null) {
        controlsLockCount = (controlsLockCount - 1).coerceAtLeast(0)
        if (controlsLockCount == 0) revealControls(slot)
    }

    private fun updateOrientationLayout() {
        val binding = _binding ?: return
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.multiviewRoot.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        (binding.multiviewContent.layoutParams as? LinearLayout.LayoutParams)?.apply {
            if (landscape) {
                width = 0
                height = ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = 0
            }
            weight = 1f
        }?.also { binding.multiviewContent.layoutParams = it }
        (binding.chatContainer.layoutParams as? LinearLayout.LayoutParams)?.apply {
            if (landscape) {
                width = 0
                height = ViewGroup.LayoutParams.MATCH_PARENT
                weight = if (latestState.combinedChat) LANDSCAPE_COMBINED_CHAT_WEIGHT else LANDSCAPE_CHAT_WEIGHT
            } else {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = 0
                weight = PORTRAIT_CHAT_WEIGHT
            }
        }?.also { binding.chatContainer.layoutParams = it }
    }

    private fun displayName(stream: Stream): String {
        return stream.channelName?.takeIf { it.isNotBlank() }
            ?: stream.channelLogin?.takeIf { it.isNotBlank() }
            ?: stream.id.orEmpty()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    suspend fun resolveManualStream(login: String): Stream? = viewModel.resolveLiveStream(login)

    fun pauseForExternalPlayer() {
        if (_binding != null) viewModel.onStop()
    }

    fun resumeAfterExternalPlayer() {
        if (_binding != null) viewModel.onStart()
    }

    companion object {
        const val ARG_STREAM = "multiview_stream"
        private const val COMBINED_CHAT_TAG = "multiview_combined_chat"
        private const val SINGLE_CHAT_TAG = "multiview_single_chat_"
        private const val MAX_STREAMS = 4
        private const val CONTROLS_TIMEOUT_MS = 4_500L
        private const val LANDSCAPE_CHAT_WEIGHT = 0.7f
        private const val LANDSCAPE_COMBINED_CHAT_WEIGHT = 1.2f
        private const val PORTRAIT_CHAT_WEIGHT = 0.42f

        fun arguments(stream: Stream): Bundle = Bundle().apply { putParcelable(ARG_STREAM, stream) }
    }
}

private fun MultiviewLayoutMode.labelRes(): Int = when (this) {
    MultiviewLayoutMode.AUTO -> R.string.multiview_layout_auto
    MultiviewLayoutMode.GRID -> R.string.multiview_layout_grid
    MultiviewLayoutMode.FOCUS -> R.string.multiview_layout_focus
}

private fun MultiviewQualityMode.labelRes(): Int = when (this) {
    MultiviewQualityMode.AUTO -> R.string.multiview_quality_auto
    MultiviewQualityMode.QUALITY_360P -> R.string.multiview_quality_360p
    MultiviewQualityMode.QUALITY_480P -> R.string.multiview_quality_480p
    MultiviewQualityMode.QUALITY_720P -> R.string.multiview_quality_720p
    MultiviewQualityMode.QUALITY_1080P -> R.string.multiview_quality_1080p
}

private inline fun <reified T : Parcelable> Bundle.parcelable(key: String): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelable(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelable(key)
    }
}

private inline fun <reified T : Parcelable> Bundle.parcelableArrayList(key: String): ArrayList<T>? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayList(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayList(key)
    }
}
