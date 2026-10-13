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
import com.github.andreyasadchy.xtra.databinding.ItemTwitchUserResultBinding
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchUserSummary
import com.github.andreyasadchy.xtra.ui.common.personPlaceholder

class TwitchUsersAdapter(
    private val onClick: (TwitchUserSummary) -> Unit,
    private val onAvatarClick: (TwitchUserSummary) -> Unit,
) : RecyclerView.Adapter<TwitchUsersAdapter.ViewHolder>() {
    private data class Row(val item: TwitchUserSummary, val selected: Boolean)

    private val differ = AsyncListDiffer(this, object : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row) = oldItem.item.id == newItem.item.id
        override fun areContentsTheSame(oldItem: Row, newItem: Row) = oldItem == newItem
        override fun getChangePayload(oldItem: Row, newItem: Row): Any? =
            if (oldItem.item == newItem.item) SELECTION else null
    })
    private var submittedItems: List<TwitchUserSummary> = emptyList()
    private var selectedPeerId: String? = null

    fun submitList(value: List<TwitchUserSummary>, selectedPeerId: String? = null) {
        if (submittedItems == value && this.selectedPeerId == selectedPeerId) return
        submittedItems = value.toList()
        this.selectedPeerId = selectedPeerId
        differ.submitList(submittedItems.map { Row(it, it.id == selectedPeerId) })
    }

    private fun currentItem(item: TwitchUserSummary): TwitchUserSummary? =
        submittedItems.firstOrNull { it.id == item.id }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemTwitchUserResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = differ.currentList.size
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = differ.currentList[position]
        holder.bind(row.item, row.selected)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        val row = differ.currentList[position]
        if (payloads.isNotEmpty() && payloads.all { it == SELECTION }) holder.bindSelection(row.selected)
        else holder.bind(row.item, row.selected)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.cancelAvatar()
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(private val binding: ItemTwitchUserResultBinding) : RecyclerView.ViewHolder(binding.root) {
        private var avatarRequest: Disposable? = null

        fun cancelAvatar() {
            avatarRequest?.dispose()
            avatarRequest = null
        }

        fun bindSelection(selected: Boolean) {
            binding.root.isSelected = selected
        }

        fun bind(item: TwitchUserSummary, selected: Boolean) = with(binding) {
            bindSelection(selected)
            root.setOnClickListener { currentItem(item)?.let(onClick) }
            avatar.isClickable = true
            avatar.isFocusable = true
            avatar.contentDescription = root.context.getString(R.string.view_profile)
            avatar.setOnClickListener { currentItem(item)?.let(onAvatarClick) }
            name.text = item.displayName
            login.text = "@${item.login}"
            cancelAvatar()
            val avatarPlaceholder = avatar.personPlaceholder()
            avatar.setImageDrawable(avatarPlaceholder)
            item.profileImageUrl?.takeIf { it.isNotBlank() }?.let { url ->
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
            root.contentDescription = "${item.displayName}, @${item.login}"
        }
    }

    private companion object {
        const val SELECTION = "selection"
    }
}
