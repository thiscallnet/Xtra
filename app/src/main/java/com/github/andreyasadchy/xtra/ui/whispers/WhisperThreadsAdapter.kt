package com.github.andreyasadchy.xtra.ui.whispers

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ItemWhisperThreadBinding
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchUserSummary
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperThread
import com.github.andreyasadchy.xtra.ui.common.personPlaceholder
import com.github.andreyasadchy.xtra.ui.inbox.relativeTime
import java.lang.ref.WeakReference

class WhisperThreadsAdapter(
    private val onClick: (WhisperThread) -> Unit,
    private val onAvatarClick: (TwitchUserSummary) -> Unit,
) : RecyclerView.Adapter<WhisperThreadsAdapter.ViewHolder>() {
    private data class Row(val item: WhisperThread, val selected: Boolean)

    private val differ = AsyncListDiffer(this, object : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row) = oldItem.item.id == newItem.item.id
        override fun areContentsTheSame(oldItem: Row, newItem: Row) = oldItem == newItem
        override fun getChangePayload(oldItem: Row, newItem: Row): Any? =
            if (oldItem.item.copy(
                    unreadCount = newItem.item.unreadCount,
                    isUnread = newItem.item.isUnread,
                    updatedAt = newItem.item.updatedAt,
                ) == newItem.item
            ) STATE else null
    })
    private var submittedItems: List<WhisperThread> = emptyList()
    private var selectedPeerId: String? = null
    private var recyclerView = WeakReference<RecyclerView>(null)

    fun submitList(value: List<WhisperThread>, selectedPeerId: String? = null) {
        if (submittedItems == value && this.selectedPeerId == selectedPeerId) return
        submittedItems = value.toList()
        this.selectedPeerId = selectedPeerId
        differ.submitList(submittedItems.map { Row(it, it.peer.id == selectedPeerId) }) {
            // Selection/unread updates keep their preview and avatar. Refresh
            // attached relative labels separately, without formatting every row.
            refreshTimes()
        }
    }

    private fun currentItem(item: WhisperThread): WhisperThread? =
        submittedItems.firstOrNull { it.id == item.id }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = WeakReference(recyclerView)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (this.recyclerView.get() === recyclerView) this.recyclerView.clear()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    private fun refreshTimes() {
        val view = recyclerView.get()?.takeIf { it.isAttachedToWindow } ?: return
        for (index in 0 until view.childCount) {
            val holder = view.getChildViewHolder(view.getChildAt(index))
            if (holder.bindingAdapter !== this) continue
            val position = holder.bindingAdapterPosition
            if (position in 0 until itemCount) notifyItemChanged(position, TIME)
        }
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemWhisperThreadBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = differ.currentList.size
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = differ.currentList[position]
        holder.bind(row.item, relativeTime(row.item.updatedAt), row.selected)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        val row = differ.currentList[position]
        val time = relativeTime(row.item.updatedAt)
        if (payloads.isNotEmpty() && payloads.all { it == STATE || it == TIME }) {
            if (STATE in payloads) holder.bindState(row.item, time, row.selected) else holder.bindTime(time)
        } else holder.bind(row.item, time, row.selected)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.cancelAvatar()
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(private val binding: ItemWhisperThreadBinding) : RecyclerView.ViewHolder(binding.root) {
        private var avatarRequest: Disposable? = null

        fun cancelAvatar() {
            avatarRequest?.dispose()
            avatarRequest = null
        }

        fun bindTime(time: String) {
            if (binding.timestamp.text.toString() != time) binding.timestamp.text = time
        }

        fun bindState(item: WhisperThread, time: String, selected: Boolean) = with(binding) {
            root.isSelected = selected
            root.setOnClickListener { currentItem(item)?.let(onClick) }
            avatar.setOnClickListener { currentItem(item)?.peer?.let(onAvatarClick) }
            name.setTypeface(null, if (item.isUnread) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            bindTime(time)
            unread.visibility = if (item.isUnread) View.VISIBLE else View.GONE
            unread.text = item.unreadCount?.takeIf { it > 0 }?.let { if (it > 99) "99+" else it.toString() }.orEmpty()
        }

        fun bind(item: WhisperThread, time: String, selected: Boolean) = with(binding) {
            bindState(item, time, selected)
            avatar.isClickable = true
            avatar.isFocusable = true
            avatar.contentDescription = root.context.getString(R.string.view_profile)
            name.text = item.peer.displayName
            preview.text = item.lastMessage?.text.orEmpty().ifBlank { root.context.getString(R.string.message) }
            cancelAvatar()
            val avatarPlaceholder = avatar.personPlaceholder()
            avatar.setImageDrawable(avatarPlaceholder)
            item.peer.profileImageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                avatarRequest = avatar.context.imageLoader.enqueue(
                    ImageRequest.Builder(avatar.context)
                        .data(url)
                        .placeholder(avatarPlaceholder)
                        .error(avatarPlaceholder)
                        .crossfade(true)
                        .transformations(CircleCropTransformation())
                        .target(avatar)
                        .build(),
                )
            }
            root.contentDescription = "${item.peer.displayName}, ${preview.text}"
        }
    }

    private companion object {
        const val STATE = "state"
        const val TIME = "time"
    }
}
