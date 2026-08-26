package com.github.andreyasadchy.xtra.ui.whispers

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ItemWhisperThreadBinding
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperThread
import com.github.andreyasadchy.xtra.ui.inbox.relativeTime

class WhisperThreadsAdapter(private val onClick: (WhisperThread) -> Unit) : RecyclerView.Adapter<WhisperThreadsAdapter.ViewHolder>() {
    private var items: List<WhisperThread> = emptyList()
    fun submitList(value: List<WhisperThread>) { items = value; notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemWhisperThreadBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    inner class ViewHolder(private val binding: ItemWhisperThreadBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: WhisperThread) = with(binding) {
            root.setOnClickListener { onClick(item) }
            name.text = item.peer.displayName
            name.setTypeface(null, if (item.isUnread) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            preview.text = item.lastMessage?.text.orEmpty().ifBlank { root.context.getString(R.string.message) }
            timestamp.text = relativeTime(item.updatedAt)
            unread.visibility = if (item.isUnread) View.VISIBLE else View.GONE
            unread.text = item.unreadCount?.takeIf { it > 0 }?.let { if (it > 99) "99+" else it.toString() }.orEmpty()
            avatar.setImageResource(R.drawable.baseline_circle_24)
            item.peer.profileImageUrl?.let { url ->
                avatar.context.imageLoader.enqueue(ImageRequest.Builder(avatar.context).data(url).crossfade(true).transformations(CircleCropTransformation()).target(avatar).build())
            }
            root.contentDescription = "${item.peer.displayName}, ${preview.text}"
        }
    }
}
