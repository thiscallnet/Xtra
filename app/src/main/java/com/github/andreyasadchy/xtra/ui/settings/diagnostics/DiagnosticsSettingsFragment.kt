package com.github.andreyasadchy.xtra.ui.settings.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.FragmentDiagnosticsSettingsBinding
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsCategory
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsFilter
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsFormatter
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsLogger
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsSeverity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

class DiagnosticsSettingsFragment : Fragment() {
    private var _binding: FragmentDiagnosticsSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var logger: DiagnosticsLogger
    private lateinit var adapter: DiagnosticsAdapter
    private var categories = DiagnosticsCategory.entries.toSet()
    private var severities = DiagnosticsSeverity.entries.toSet()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDiagnosticsSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        logger = (requireContext().applicationContext as XtraApp).xtraModule.diagnosticsLogger
        adapter = DiagnosticsAdapter(::copyText)
        binding.entriesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.entriesRecyclerView.adapter = adapter
        binding.enabledSwitch.isChecked = logger.isEnabled
        binding.enabledSwitch.setOnCheckedChangeListener { _, enabled -> logger.setEnabled(enabled) }
        binding.categoryFilterButton.setOnClickListener { showCategoryFilter() }
        binding.severityFilterButton.setOnClickListener { showSeverityFilter() }
        binding.clearButton.setOnClickListener { logger.clear() }
        binding.copyButton.setOnClickListener { copyText(formatCurrentEntries()) }
        binding.shareButton.setOnClickListener { shareCurrentEntries() }
        render()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                logger.changes.conflate().collectLatest { render() }
            }
        }
    }

    private fun render() {
        val entries = logger.snapshot(DiagnosticsFilter(categories, severities))
        adapter.submitList(entries)
        binding.emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.entriesRecyclerView.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        val enabled = logger.isEnabled
        binding.categoryFilterButton.isEnabled = enabled
        binding.severityFilterButton.isEnabled = enabled
        binding.clearButton.isEnabled = enabled && entries.isNotEmpty()
        binding.copyButton.isEnabled = enabled && entries.isNotEmpty()
        binding.shareButton.isEnabled = enabled && entries.isNotEmpty()
    }

    private fun showCategoryFilter() {
        val values = DiagnosticsCategory.entries
        val selected = categories.toMutableSet()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.diagnostics_category_filter)
            .setMultiChoiceItems(
                values.map(::displayName).toTypedArray(),
                BooleanArray(values.size) { values[it] in categories },
            ) { _, index, checked ->
                if (checked) selected.add(values[index]) else selected.remove(values[index])
            }
            .setPositiveButton(R.string.diagnostics_filter_apply) { _, _ ->
                categories = selected.toSet()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSeverityFilter() {
        val values = DiagnosticsSeverity.entries
        val selected = severities.toMutableSet()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.diagnostics_severity_filter)
            .setMultiChoiceItems(
                values.map(::displayName).toTypedArray(),
                BooleanArray(values.size) { values[it] in severities },
            ) { _, index, checked ->
                if (checked) selected.add(values[index]) else selected.remove(values[index])
            }
            .setPositiveButton(R.string.diagnostics_filter_apply) { _, _ ->
                severities = selected.toSet()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatCurrentEntries(): String = DiagnosticsFormatter.formatAll(
        logger.snapshot(DiagnosticsFilter(categories, severities)),
    )

    private fun copyText(text: String) {
        requireContext().getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.settings_diagnostics_live), text),
        )
        Toast.makeText(requireContext(), R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareCurrentEntries() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, formatCurrentEntries())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.diagnostics_share)))
    }

    private fun displayName(value: Enum<*>): String = value.name
        .replace('_', ' ')
        .lowercase()
        .replaceFirstChar { it.titlecase() }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
