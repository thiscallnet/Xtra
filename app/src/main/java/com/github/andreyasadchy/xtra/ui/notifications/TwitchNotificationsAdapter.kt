package com.github.andreyasadchy.xtra.ui.notifications

import android.graphics.Typeface
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
import com.github.andreyasadchy.xtra.databinding.ItemTwitchNotificationBinding
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchNotification
import com.github.andreyasadchy.xtra.ui.inbox.relativeTime

class TwitchNotificationsAdapter(
    private val onClick: (TwitchNotification) -> Unit,
    private val onDismiss: (TwitchNotification) -> Unit,
) : RecyclerView.Adapter<TwitchNotificationsAdapter.ViewHolder>() {
    private var items: List<TwitchNotification> = emptyList()

    fun submitList(value: List<TwitchNotification>) {
        items = value
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ItemTwitchNotificationBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    inner class ViewHolder(private val binding: ItemTwitchNotificationBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: TwitchNotification) = with(binding) {
            root.setOnClickListener { onClick(item) }
            body.text = item.body
            body.setTypeface(null, if (item.isUnread) Typeface.BOLD else Typeface.NORMAL)
            timestamp.text = relativeTime(item.createdAt)
            unreadDot.visibility = if (item.isUnread) View.VISIBLE else View.GONE
            dismiss.visibility = if (item.canDismiss) View.VISIBLE else View.GONE
            dismiss.setOnClickListener { onDismiss(item) }
            image.setImageResource(R.drawable.ic_twitch_notifications)
            item.imageUrl?.let { url ->
                image.context.imageLoader.enqueue(ImageRequest.Builder(image.context).data(url).crossfade(true).transformations(CircleCropTransformation()).target(image).build())
            }
            root.contentDescription = buildString {
                if (item.isUnread) append(root.context.getString(R.string.unread)).append(", ")
                append(item.body)
                if (timestamp.text.isNotBlank()) append(", ").append(timestamp.text)
            }
        }
    }
}
