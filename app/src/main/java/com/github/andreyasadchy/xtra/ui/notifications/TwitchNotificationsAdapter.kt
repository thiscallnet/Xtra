package com.github.andreyasadchy.xtra.ui.notifications

import android.content.Intent
import android.net.Uri
import android.graphics.Typeface
import android.text.method.LinkMovementMethod
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
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchNotificationAction
import com.github.andreyasadchy.xtra.repository.isSafeTwitchUrl
import com.github.andreyasadchy.xtra.ui.inbox.relativeTime
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.LinkResolver
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.SoftBreakAddsNewLinePlugin

class TwitchNotificationsAdapter(
    private val onClick: (TwitchNotification) -> Unit,
    private val onAvatarClick: (TwitchNotification) -> Unit,
    private val onMarkRead: (TwitchNotification) -> Unit,
    private val onDismiss: (TwitchNotification) -> Unit,
) : RecyclerView.Adapter<TwitchNotificationsAdapter.ViewHolder>() {
    private var items: List<TwitchNotification> = emptyList()
    private lateinit var markwon: Markwon

    fun submitList(value: List<TwitchNotification>) {
        items = value
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        if (!::markwon.isInitialized) {
            markwon = Markwon.builder(parent.context)
                .usePlugin(object : AbstractMarkwonPlugin() {
                    override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                        builder.linkResolver(LinkResolver { view, link ->
                            val notification = view.tag as? TwitchNotification
                            if (notification?.action is TwitchNotificationAction.Drops) {
                                onClick(notification)
                            } else if (isSafeTwitchUrl(link)) {
                                runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                            }
                        })
                    }
                })
                .usePlugin(SoftBreakAddsNewLinePlugin.create())
                .build()
        }
        return ViewHolder(ItemTwitchNotificationBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    inner class ViewHolder(private val binding: ItemTwitchNotificationBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: TwitchNotification) = with(binding) {
            root.setOnClickListener { onClick(item) }
            body.tag = item
            markwon.setMarkdown(body, item.body)
            if (item.action is TwitchNotificationAction.Drops) {
                // Markdown links in Drop notifications often point at a channel or game.
                // They must not steal the row action and make the native Drops destination
                // appear unreliable.
                body.movementMethod = null
                body.setOnClickListener { onClick(item) }
            } else {
                body.movementMethod = LinkMovementMethod.getInstance()
                body.setOnClickListener(null)
            }
            body.setTypeface(null, if (item.isUnread) Typeface.BOLD else Typeface.NORMAL)
            timestamp.text = relativeTime(item.createdAt)
            unreadDot.visibility = if (item.isUnread) View.VISIBLE else View.GONE
            markRead.visibility = if (item.isUnread) View.VISIBLE else View.GONE
            markRead.setOnClickListener { onMarkRead(item) }
            dismiss.visibility = if (item.canDismiss) View.VISIBLE else View.GONE
            dismiss.setOnClickListener { onDismiss(item) }
            val isChannel = item.action is TwitchNotificationAction.Channel
            image.isClickable = isChannel
            image.isFocusable = isChannel
            image.contentDescription = if (isChannel) image.context.getString(R.string.view_profile) else null
            image.setOnClickListener { if (isChannel) onAvatarClick(item) }
            image.setImageResource(R.drawable.ic_twitch_notifications)
            item.imageUrl?.let { url ->
                image.context.imageLoader.enqueue(ImageRequest.Builder(image.context).data(url).crossfade(true).transformations(CircleCropTransformation()).target(image).build())
            }
            root.contentDescription = buildString {
                if (item.isUnread) append(root.context.getString(R.string.unread)).append(", ")
                append(body.text)
                if (timestamp.text.isNotBlank()) append(", ").append(timestamp.text)
            }
        }
    }
}
