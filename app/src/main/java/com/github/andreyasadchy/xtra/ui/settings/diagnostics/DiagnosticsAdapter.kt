package com.github.andreyasadchy.xtra.ui.settings.diagnostics

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ItemDiagnosticsEntryBinding
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsEntry
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsFormatter

class DiagnosticsAdapter(
    private val onCopy: (String) -> Unit,
) : ListAdapter<DiagnosticsEntry, DiagnosticsAdapter.ViewHolder>(DIFF_CALLBACK) {
    private val expandedSequences = mutableSetOf<Long>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ItemDiagnosticsEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemDiagnosticsEntryBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: DiagnosticsEntry) {
            val expanded = entry.sequence in expandedSequences
            binding.summary.text = buildString {
                append(DiagnosticsFormatter.formatTimestamp(entry.timestampMs))
                append("  ")
                append(entry.severity.name)
                append(" · ")
                append(entry.category.name)
                append("/")
                append(entry.transport.name)
                append("\n")
                append(entry.operation)
                append(" · ")
                append(entry.event)
            }
            binding.details.text = DiagnosticsFormatter.formatEntry(entry)
            binding.detailsContainer.visibility = if (expanded) android.view.View.VISIBLE else android.view.View.GONE
            binding.expandButton.setImageResource(
                if (expanded) R.drawable.ic_happening_now_chevron_up
                else R.drawable.ic_happening_now_chevron_down,
            )
            binding.expandButton.contentDescription = binding.root.context.getString(
                if (expanded) R.string.diagnostics_entry_collapse else R.string.diagnostics_entry_expand,
            )
            binding.expandButton.setOnClickListener {
                if (expandedSequences.remove(entry.sequence).not()) expandedSequences.add(entry.sequence)
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::notifyItemChanged)
            }
            binding.copyEntryButton.setOnClickListener {
                onCopy(DiagnosticsFormatter.formatEntry(entry))
            }
        }
    }

    override fun submitList(list: List<DiagnosticsEntry>?) {
        val sequences = list.orEmpty().mapTo(HashSet()) { it.sequence }
        expandedSequences.retainAll(sequences)
        super.submitList(list)
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DiagnosticsEntry>() {
            override fun areItemsTheSame(oldItem: DiagnosticsEntry, newItem: DiagnosticsEntry): Boolean =
                oldItem.sequence == newItem.sequence

            override fun areContentsTheSame(oldItem: DiagnosticsEntry, newItem: DiagnosticsEntry): Boolean =
                oldItem == newItem
        }
    }
}
