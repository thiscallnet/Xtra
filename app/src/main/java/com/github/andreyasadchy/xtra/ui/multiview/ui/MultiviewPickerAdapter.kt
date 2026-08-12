package com.github.andreyasadchy.xtra.ui.multiview.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.databinding.MultiviewPickerListItemBinding
import com.github.andreyasadchy.xtra.model.ui.Stream

class MultiviewPickerAdapter(
    private val isExcluded: (Stream) -> Boolean,
    private val isSelected: (Stream) -> Boolean,
    private val onClick: (Stream) -> Unit,
) : PagingDataAdapter<Stream, MultiviewPickerAdapter.ViewHolder>(DIFF_CALLBACK) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(MultiviewPickerListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        getItem(position)?.let(holder::bind)
    }

    fun refreshSelection() {
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    inner class ViewHolder(
        private val binding: MultiviewPickerListItemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(stream: Stream) {
            val excluded = isExcluded(stream)
            binding.channelName.text = stream.channelName ?: stream.channelLogin.orEmpty()
            binding.streamTitle.text = stream.title.orEmpty()
            binding.streamTitle.visibility = if (stream.title.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
            binding.metadata.text = listOfNotNull(
                stream.gameName,
                stream.viewerCount?.let { binding.root.context.getString(com.github.andreyasadchy.xtra.R.string.multiview_viewers, it) },
            ).joinToString(" · ")
            binding.metadata.visibility = if (binding.metadata.text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
            binding.selected.isChecked = isSelected(stream)
            binding.root.alpha = if (excluded) 0.55f else 1f
            binding.root.isEnabled = !excluded
            binding.root.contentDescription = if (excluded) {
                "${binding.channelName.text}, ${binding.root.context.getString(com.github.andreyasadchy.xtra.R.string.multiview_already_added)}"
            } else {
                binding.channelName.text
            }
            binding.root.setOnClickListener { if (!excluded) onClick(stream) }
        }
    }

    companion object {
        private val PAYLOAD_SELECTION = Any()
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Stream>() {
            override fun areItemsTheSame(oldItem: Stream, newItem: Stream): Boolean {
                return stableIdentity(oldItem) == stableIdentity(newItem)
            }

            override fun areContentsTheSame(oldItem: Stream, newItem: Stream): Boolean {
                return oldItem == newItem
            }
        }

        private fun stableIdentity(stream: Stream): String? {
            return stream.channelId?.takeIf { it.isNotBlank() }?.let { "id:${it.lowercase()}" }
                ?: stream.channelLogin?.takeIf { it.isNotBlank() }?.let { "login:${it.lowercase()}" }
                ?: stream.id?.takeIf { it.isNotBlank() }?.let { "stream:${it.lowercase()}" }
        }
    }
}
