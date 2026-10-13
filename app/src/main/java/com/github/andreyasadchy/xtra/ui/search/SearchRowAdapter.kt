package com.github.andreyasadchy.xtra.ui.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import coil3.transform.RoundedCornersTransformation
import com.github.andreyasadchy.xtra.databinding.ItemSearchHistoryBinding
import com.github.andreyasadchy.xtra.databinding.ItemSearchSectionHeaderBinding
import com.github.andreyasadchy.xtra.databinding.ItemSectionStatusBinding
import com.github.andreyasadchy.xtra.ui.common.bindStatus
import com.github.andreyasadchy.xtra.model.ui.SearchHistoryItem
import com.github.andreyasadchy.xtra.ui.view.FullSpanItems
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

/**
 * Compact list used by the search landing page, the pinned matches in the tabs and the All tab.
 * [onRemove] null hides the remove button on every entry.
 */
class SearchRowAdapter(
    private val onOpen: (SearchRow.Entry) -> Unit,
    private val onRemove: ((SearchHistoryItem) -> Unit)? = null,
    private val onAction: (String) -> Unit = {},
) : ListAdapter<SearchRow, RecyclerView.ViewHolder>(DIFF_CALLBACK), FullSpanItems {

    private var submittedRows: List<SearchRow> = emptyList()

    override fun submitList(list: List<SearchRow>?) = submitList(list, null)

    override fun submitList(list: List<SearchRow>?, commitCallback: Runnable?) {
        val snapshot = list?.toList().orEmpty()
        submittedRows = snapshot
        super.submitList(snapshot, commitCallback)
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is SearchRow.Header -> TYPE_HEADER
        is SearchRow.Entry -> TYPE_ENTRY
        is SearchRow.Status -> TYPE_STATUS
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(ItemSearchSectionHeaderBinding.inflate(inflater, parent, false))
            TYPE_STATUS -> StatusHolder(ItemSectionStatusBinding.inflate(inflater, parent, false))
            else -> EntryHolder(ItemSearchHistoryBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is SearchRow.Header -> (holder as HeaderHolder).bind(row)
            is SearchRow.Entry -> (holder as EntryHolder).bind(row)
            is SearchRow.Status -> (holder as StatusHolder).bind(row)
        }
    }

    private inner class StatusHolder(private val binding: ItemSectionStatusBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: SearchRow.Status) = binding.bindStatus(row.status, row.title) { onAction("retry:${row.section}") }
    }

    private inner class HeaderHolder(private val binding: ItemSearchSectionHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        private var actionKey: String? = null

        init {
            binding.action.setOnClickListener { actionKey?.let(onAction) }
        }

        fun bind(row: SearchRow.Header) {
            actionKey = row.actionKey
            binding.title.text = row.title
            androidx.core.view.ViewCompat.setAccessibilityHeading(binding.title, true)
            binding.action.isVisible = row.actionText != null
            binding.action.text = row.actionText
        }
    }

    private inner class EntryHolder(private val binding: ItemSearchHistoryBinding) : RecyclerView.ViewHolder(binding.root) {
        private var row: SearchRow.Entry? = null

        init {
            binding.root.setOnClickListener { currentRow()?.let(onOpen) }
            binding.delete.setOnClickListener { currentRow()?.takeIf { it.removable }?.let { onRemove?.invoke(it.item) } }
        }

        private fun currentRow() = submittedRows.firstOrNull { it.key == row?.key } as? SearchRow.Entry

        fun bind(value: SearchRow.Entry) {
            row = value
            val context = binding.root.context
            binding.title.text = value.item.title
            binding.subtitle.text = value.subtitle
            binding.liveBadge.isVisible = value.live
            binding.delete.isVisible = onRemove != null && value.removable
            binding.root.contentDescription = "${value.item.title}, ${value.subtitle}"
            context.imageLoader.enqueue(
                ImageRequest.Builder(context).apply {
                    data(value.item.imageUrl)
                    when {
                        !value.item.isChannel -> transformations(RoundedCornersTransformation(context.resources.displayMetrics.density * 8))
                        value.roundAvatar ?: context.prefs().getBoolean(C.UI_ROUND_USER_IMAGE, true) -> transformations(CircleCropTransformation())
                    }
                    crossfade(true)
                    target(binding.image)
                }.build()
            )
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ENTRY = 1
        private const val TYPE_STATUS = 2

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SearchRow>() {
            override fun areItemsTheSame(oldItem: SearchRow, newItem: SearchRow): Boolean = oldItem.key == newItem.key

            override fun areContentsTheSame(oldItem: SearchRow, newItem: SearchRow): Boolean =
                if (oldItem is SearchRow.Entry && newItem is SearchRow.Entry) {
                    oldItem.item.title == newItem.item.title && oldItem.item.imageUrl == newItem.item.imageUrl &&
                        oldItem.subtitle == newItem.subtitle && oldItem.live == newItem.live &&
                        oldItem.removable == newItem.removable && oldItem.roundAvatar == newItem.roundAvatar
                } else oldItem == newItem
        }
    }
}
