package com.github.andreyasadchy.xtra.ui.saved.clips

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentClipsBinding
import com.github.andreyasadchy.xtra.ui.common.installVisibleViewportStatePositioning
import com.github.andreyasadchy.xtra.ui.saved.installSavedDeleteFeedback
import com.github.andreyasadchy.xtra.ui.saved.SavedItemSelection
import com.github.andreyasadchy.xtra.ui.saved.SavedSelectablePage
import com.github.andreyasadchy.xtra.ui.saved.SavedSelectionHost
import com.github.andreyasadchy.xtra.ui.saved.clips.ClipsViewModel.Companion.ClipsViewModelFactory
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class ClipsFragment : Fragment(), SavedSelectablePage {

    private var _binding: FragmentClipsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ClipsViewModel by viewModels { ClipsViewModelFactory }
    private lateinit var adapter: ClipsAdapter
    private lateinit var selection: SavedItemSelection
    private var player: ExoPlayer? = null
    private var selectedClip: LocalClip? = null
    private var currentClips: List<LocalClip> = emptyList()
    private var playerOverlayAvoidance: ClipsPlayerOverlayAvoidance? = null
    private var viewportLayout: ClipsViewportLayout? = null
    private var previewHeaderVisible = true
    private var resumePreviewWhenVisible = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentClipsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        selection = SavedItemSelection(savedInstanceState)
        binding.staleRetry.setOnClickListener { viewModel.refresh() }
        installSavedDeleteFeedback(view, viewModel.deleteFailed, viewModel::acknowledgeDeleteFailure)
        val playerContainer = requireActivity().findViewById<View>(R.id.playerContainer)
        binding.contentContainer.installVisibleViewportStatePositioning(
            lifecycleOwner = viewLifecycleOwner,
            stateViews = listOf(binding.errorState, binding.progressBar),
            overlayProvider = { playerContainer?.findViewById(R.id.playerLayout) },
            overlayAvoidanceTarget = binding.errorState,
            overlayGapDp = 8f,
        )
        playerOverlayAvoidance?.close()
        playerOverlayAvoidance = playerContainer?.let {
            ClipsPlayerOverlayAvoidance(
                lifecycleOwner = viewLifecycleOwner,
                root = binding.root,
                controlsHost = binding.clipsControlsHost,
                controlsContent = binding.clipsControlsContent,
                clipsInfo = binding.clipsInfo,
                clipCount = binding.clipCount,
                autoplaySummary = binding.autoplaySummary,
                autoplayCompactLabel = binding.autoplayCompactLabel,
                autoplay = binding.autoplay,
                contentContainer = binding.contentContainer,
                emptyState = binding.emptyState,
                emptyStateContent = binding.emptyStateContent,
                emptyStateTitle = binding.emptyStateTitle,
                playerContainer = it,
            )
        }
        adapter = ClipsAdapter(
            context = requireContext(),
            onSelect = ::selectClip,
            onShare = ::shareClip,
            onDelete = ::confirmDelete,
            selection = selection,
        )
        selection.loadedIds = { currentClips.map { it.uri.toString() } }
        selection.hasLoadedItems = { currentClips.isNotEmpty() }
        selection.onChanged = {
            adapter.notifySelectionChanged(it)
            _binding?.deleteAllButton?.isVisible = currentClips.isNotEmpty() && !selection.active
        }
        selection.onDelete = ::confirmDeleteSelected
        view.post { if (_binding?.root === view && selection.active) startSavedSelection() }
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ClipsFragment.adapter
        }
        viewportLayout = ClipsViewportLayout(
            savedInstanceState, binding.root, binding.contentContainer, binding.recyclerView, adapter,
            listOf(binding.previewCard, binding.clipsControlsHost, binding.sortButton.parent as View),
        ) { visible ->
            if (previewHeaderVisible != visible) {
                previewHeaderVisible = visible
                if (!visible) {
                    resumePreviewWhenVisible = player?.playWhenReady == true
                    player?.pause()
                } else if (resumePreviewWhenVisible && isFragmentResumed) {
                    player?.play()
                    resumePreviewWhenVisible = false
                }
            }
        }
        binding.autoplay.isChecked = viewModel.autoplayEnabled()
        binding.autoplay.setOnCheckedChangeListener { _, enabled ->
            viewModel.setAutoplayEnabled(enabled)
            val currentPlayer = player ?: return@setOnCheckedChangeListener
            if (enabled) {
                currentPlayer.volume = 0f
                currentPlayer.repeatMode = Player.REPEAT_MODE_ONE
                if (previewHeaderVisible) currentPlayer.play()
                else resumePreviewWhenVisible = true
            } else {
                resumePreviewWhenVisible = false
                currentPlayer.pause()
                currentPlayer.volume = 1f
            }
        }
        binding.sortButton.setOnClickListener { showSortDialog() }
        binding.deleteAllButton.setOnClickListener { confirmDeleteAll() }
        binding.retryButton.setOnClickListener { viewModel.refresh() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch { viewModel.clips.collect { clips -> clips?.let(::renderClips) } }
                launch {
                    viewModel.isLoading.collect {
                        binding.progressBar.isVisible = it
                        updateContentVisibility()
                    }
                }
                launch { viewModel.hasError.collect { renderError(it) } }
                launch { viewModel.sortMode.collect { updateSortButton(it) } }
            }
        }
        viewModel.refresh()
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.autoplayEnabled()) {
            player?.volume = 0f
            player?.repeatMode = Player.REPEAT_MODE_ONE
            if (previewHeaderVisible) player?.play()
            else resumePreviewWhenVisible = true
        }
        viewModel.refresh()
    }

    private fun renderClips(clips: List<LocalClip>) {
        selection.retain(clips.mapTo(HashSet()) { it.uri.toString() })
        currentClips = clips
        adapter.submitList(clips)
        binding.clipCount.text = resources.getQuantityString(R.plurals.clips_count, clips.size, clips.size)
        binding.previewCard.isVisible = clips.isNotEmpty()
        binding.sortButton.isVisible = clips.isNotEmpty()
        binding.deleteAllButton.isVisible = clips.isNotEmpty() && !selection.active
        updateContentVisibility()
        binding.staleStatus.isVisible = viewModel.hasError.value && clips.isNotEmpty()

        val selected = selectedClip?.let { current -> clips.firstOrNull { it.uri == current.uri } }
        if (selected == null) {
            selectedClip = clips.firstOrNull()
            selectedClip?.let {
                val autoplay = viewModel.autoplayEnabled()
                loadPreview(it, muted = autoplay, play = autoplay && isFragmentResumed)
            }
                ?: releasePreview()
        } else if (selected != selectedClip || player == null) {
            selectedClip = selected
            val autoplay = viewModel.autoplayEnabled()
            loadPreview(selected, muted = autoplay, play = autoplay && isFragmentResumed)
        }
        adapter.setSelected(selectedClip)
        viewportLayout?.setHasItems(clips.isNotEmpty())
    }

    private val isFragmentResumed: Boolean
        get() = viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private fun renderError(hasError: Boolean) {
        binding.staleStatus.isVisible = hasError && currentClips.isNotEmpty()
        binding.errorState.isVisible = hasError && currentClips.isEmpty()
        updateContentVisibility()
    }

    private fun updateContentVisibility() {
        binding.emptyState.isVisible = currentClips.isEmpty() &&
                !binding.progressBar.isVisible &&
                !binding.errorState.isVisible
    }

    private fun updateSortButton(mode: String) {
        val label = when (mode) {
            ClipsViewModel.SORT_OLDEST -> R.string.clips_sort_oldest
            ClipsViewModel.SORT_NAME -> R.string.clips_sort_name
            else -> R.string.clips_sort_newest
        }
        binding.sortButton.text = getString(R.string.clips_sort_button, getString(label))
    }

    private fun selectClip(clip: LocalClip) {
        selectedClip = clip
        adapter.setSelected(clip)
        loadPreview(clip, muted = false, play = true)
        viewportLayout?.showPreview()
    }

    private fun loadPreview(clip: LocalClip, muted: Boolean, play: Boolean) {
        val previewPlayer = player ?: ExoPlayer.Builder(requireContext()).build().also { created ->
            created.repeatMode = Player.REPEAT_MODE_ONE
            player = created
            binding.preview.player = created
        }
        previewPlayer.stop()
        previewPlayer.setMediaItem(MediaItem.fromUri(clip.uri))
        previewPlayer.volume = if (muted) 0f else 1f
        previewPlayer.prepare()
        resumePreviewWhenVisible = play && !previewHeaderVisible
        previewPlayer.playWhenReady = play && previewHeaderVisible
        binding.previewMeta.text = formatClipMetadata(clip)
        binding.previewEmpty.isVisible = false
        binding.previewHeading.text = clip.displayName
    }

    private fun releasePreview() {
        player?.release()
        player = null
        binding.preview.player = null
        binding.previewMeta.text = null
        binding.previewHeading.text = getString(R.string.clips_preview_title)
        binding.previewEmpty.isVisible = true
    }

    private fun formatClipMetadata(clip: LocalClip): String = getString(
        R.string.clips_date_and_size,
        android.text.format.DateUtils.formatElapsedTime(clip.durationMs / 1_000L),
        android.text.format.Formatter.formatShortFileSize(requireContext(), clip.sizeBytes),
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            .format(java.util.Date(clip.modifiedAtMs)),
    )

    private fun shareClip(clip: LocalClip) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, clip.uri)
            putExtra(Intent.EXTRA_TITLE, clip.displayName)
            clipData = ClipData.newUri(requireContext().contentResolver, clip.displayName, clip.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Toast.makeText(requireContext(), R.string.clips_share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(clip: LocalClip) {
        requireActivity().getAlertDialogBuilder()
            .setTitle(R.string.clips_delete_title)
            .setMessage(getString(R.string.clips_delete_message, clip.displayName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (selectedClip?.uri == clip.uri) {
                    selectedClip = null
                    releasePreview()
                }
                viewModel.delete(clip)
            }
            .show()
    }

    override fun startSavedSelection() {
        selection.start((parentFragment as? SavedSelectionHost)?.savedSelectionChrome)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        viewportLayout?.save(outState)
        if (this::selection.isInitialized) selection.save(outState)
        super.onSaveInstanceState(outState)
    }

    private fun confirmDeleteSelected(ids: Set<String>) {
        requireActivity().getAlertDialogBuilder()
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.saved_delete_clips_message, ids.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (selectedClip?.uri?.toString() in ids) {
                    selectedClip = null
                    releasePreview()
                }
                viewModel.deleteSelected(ids)
                selection.finish()
            }.show()
    }

    private fun confirmDeleteAll() {
        if (currentClips.isEmpty()) return
        requireActivity().getAlertDialogBuilder()
            .setTitle(R.string.clips_delete_all_title)
            .setMessage(getString(R.string.clips_delete_all_message, currentClips.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.clips_delete_all) { _, _ ->
                selectedClip = null
                releasePreview()
                viewModel.deleteAll()
            }
            .show()
    }

    private fun showSortDialog() {
        val modes = listOf(
            ClipsViewModel.SORT_NEWEST to R.string.clips_sort_newest,
            ClipsViewModel.SORT_OLDEST to R.string.clips_sort_oldest,
            ClipsViewModel.SORT_NAME to R.string.clips_sort_name,
        )
        val checked = modes.indexOfFirst { it.first == viewModel.sortMode.value }
        requireActivity().getAlertDialogBuilder()
            .setTitle(R.string.clips_sort)
            .setSingleChoiceItems(modes.map { getString(it.second) }.toTypedArray(), checked) { dialog, which ->
                viewModel.setSortMode(modes[which].first)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onPause() {
        resumePreviewWhenVisible = false
        player?.pause()
        super.onPause()
    }

    override fun onDestroyView() {
        viewportLayout?.close()
        viewportLayout = null
        playerOverlayAvoidance?.close()
        playerOverlayAvoidance = null
        player?.release()
        player = null
        _binding = null
        super.onDestroyView()
    }
}
