package com.github.andreyasadchy.xtra.ui.player.clip

import android.Manifest
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.util.Log
import android.provider.MediaStore
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.TimeBar
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.databinding.FragmentClipEditorBinding
import com.google.android.material.slider.RangeSlider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full-screen segment-aligned editor for a prepared local live snapshot. */
@OptIn(UnstableApi::class)
class ClipEditorDialogFragment : Fragment() {
    private var _binding: FragmentClipEditorBinding? = null
    private val binding get() = _binding!!

    private lateinit var playlistFile: File
    private lateinit var directory: File
    private lateinit var boundariesUs: LongArray
    private var durationUs = 0L
    private var channelName: String? = null
    private var player: ExoPlayer? = null
    private var previewHasRenderedFrame = false
    private var previewPlayWhenReadyAfterViewRecreation = false
    private var previewPlayWhenReadyBeforeStop = false
    private var previewStoppedForLifecycle = false
    private var exportJob: Job? = null
    private var savedUri: Uri? = null
    private var savedDisplayName: String? = null
    private var shareFile: File? = null
    private var defaultFileBaseName = ""
    private var restoredFileBaseName: String? = null
    private var updatingSlider = false
    private var previewStartUs = 0L
    private var previewEndUs = 0L
    private var lastPreviewSeekStartUs = 0L
    private var previewWasPlayingBeforeScrub = false
    private var previewWasPlayingBeforeRangeDrag = false
    private var previewScrubPositionMs: Long? = null
    private var startUs = 0L
    private var endUs = 0L
    private var storagePermissionRequestPending = false
    private var resultSent = false
    private var previewHovered = false
    private val previewHandler = Handler(Looper.getMainLooper())

    private data class Selection(
        val startUs: Long,
        val endUs: Long,
    )
    private val hidePreviewControls = Runnable {
        if (_binding != null && !previewHovered && player?.isPlaying == true) {
            binding.previewControls.visibility = View.INVISIBLE
        }
    }
    private val previewLoop = object : Runnable {
        override fun run() {
            val previewPlayer = player
            if (previewPlayer != null) {
                if (previewPlayer.isPlaying && previewPlayer.currentPosition >= previewEndUs / 1_000L) {
                    previewPlayer.pause()
                    showPreviewControls()
                }
                updatePreviewControls()
            }
            if (_binding != null) previewHandler.postDelayed(this, PREVIEW_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playlistFile = File(requireArguments().getString(ARG_PLAYLIST)!!)
        directory = File(requireArguments().getString(ARG_DIRECTORY)!!)
        boundariesUs = ClipTimeline.normalizeBoundaries(
            requireArguments().getLongArray(ARG_BOUNDARIES) ?: longArrayOf(0L),
        )
        durationUs = boundariesUs.last()
        channelName = requireArguments().getString(ARG_CHANNEL_NAME)
        defaultFileBaseName = "${sanitize(channelName)}_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}"
        startUs = savedInstanceState
            ?.takeIf { it.containsKey(STATE_START_US) }
            ?.getLong(STATE_START_US)
            ?: 0L
        endUs = savedInstanceState
            ?.takeIf { it.containsKey(STATE_END_US) }
            ?.getLong(STATE_END_US)
            ?: durationUs
        restoredFileBaseName = savedInstanceState?.getString(STATE_FILE_NAME)
        savedDisplayName = savedInstanceState?.getString(STATE_SAVED_DISPLAY_NAME)
        shareFile = savedInstanceState?.getString(STATE_SHARE_FILE)?.let(::File)
        savedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            savedInstanceState?.getParcelable(STATE_SAVED_URI, Uri::class.java)
        } else {
            readSavedUri(savedInstanceState)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentClipEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.channel.text = channelName.orEmpty()
        binding.fileNameLayout.hint = defaultFileBaseName
        binding.fileName.setText(restoredFileBaseName.orEmpty())
        binding.close.setOnClickListener { closeEditor() }
        binding.done.setOnClickListener { closeEditor() }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = closeEditor()
            },
        )
        binding.share.setOnClickListener { shareSavedClip() }
        binding.save.setOnClickListener { exportClip() }
        binding.previewRewind.setOnClickListener {
            showPreviewControls()
            seekPreviewBy(-PREVIEW_SEEK_MS)
        }
        binding.previewFastForward.setOnClickListener {
            showPreviewControls()
            seekPreviewBy(PREVIEW_SEEK_MS)
        }
        binding.previewRewind.contentDescription = getString(
            R.string.player_rewind_seconds,
            PREVIEW_SEEK_MS / 1_000L,
        )
        binding.previewFastForward.contentDescription = getString(
            R.string.player_fast_forward_seconds,
            PREVIEW_SEEK_MS / 1_000L,
        )

        val durationMs = durationUs / 1_000f
        val initialSelection = selectionForUs(startUs, endUs)
        startUs = initialSelection.startUs
        endUs = initialSelection.endUs
        previewStartUs = startUs
        previewEndUs = endUs
        lastPreviewSeekStartUs = previewStartUs

        binding.rangeSlider.valueFrom = 0f
        binding.rangeSlider.valueTo = durationMs
        binding.rangeSlider.setValues(startUs / 1_000f, endUs / 1_000f)
        binding.rangeSlider.addOnChangeListener { _, _, _ ->
            // Seeking on every drag event repeatedly flushes the preview decoder.
            if (!updatingSlider) updateSelectionFromSlider()
        }
        binding.rangeSlider.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) {
                showPreviewControls()
                previewWasPlayingBeforeRangeDrag = player?.isPlaying == true
                player?.pause()
            }

            override fun onStopTrackingTouch(slider: RangeSlider) {
                snapSliderValues()
                if (previewWasPlayingBeforeRangeDrag && (player?.currentPosition ?: 0L) < previewEndUs / 1_000L) {
                    player?.play()
                }
                previewWasPlayingBeforeRangeDrag = false
            }
        })
        binding.preview.setOnClickListener {
            togglePreviewControls()
        }
        binding.previewControls.setOnClickListener { togglePreviewControls() }
        val hoverListener = View.OnHoverListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER,
                MotionEvent.ACTION_HOVER_MOVE -> {
                    previewHovered = true
                    showPreviewControls()
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    previewHovered = false
                    schedulePreviewControlsHide()
                }
            }
            false
        }
        binding.previewContainer.setOnHoverListener(hoverListener)
        binding.preview.setOnHoverListener(hoverListener)
        binding.previewControls.setOnHoverListener(hoverListener)
        binding.previewPlayPause.setOnClickListener {
            showPreviewControls()
            togglePreviewPlayback()
        }
        binding.previewProgress.addListener(
            object : TimeBar.OnScrubListener {
                override fun onScrubStart(timeBar: TimeBar, position: Long) {
                    showPreviewControls()
                    previewWasPlayingBeforeScrub = player?.isPlaying == true
                    player?.pause()
                    previewScrubPositionMs = position.coerceIn(0L, previewDurationMs())
                    updatePreviewControls()
                }

                override fun onScrubMove(timeBar: TimeBar, position: Long) {
                    previewScrubPositionMs = position.coerceIn(0L, previewDurationMs())
                    updatePreviewControls()
                }

                override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                    val previewPlayer = player
                    if (!canceled && previewPlayer != null) {
                        val durationMs = previewDurationMs()
                        val targetMs = previewStartUs / 1_000L + position.coerceIn(0L, durationMs)
                        previewPlayer.seekTo(targetMs)
                        lastPreviewSeekStartUs = previewStartUs
                    }
                    if (previewWasPlayingBeforeScrub) previewPlayer?.play()
                    previewWasPlayingBeforeScrub = false
                    previewScrubPositionMs = null
                    updatePreviewControls()
                    showPreviewControls()
                }
            }
        )
        applySelection(initialSelection, seekPreview = false)
        if (savedUri != null) {
            binding.fileName.setText(
                (savedDisplayName ?: restoredFileBaseName ?: defaultFileBaseName).removeMp4Extension(),
            )
            binding.fileNameLayout.isEnabled = false
            binding.save.isVisible = false
            binding.rangeSlider.isEnabled = false
            binding.share.isVisible = true
            binding.done.isVisible = true
            showSavedInfo(
                savedDisplayName ?: "$defaultFileBaseName.mp4",
                CLIP_LOCATION,
            )
        }

        if (player == null) {
            clipDebug("editor PlayerView attach new player")
            initializePreviewPlayer(previewStartUs / 1_000L, playWhenReady = true)
        } else {
            clipDebug("editor PlayerView attach retained player")
            attachExistingPreviewPlayer()
        }
        previewHandler.post(previewLoop)
    }

    override fun onStart() {
        super.onStart()
        if (previewStoppedForLifecycle) {
            val shouldResume = previewPlayWhenReadyBeforeStop
            previewStoppedForLifecycle = false
            previewPlayWhenReadyBeforeStop = false
            val previewPlayer = player
            if (shouldResume && previewPlayer != null && previewPlayer.currentPosition < previewEndUs / 1_000L) {
                previewPlayer.play()
                showPreviewControls()
            }
        }
    }

    override fun onStop() {
        previewPlayWhenReadyBeforeStop = player?.playWhenReady == true
        previewStoppedForLifecycle = player != null
        player?.playWhenReady = false
        player?.pause()
        super.onStop()
    }

    private fun initializePreviewPlayer(startPositionMs: Long, playWhenReady: Boolean) {
        if (_binding == null) return
        previewHasRenderedFrame = false
        binding.previewLoading.isVisible = true
        player = ExoPlayer.Builder(requireContext()).build().also { exoPlayer ->
            binding.preview.player = exoPlayer
            val mediaItem = MediaItem.Builder()
                .setUri(playlistFile.toUri())
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
            val mediaSource = HlsMediaSource.Factory(DefaultDataSource.Factory(requireContext()))
                .createMediaSource(mediaItem)
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        exoPlayer.pause()
                    }
                    if (_binding != null) updatePreviewControls()
                    clipDebug("preview state=$playbackState")
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (_binding != null) {
                        updatePreviewControls()
                        showPreviewControls()
                    }
                }

                override fun onRenderedFirstFrame() {
                    previewHasRenderedFrame = true
                    clipDebug("preview onRenderedFirstFrame")
                    if (_binding != null) {
                        binding.previewLoading.isVisible = false
                        updatePreviewControls()
                    }
                    if (isAdded) {
                        parentFragmentManager.setFragmentResult(PREVIEW_READY_KEY, Bundle.EMPTY)
                    }
                }
            })
            exoPlayer.setMediaSource(mediaSource, startPositionMs)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = playWhenReady
        }
    }

    private fun attachExistingPreviewPlayer() {
        val previewPlayer = player ?: return
        binding.preview.player = previewPlayer
        binding.previewLoading.isVisible = !previewHasRenderedFrame
        val currentPositionMs = previewPlayer.currentPosition
        val positionMs = currentPositionMs.coerceIn(
            previewStartUs / 1_000L,
            previewEndUs / 1_000L,
        )
        if (positionMs != currentPositionMs) {
            previewPlayer.seekTo(positionMs)
        }
        previewPlayer.playWhenReady = previewPlayWhenReadyAfterViewRecreation
        previewPlayWhenReadyAfterViewRecreation = false
    }

    private fun snapSliderValues() {
        val selection = selectionForSlider(binding.rangeSlider.values)
        updatingSlider = true
        binding.rangeSlider.setValues(
            selection.startUs / 1_000f,
            selection.endUs / 1_000f,
        )
        updatingSlider = false
        applySelection(selection, seekPreview = true)
    }

    private fun updateSelectionFromSlider() {
        applySelection(selectionForSlider(binding.rangeSlider.values), seekPreview = false)
    }

    private fun applySelection(selection: Selection, seekPreview: Boolean) {
        val startChangedSinceLastSeek = selection.startUs != lastPreviewSeekStartUs
        startUs = selection.startUs
        endUs = selection.endUs
        previewStartUs = startUs
        previewEndUs = endUs
        binding.selectionDuration.text = getString(
            R.string.clip_editor_selected_duration,
            formatDuration((endUs - startUs) / 1_000L),
        )
        binding.startLabel.text = formatRelativePosition(startUs)
        binding.endLabel.text = formatRelativePosition(endUs)
        if (seekPreview && startChangedSinceLastSeek) {
            val previewPlayer = player
            if (previewPlayer != null) {
                val resumePlayback = previewPlayer.playWhenReady
                previewPlayer.seekTo(previewStartUs / 1_000L)
                lastPreviewSeekStartUs = previewStartUs
                if (resumePlayback) previewPlayer.play()
            }
        }
        if (player?.isPlaying == true && (player?.currentPosition ?: 0L) >= previewEndUs / 1_000L) {
            player?.pause()
        }
        updatePreviewProgressDuration()
    }

    private fun selectionForSlider(values: List<Float>): Selection {
        if (values.size < 2) return Selection(startUs, endUs)
        return selectionForIndices(
            ClipTimeline.boundaryIndex(values[0], boundariesUs),
            ClipTimeline.boundaryIndex(values[1], boundariesUs),
        )
    }

    private fun selectionForUs(startUs: Long, endUs: Long): Selection = selectionForIndices(
        ClipTimeline.boundaryIndexUs(startUs, boundariesUs),
        ClipTimeline.boundaryIndexUs(endUs, boundariesUs),
    )

    private fun selectionForIndices(start: Int, end: Int): Selection {
        var startIndex = start.coerceIn(0, boundariesUs.lastIndex)
        var endIndex = end.coerceIn(0, boundariesUs.lastIndex)
        if (endIndex <= startIndex) {
            if (startIndex < boundariesUs.lastIndex) {
                endIndex = startIndex + 1
            } else {
                startIndex = (endIndex - 1).coerceAtLeast(0)
            }
        }
        return Selection(boundariesUs[startIndex], boundariesUs[endIndex])
    }

    private fun togglePreviewPlayback() {
        val previewPlayer = player ?: return
        if (previewPlayer.isPlaying) {
            previewPlayer.pause()
        } else {
            val startMs = previewStartUs / 1_000L
            val endMs = previewEndUs / 1_000L
            if (previewPlayer.currentPosition < startMs || previewPlayer.currentPosition >= endMs) {
                previewPlayer.seekTo(startMs)
                lastPreviewSeekStartUs = previewStartUs
            }
            previewPlayer.play()
        }
        updatePreviewControls()
        showPreviewControls()
    }

    private fun seekPreviewBy(offsetMs: Long) {
        val previewPlayer = player ?: return
        val targetMs = (previewPlayer.currentPosition + offsetMs)
            .coerceIn(previewStartUs / 1_000L, previewEndUs / 1_000L)
        previewPlayer.seekTo(targetMs)
        updatePreviewControls()
        showPreviewControls()
    }

    private fun showPreviewControls() {
        if (_binding == null) return
        previewHandler.removeCallbacks(hidePreviewControls)
        binding.previewControls.visibility = View.VISIBLE
        clipDebug("controls visible")
        schedulePreviewControlsHide()
    }

    private fun schedulePreviewControlsHide() {
        if (_binding == null) return
        previewHandler.removeCallbacks(hidePreviewControls)
        if (!previewHovered && player?.isPlaying == true) {
            previewHandler.postDelayed(hidePreviewControls, PREVIEW_CONTROLS_HIDE_MS)
        }
    }

    private fun togglePreviewControls() {
        if (_binding == null) return
        if (binding.previewControls.isVisible && player?.isPlaying == true) {
            previewHandler.removeCallbacks(hidePreviewControls)
            binding.previewControls.visibility = View.INVISIBLE
            clipDebug("controls hidden")
        } else {
            showPreviewControls()
        }
    }

    private fun previewDurationMs(): Long =
        ((previewEndUs - previewStartUs) / 1_000L).coerceAtLeast(1L)

    private fun updatePreviewProgressDuration() {
        if (_binding == null) return
        binding.previewProgress.setDuration(previewDurationMs())
        updatePreviewControls()
    }

    private fun updatePreviewControls() {
        if (_binding == null) return
        val previewPlayer = player ?: return
        val durationMs = previewDurationMs()
        val positionMs = (previewScrubPositionMs ?:
            (previewPlayer.currentPosition - previewStartUs / 1_000L))
            .coerceIn(0L, durationMs)
        val bufferedMs = (previewPlayer.bufferedPosition - previewStartUs / 1_000L)
            .coerceIn(0L, durationMs)
        if (!binding.previewProgress.isPressed) binding.previewProgress.setPosition(positionMs)
        binding.previewProgress.setBufferedPosition(bufferedMs)
        binding.previewPosition.text = "${formatDuration(positionMs)} / ${formatDuration(durationMs)}"
        val playing = previewPlayer.isPlaying
        binding.previewPlayPause.setImageResource(
            if (playing) R.drawable.baseline_pause_black_48 else R.drawable.baseline_play_arrow_black_48,
        )
        binding.previewPlayPause.contentDescription = getString(
            if (playing) R.string.player_pause_action else R.string.player_play,
        )
    }

    private fun formatRelativePosition(positionUs: Long): String {
        val remainingUs = (durationUs - positionUs).coerceAtLeast(0L)
        return if (remainingUs <= 500_000L) {
            getString(R.string.clip_editor_now)
        } else {
            getString(R.string.clip_editor_seconds_ago, ((remainingUs + 500_000L) / 1_000_000L).toInt())
        }
    }

    private fun exportClip() {
        if (exportJob?.isActive == true || savedUri != null) return
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionRequestPending = true
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_REQUEST)
            return
        }
        val startIndex = ClipTimeline.boundaryIndexUs(startUs, boundariesUs)
        val endIndex = ClipTimeline.boundaryIndexUs(endUs, boundariesUs)
        if (endIndex <= startIndex) return
        val displayName = requestedDisplayName()
        binding.progress.isVisible = true
        binding.save.isEnabled = false
        binding.rangeSlider.isEnabled = false
        exportJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val selectedPlaylist = ClipSelectionPlaylistWriter.write(
                    prepared = ClipPreparationRepository.PreparedLiveClip.read(directory),
                    output = File(directory, "selected.m3u8"),
                    startIndex = startIndex,
                    endIndexExclusive = endIndex,
                )
                val outputFile = File(directory, "clip.mp4")
                val exported = ClipExporter(requireContext()).export(selectedPlaylist, outputFile)
                val published = publishToMediaStore(exported.file, displayName)
                    ?: throw IllegalStateException("MediaStore insert failed")
                shareFile = withContext(Dispatchers.IO) {
                    val namedFile = File(directory, displayName)
                    if (exported.file != namedFile && !exported.file.renameTo(namedFile)) {
                        exported.file.copyTo(namedFile, overwrite = true)
                        exported.file.delete()
                    }
                    namedFile
                }
                savedUri = published.uri
                savedDisplayName = published.displayName
                restoredFileBaseName = published.displayName.removeMp4Extension()
                binding.fileName.setText(restoredFileBaseName)
                binding.fileNameLayout.isEnabled = false
                showSavedInfo(published.displayName, published.location)
                binding.progress.isVisible = false
                binding.save.isVisible = false
                binding.share.isVisible = true
                binding.done.isVisible = true
                Toast.makeText(
                    requireContext(),
                    "${getString(R.string.clip_saved)}: ${published.displayName}\n${published.location}",
                    Toast.LENGTH_LONG,
                ).show()
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Dismissal or rotation can cancel an in-flight export.
            } catch (_: Throwable) {
                binding.progress.isVisible = false
                binding.save.isEnabled = true
                binding.rangeSlider.isEnabled = true
                Toast.makeText(requireContext(), R.string.clip_export_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun publishToMediaStore(file: File, displayName: String): PublishedClip? = withContext(Dispatchers.IO) {
        val context = requireContext()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val legacyFile = ClipMediaStore.legacyFile(displayName)
            val legacyDirectory = legacyFile.parentFile ?: error("Unable to resolve the clip directory")
            check(legacyDirectory.mkdirs() || legacyDirectory.isDirectory) {
                "Unable to create the clip directory"
            }
            val values = ClipMediaStore.legacyValues(displayName, legacyFile)
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext null
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Unable to open output media")
                PublishedClip(uri, displayName, CLIP_LOCATION)
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                legacyFile.delete()
                throw error
            }
        } else {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.TITLE, displayName.removeMp4Extension())
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Xtra/Clips")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext null
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Unable to open output media")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                PublishedClip(uri, displayName, CLIP_LOCATION)
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
        }
    }

    private fun shareSavedClip() {
        val uri = savedUri ?: return
        val displayName = savedDisplayName ?: requestedDisplayName()
        val shareUri = shareFile?.takeIf(File::isFile)?.let { file ->
            FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file,
            )
        } ?: uri
        startActivity(
            Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                putExtra(Intent.EXTRA_TITLE, displayName)
                clipData = ClipData.newUri(requireContext().contentResolver, displayName, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.let { Intent.createChooser(it, getString(R.string.clip_editor_share)) }
        )
    }

    private fun requestedDisplayName(): String {
        val input = binding.fileName.text?.toString().orEmpty().trim()
        val baseName = (if (input.isBlank()) defaultFileBaseName else input)
            .replace(Regex("(?i)\\.mp4$"), "")
        return "${sanitize(baseName)}.mp4"
    }

    private fun showSavedInfo(displayName: String, location: String) {
        binding.savedInfo.text = "$displayName\n$location"
        binding.savedInfo.isVisible = true
    }

    private fun sanitize(value: String?): String = value.orEmpty()
        .ifBlank { "clip" }
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .take(48)

    private fun String.removeMp4Extension(): String = replace(Regex("(?i)\\.mp4$"), "")

    private fun formatDuration(milliseconds: Long): String = DateUtils.formatElapsedTime(milliseconds / 1000L)

    @Suppress("DEPRECATION")
    private fun readSavedUri(state: Bundle?): Uri? = state?.getParcelable(STATE_SAVED_URI)

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_START_US, startUs)
        outState.putLong(STATE_END_US, endUs)
        outState.putString(STATE_FILE_NAME, binding.fileName.text?.toString())
        outState.putString(STATE_SAVED_DISPLAY_NAME, savedDisplayName)
        outState.putString(STATE_SHARE_FILE, shareFile?.absolutePath)
        outState.putParcelable(STATE_SAVED_URI, savedUri)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        clipDebug("preview release surface retainPlayer=${player != null}")
        previewHovered = false
        previewHandler.removeCallbacks(previewLoop)
        previewHandler.removeCallbacks(hidePreviewControls)
        previewPlayWhenReadyAfterViewRecreation =
            previewPlayWhenReadyBeforeStop || player?.playWhenReady == true
        previewStoppedForLifecycle = false
        previewPlayWhenReadyBeforeStop = false
        player?.playWhenReady = false
        previewHasRenderedFrame = false
        binding.preview.player = null
        _binding = null
        super.onDestroyView()
    }

    private fun closeEditor() {
        if (resultSent) return
        clipDebug("editor close")
        sendResult()
        parentFragmentManager.beginTransaction()
            .remove(this)
            .commit()
    }

    override fun onDestroy() {
        exportJob?.cancel()
        clipDebug("preview player release")
        player?.release()
        player = null
        super.onDestroy()
    }

    internal val preparedDirectoryPath: String?
        get() = arguments?.getString(ARG_DIRECTORY)

    private fun sendResult() {
        if (!resultSent && isAdded) {
            resultSent = true
            parentFragmentManager.setFragmentResult(
                RESULT_KEY,
                Bundle().apply { putString(RESULT_DIRECTORY, directory.absolutePath) },
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != STORAGE_PERMISSION_REQUEST || !storagePermissionRequestPending) return
        storagePermissionRequestPending = false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            exportClip()
        } else {
            Toast.makeText(requireContext(), R.string.storage_permission_message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val ARG_PLAYLIST = "playlist"
        private const val ARG_DIRECTORY = "directory"
        private const val ARG_BOUNDARIES = "boundariesUs"
        private const val ARG_CHANNEL_NAME = "channelName"
        private const val STATE_START_US = "startUs"
        private const val STATE_END_US = "endUs"
        private const val STATE_FILE_NAME = "fileName"
        private const val STATE_SAVED_DISPLAY_NAME = "savedDisplayName"
        private const val STATE_SHARE_FILE = "shareFile"
        private const val STATE_SAVED_URI = "savedUri"
        const val RESULT_KEY = "liveClipEditorResult"
        const val RESULT_DIRECTORY = "directoryPath"
        const val PREVIEW_READY_KEY = "liveClipEditorPreviewReady"
        private const val STORAGE_PERMISSION_REQUEST = 1001
        private const val PREVIEW_POLL_MS = 100L
        private const val PREVIEW_CONTROLS_HIDE_MS = 3_000L
        private const val PREVIEW_SEEK_MS = 5_000L
        private const val CLIP_LOCATION = "Movies/Xtra/Clips"
        private const val CLIP_LOG_TAG = "XtraClipEditor"

        private data class PublishedClip(
            val uri: Uri,
            val displayName: String,
            val location: String,
        )

        fun newInstance(
            playlistPath: String,
            directoryPath: String,
            boundariesUs: LongArray,
            channelName: String?,
        ) = ClipEditorDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_PLAYLIST, playlistPath)
                putString(ARG_DIRECTORY, directoryPath)
                putLongArray(ARG_BOUNDARIES, boundariesUs)
                putString(ARG_CHANNEL_NAME, channelName)
            }
        }
    }

    private fun clipDebug(message: String) {
        if (BuildConfig.DEBUG && Log.isLoggable(CLIP_LOG_TAG, Log.DEBUG)) {
            Log.d(CLIP_LOG_TAG, "[CLIP-UI] $message")
        }
    }
}
