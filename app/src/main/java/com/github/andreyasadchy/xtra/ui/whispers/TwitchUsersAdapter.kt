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
import com.github.andreyasadchy.xtra.databinding.ItemTwitchUserResultBinding
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchUserSummary

class TwitchUsersAdapter(
    private val onClick: (TwitchUserSummary) -> Unit,
    private val onAvatarClick: (TwitchUserSummary) -> Unit,
) : RecyclerView.Adapter<TwitchUsersAdapter.ViewHolder>() {
    private var items: List<TwitchUserSummary> = emptyList()
    fun submitList(value: List<TwitchUserSummary>) { items = value; notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemTwitchUserResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    inner class ViewHolder(private val binding: ItemTwitchUserResultBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: TwitchUserSummary) = with(binding) {
            root.setOnClickListener { onClick(item) }
            avatar.isClickable = true
            avatar.isFocusable = true
            avatar.contentDescription = root.context.getString(R.string.view_profile)
            avatar.setOnClickListener { onAvatarClick(item) }
            name.text = item.displayName
            login.text = "@${item.login}"
            avatar.setImageResource(R.drawable.baseline_person_black_24)
            item.profileImageUrl?.let { url ->
                avatar.context.imageLoader.enqueue(ImageRequest.Builder(avatar.context).data(url).crossfade(true).transformations(CircleCropTransformation()).target(avatar).build())
            }
            root.contentDescription = "${item.displayName}, @${item.login}"
        }
    }
}
