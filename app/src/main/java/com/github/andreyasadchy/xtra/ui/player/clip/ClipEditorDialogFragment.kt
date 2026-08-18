package com.github.andreyasadchy.xtra.ui.player.clip

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.provider.MediaStore
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.github.andreyasadchy.xtra.R
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
import kotlin.math.abs

/** Full-screen segment-aligned editor for a prepared local live snapshot. */
@OptIn(UnstableApi::class)
class ClipEditorDialogFragment : DialogFragment() {
    private var _binding: FragmentClipEditorBinding? = null
    private val binding get() = _binding!!

    private lateinit var playlistFile: File
    private lateinit var directory: File
    private lateinit var boundariesUs: LongArray
    private var durationMs = 0L
    private var channelName: String? = null
    private var player: ExoPlayer? = null
    private var exportJob: Job? = null
    private var savedUri: Uri? = null
    private var updatingSlider = false
    private var startMs = 0f
    private var endMs = 0f
    private var storagePermissionRequestPending = false
    private var resultSent = false
    private val previewHandler = Handler(Looper.getMainLooper())
    private val previewLoop = object : Runnable {
        override fun run() {
            val previewPlayer = player
            if (previewPlayer != null && previewPlayer.isPlaying && previewPlayer.currentPosition >= endMs.toLong()) {
                previewPlayer.seekTo(startMs.toLong())
            }
            if (_binding != null) previewHandler.postDelayed(this, PREVIEW_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
        playlistFile = File(requireArguments().getString(ARG_PLAYLIST)!!)
        directory = File(requireArguments().getString(ARG_DIRECTORY)!!)
        boundariesUs = requireArguments().getLongArray(ARG_BOUNDARIES) ?: longArrayOf(0L)
        durationMs = requireArguments().getLong(ARG_DURATION_MS)
        channelName = requireArguments().getString(ARG_CHANNEL_NAME)
        startMs = savedInstanceState?.getFloat(STATE_START_MS) ?: 0f
        endMs = savedInstanceState?.getFloat(STATE_END_MS) ?: durationMs.toFloat()
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

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0f)
        }
        dialog?.setCanceledOnTouchOutside(false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.channel.text = channelName.orEmpty()
        binding.close.setOnClickListener { dismiss() }
        binding.done.setOnClickListener { dismiss() }
        binding.share.setOnClickListener { shareSavedClip() }
        binding.save.setOnClickListener { exportClip() }

        val safeDurationMs = durationMs.coerceAtLeast(1L)
        durationMs = safeDurationMs
        boundariesUs = boundariesUs
            .filter { it in 0L..(safeDurationMs * 1000L) }
            .distinct()
            .sorted()
            .toLongArray()
            .takeIf { it.size >= 2 } ?: longArrayOf(0L, safeDurationMs * 1000L)
        startMs = snapToBoundary(startMs).coerceIn(0f, safeDurationMs - MIN_SELECTION_MS)
        endMs = snapToBoundary(endMs).coerceIn(startMs + MIN_SELECTION_MS, safeDurationMs.toFloat())

        binding.rangeSlider.valueFrom = 0f
        binding.rangeSlider.valueTo = safeDurationMs.toFloat()
        binding.rangeSlider.setValues(listOf(startMs, endMs))
        binding.rangeSlider.addOnChangeListener { _, _, _ ->
            if (!updatingSlider) updateSelection(seekPreview = true)
        }
        binding.rangeSlider.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) = Unit

            override fun onStopTrackingTouch(slider: RangeSlider) {
                snapSliderValues()
            }
        })
        updateSelection(seekPreview = false)
        if (savedUri != null) {
            binding.save.isVisible = false
            binding.rangeSlider.isEnabled = false
            binding.share.isVisible = true
            binding.done.isVisible = true
        }

        player = ExoPlayer.Builder(requireContext()).build().also { exoPlayer ->
            binding.preview.player = exoPlayer
            val mediaItem = MediaItem.Builder()
                .setUri(playlistFile.toUri())
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
            exoPlayer.setMediaSource(
                HlsMediaSource.Factory(DefaultDataSource.Factory(requireContext()))
                    .createMediaSource(mediaItem)
            )
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
        previewHandler.post(previewLoop)
    }

    private fun snapSliderValues() {
        val values = binding.rangeSlider.values
        val snappedStart = snapToBoundary(values[0])
        val snappedEnd = snapToBoundary(values[1])
        var nextStart = snappedStart
        var nextEnd = snappedEnd
        if (nextEnd - nextStart < MIN_SELECTION_MS) {
            val endIndex = boundaryIndex(nextEnd)
            if (endIndex < boundariesUs.lastIndex) {
                nextEnd = boundariesUs[endIndex + 1] / 1000f
            } else {
                val startIndex = boundaryIndex(nextStart)
                nextStart = boundariesUs[(startIndex - 1).coerceAtLeast(0)] / 1000f
            }
        }
        updatingSlider = true
        binding.rangeSlider.setValues(nextStart, nextEnd)
        updatingSlider = false
        updateSelection(seekPreview = true)
    }

    private fun updateSelection(seekPreview: Boolean) {
        val values = binding.rangeSlider.values
        if (values.size < 2) return
        startMs = values[0]
        endMs = values[1]
        binding.selectionDuration.text = getString(
            R.string.clip_editor_selected_duration,
            formatDuration((endMs - startMs).toLong()),
        )
        binding.startLabel.text = formatRelativePosition(startMs.toLong())
        binding.endLabel.text = formatRelativePosition(endMs.toLong())
        if (seekPreview) player?.seekTo(startMs.toLong())
    }

    private fun formatRelativePosition(positionMs: Long): String {
        val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
        return if (remainingMs <= 500L) {
            getString(R.string.clip_editor_now)
        } else {
            getString(R.string.clip_editor_seconds_ago, ((remainingMs + 500L) / 1000L).toInt())
        }
    }

    private fun snapToBoundary(valueMs: Float): Float {
        val targetUs = valueMs.toDouble() * 1000.0
        return boundariesUs.minBy { abs(it.toDouble() - targetUs) }.toFloat() / 1000f
    }

    private fun boundaryIndex(valueMs: Float): Int {
        val targetUs = valueMs.toDouble() * 1000.0
        return boundariesUs.indices.minBy { abs(boundariesUs[it].toDouble() - targetUs) }
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
        val startIndex = boundaryIndex(startMs)
        val endIndex = boundaryIndex(endMs)
        if (endIndex <= startIndex) return
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
                val publishedUri = publishToMediaStore(exported.file)
                if (publishedUri == null) throw IllegalStateException("MediaStore insert failed")
                savedUri = publishedUri
                binding.progress.isVisible = false
                binding.save.isVisible = false
                binding.share.isVisible = true
                binding.done.isVisible = true
                Toast.makeText(requireContext(), R.string.clip_saved, Toast.LENGTH_SHORT).show()
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

    private suspend fun publishToMediaStore(file: File): Uri? = withContext(Dispatchers.IO) {
        val displayName = "Xtra_${sanitize(channelName)}_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.mp4"
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
                uri
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                legacyFile.delete()
                throw error
            }
        } else {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
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
                uri
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
        }
    }

    private fun shareSavedClip() {
        val uri = savedUri ?: return
        startActivity(
            Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.let { Intent.createChooser(it, getString(R.string.clip_editor_share)) }
        )
    }

    private fun sanitize(value: String?): String = value.orEmpty()
        .ifBlank { "clip" }
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .take(48)

    private fun formatDuration(milliseconds: Long): String = DateUtils.formatElapsedTime(milliseconds / 1000L)

    @Suppress("DEPRECATION")
    private fun readSavedUri(state: Bundle?): Uri? = state?.getParcelable(STATE_SAVED_URI)

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putFloat(STATE_START_MS, startMs)
        outState.putFloat(STATE_END_MS, endMs)
        outState.putParcelable(STATE_SAVED_URI, savedUri)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        previewHandler.removeCallbacks(previewLoop)
        binding.preview.player = null
        player?.release()
        player = null
        _binding = null
        super.onDestroyView()
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        exportJob?.cancel()
        if (!resultSent) {
            resultSent = true
            parentFragmentManager.setFragmentResult(
                RESULT_KEY,
                Bundle().apply { putString(RESULT_DIRECTORY, directory.absolutePath) },
            )
        }
        super.onDismiss(dialog)
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
        private const val ARG_DURATION_MS = "durationMs"
        private const val ARG_BOUNDARIES = "boundariesUs"
        private const val ARG_CHANNEL_NAME = "channelName"
        private const val STATE_START_MS = "startMs"
        private const val STATE_END_MS = "endMs"
        private const val STATE_SAVED_URI = "savedUri"
        const val RESULT_KEY = "liveClipEditorResult"
        const val RESULT_DIRECTORY = "directoryPath"
        private const val MIN_SELECTION_MS = 1f
        private const val STORAGE_PERMISSION_REQUEST = 1001
        private const val PREVIEW_POLL_MS = 100L

        fun newInstance(
            playlistPath: String,
            directoryPath: String,
            durationMs: Long,
            boundariesUs: LongArray,
            channelName: String?,
        ) = ClipEditorDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_PLAYLIST, playlistPath)
                putString(ARG_DIRECTORY, directoryPath)
                putLong(ARG_DURATION_MS, durationMs)
                putLongArray(ARG_BOUNDARIES, boundariesUs)
                putString(ARG_CHANNEL_NAME, channelName)
            }
        }
    }
}
