package com.github.andreyasadchy.xtra.ui.search.streams

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.databinding.ItemSearchStreamSuggestionsBinding
import com.github.andreyasadchy.xtra.model.ui.Stream

class SearchStreamSuggestionsHeaderAdapter(
    private val fragment: Fragment,
) : RecyclerView.Adapter<SearchStreamSuggestionsHeaderAdapter.ViewHolder>() {

    private var streams: List<Stream> = emptyList()

    override fun getItemCount(): Int = if (streams.isEmpty()) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSearchStreamSuggestionsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(streams)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.detach()
        super.onViewRecycled(holder)
    }

    fun submitStreams(items: List<Stream>) {
        val wasEmpty = streams.isEmpty()
        streams = items.distinctBy { it.channelId ?: it.id }
        when {
            wasEmpty && streams.isNotEmpty() -> notifyItemInserted(0)
            !wasEmpty && streams.isEmpty() -> notifyItemRemoved(0)
            streams.isNotEmpty() -> notifyItemChanged(0)
        }
    }

    inner class ViewHolder(
        private val binding: ItemSearchStreamSuggestionsBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private val adapter = SearchStreamSuggestionAdapter(fragment)

        init {
            binding.recyclerView.apply {
                layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
                itemAnimator = null
                setHasFixedSize(true)
                isNestedScrollingEnabled = false
                adapter = this@ViewHolder.adapter
            }
        }

        fun bind(items: List<Stream>) {
            if (binding.recyclerView.adapter !== adapter) {
                binding.recyclerView.adapter = adapter
            }
            adapter.submitList(items)
        }

        fun detach() {
            binding.recyclerView.adapter = null
        }
    }
}
