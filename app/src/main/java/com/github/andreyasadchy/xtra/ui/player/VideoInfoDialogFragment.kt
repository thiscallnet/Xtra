package com.github.andreyasadchy.xtra.ui.player

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.Gravity
import android.widget.TableRow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogVideoInfoBinding
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class VideoInfoDialogFragment : DialogFragment() {

    private data class RowViews(
        val name: TextView,
        val value: TextView,
    )

    private var _binding: DialogVideoInfoBinding? = null
    private val binding get() = _binding!!
    private var refreshJob: Job? = null
    private var latestInfo: PlaybackVideoInfo? = null
    private var latestViewMetrics = PlaybackVideoViewMetrics()
    private val rows = mutableListOf<RowViews>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogVideoInfoBinding.inflate(layoutInflater)
        val dialog = requireContext().getAlertDialogBuilder()
            .setTitle(R.string.video_info)
            .setView(binding.root)
            .setNeutralButton(R.string.copy_clip, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
                val info = latestInfo ?: return@setOnClickListener
                val clipboard = ContextCompat.getSystemService(requireContext(), ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    ClipData.newPlainText(
                        getString(R.string.video_info),
                        info.toSanitizedText(latestViewMetrics),
                    ),
                )
            }
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    requestRefresh()
                    delay(1_000L)
                }
            }
        }
    }

    override fun onStop() {
        refreshJob?.cancel()
        refreshJob = null
        super.onStop()
    }

    private fun requestRefresh() {
        (parentFragment as? PlaybackVideoInfoHost)?.requestVideoInfo { info, viewMetrics ->
            if (!isAdded || _binding == null ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) {
                return@requestVideoInfo
            }
            latestInfo = info
            latestViewMetrics = viewMetrics
            render(info, viewMetrics)
        }
    }

    private fun render(info: PlaybackVideoInfo, viewMetrics: PlaybackVideoViewMetrics) {
        val data = videoInfoRows(info, viewMetrics)
        ensureRows(data)
        data.forEachIndexed { index, row ->
            val value = rows[index].value
            if (value.text.toString() != row.value) {
                value.text = row.value
            }
        }
    }

    private fun ensureRows(data: List<VideoInfoRow>) {
        if (rows.size == data.size) return

        binding.infoTable.removeAllViews()
        rows.clear()
        val primaryColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
        val secondaryColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        data.forEach { row ->
            val tableRow = TableRow(requireContext()).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = TextView(requireContext()).apply {
                text = row.name
                textSize = 14f
                setTextColor(secondaryColor)
                setPadding(0, 5, 16, 5)
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
            }
            val value = TextView(requireContext()).apply {
                text = row.value
                textSize = 14f
                setTextColor(primaryColor)
                gravity = Gravity.END
                setPadding(16, 5, 0, 5)
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
            }
            tableRow.addView(name)
            tableRow.addView(value)
            binding.infoTable.addView(tableRow)
            rows += RowViews(name, value)
        }
    }

    override fun onDestroyView() {
        refreshJob?.cancel()
        refreshJob = null
        rows.clear()
        _binding = null
        super.onDestroyView()
    }
}
