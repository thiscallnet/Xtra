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
import com.github.andreyasadchy.xtra.ui.saved.clips.ClipsViewModel.Companion.ClipsViewModelFactory
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class ClipsFragment : Fragment() {

    private var _binding: FragmentClipsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ClipsViewModel by viewModels { ClipsViewModelFactory }
    private lateinit var adapter: ClipsAdapter
    private var player: ExoPlayer? = null
    private var selectedClip: LocalClip? = null
    private var currentClips: List<LocalClip> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentClipsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ClipsAdapter(
            context = requireContext(),
            onSelect = ::selectClip,
            onShare = ::shareClip,
            onDelete = ::confirmDelete,
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ClipsFragment.adapter
        }
        binding.autoplay.isChecked = viewModel.autoplayEnabled()
        binding.autoplay.setOnCheckedChangeListener { _, enabled ->
            viewModel.setAutoplayEnabled(enabled)
            val currentPlayer = player ?: return@setOnCheckedChangeListener
            if (enabled) {
                currentPlayer.volume = 0f
                currentPlayer.repeatMode = Player.REPEAT_MODE_ONE
                currentPlayer.play()
            } else {
                currentPlayer.pause()
                currentPlayer.volume = 1f
            }
        }
        binding.sortButton.setOnClickListener { showSortDialog() }
        binding.deleteAllButton.setOnClickListener { confirmDeleteAll() }
        binding.retryButton.setOnClickListener { viewModel.refresh() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch { viewModel.clips.collect(::renderClips) }
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
            player?.play()
        }
        viewModel.refresh()
    }

    private fun renderClips(clips: List<LocalClip>) {
        currentClips = clips
        adapter.submitList(clips)
        binding.clipCount.text = resources.getQuantityString(R.plurals.clips_count, clips.size, clips.size)
        binding.previewCard.isVisible = clips.isNotEmpty()
        binding.deleteAllButton.isVisible = clips.isNotEmpty()
        updateContentVisibility()

        val selected = selectedClip?.let { current -> clips.firstOrNull { it.uri == current.uri } }
        if (selected == null) {
            selectedClip = clips.firstOrNull()
            selectedClip?.let {
                val autoplay = viewModel.autoplayEnabled()
                loadPreview(it, muted = autoplay, play = autoplay && isFragmentResumed)
            }
                ?: releasePreview()
        } else if (selected !== selectedClip || player == null) {
            selectedClip = selected
            val autoplay = viewModel.autoplayEnabled()
            loadPreview(selected, muted = autoplay, play = autoplay && isFragmentResumed)
        }
        adapter.setSelected(selectedClip)
    }

    private val isFragmentResumed: Boolean
        get() = viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private fun renderError(hasError: Boolean) {
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
        previewPlayer.playWhenReady = play
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
        } catch (_: Throwable) {
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
        player?.pause()
        super.onPause()
    }

    override fun onDestroyView() {
        player?.release()
        player = null
        _binding = null
        super.onDestroyView()
    }
}
