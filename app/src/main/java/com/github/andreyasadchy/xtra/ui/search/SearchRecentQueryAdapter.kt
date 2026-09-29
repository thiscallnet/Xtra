package com.github.andreyasadchy.xtra.ui.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ItemSearchRecentQueryBinding
import com.github.andreyasadchy.xtra.model.ui.RecentSearch

class SearchRecentQueryAdapter(
    private val onClick: (RecentSearch) -> Unit,
    private val onDelete: (RecentSearch) -> Unit,
) : ListAdapter<RecentSearch, SearchRecentQueryAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSearchRecentQueryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(
        private val binding: ItemSearchRecentQueryBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: RecentSearch? = null

        init {
            binding.root.setOnClickListener { item?.let(onClick) }
            binding.delete.setOnClickListener { item?.let(onDelete) }
        }

        fun bind(value: RecentSearch) {
            item = value
            binding.query.text = value.query
            binding.type.setText(
                when (value.type) {
                    RecentSearch.TYPE_STREAM -> R.string.streams
                    RecentSearch.TYPE_CHANNEL -> R.string.channels
                    RecentSearch.TYPE_GAME -> R.string.games
                    else -> R.string.videos
                },
            )
            binding.root.contentDescription = "${value.query}, ${binding.type.text}"
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<RecentSearch>() {
            override fun areItemsTheSame(oldItem: RecentSearch, newItem: RecentSearch): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: RecentSearch, newItem: RecentSearch): Boolean =
                oldItem.query == newItem.query &&
                    oldItem.type == newItem.type &&
                    oldItem.lastSearched == newItem.lastSearched
        }
    }
}
