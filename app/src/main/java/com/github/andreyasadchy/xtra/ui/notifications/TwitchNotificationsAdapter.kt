package com.github.andreyasadchy.xtra.ui.notifications

import android.content.Intent
import android.net.Uri
import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
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
import java.lang.ref.WeakReference

class TwitchNotificationsAdapter(
    private val onClick: (TwitchNotification) -> Unit,
    private val onAvatarClick: (TwitchNotification) -> Unit,
    private val onMarkRead: (TwitchNotification) -> Unit,
    private val onDismiss: (TwitchNotification) -> Unit,
) : RecyclerView.Adapter<TwitchNotificationsAdapter.ViewHolder>() {
    private val differ = AsyncListDiffer(this, object : DiffUtil.ItemCallback<TwitchNotification>() {
        override fun areItemsTheSame(oldItem: TwitchNotification, newItem: TwitchNotification) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: TwitchNotification, newItem: TwitchNotification) = oldItem == newItem
        override fun getChangePayload(oldItem: TwitchNotification, newItem: TwitchNotification): Any? =
            if (oldItem.copy(isUnread = newItem.isUnread, canDismiss = newItem.canDismiss, createdAt = newItem.createdAt) == newItem) STATE else null
    })
    private var submittedItems: List<TwitchNotification> = emptyList()
    private var recyclerView = WeakReference<RecyclerView>(null)
    private lateinit var markwon: Markwon

    fun submitList(value: List<TwitchNotification>) {
        if (submittedItems == value) return
        submittedItems = value.toList()
        differ.submitList(submittedItems) {
            // Refresh visible relative labels without formatting the entire list,
            // reparsing Markdown or restarting images on unchanged cards.
            refreshTimes()
        }
    }

    private fun currentItem(item: TwitchNotification): TwitchNotification? =
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        if (!::markwon.isInitialized) {
            markwon = Markwon.builder(parent.context)
                .usePlugin(object : AbstractMarkwonPlugin() {
                    override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                        builder.linkResolver(LinkResolver { view, link ->
                            val notification = (view.tag as? TwitchNotification)?.let(::currentItem) ?: return@LinkResolver
                            if (notification.action is TwitchNotificationAction.Drops) {
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

    override fun getItemCount() = differ.currentList.size
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = differ.currentList[position]
        holder.bind(item, relativeTime(item.createdAt))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        val item = differ.currentList[position]
        val time = relativeTime(item.createdAt)
        if (payloads.isNotEmpty() && payloads.all { it == STATE || it == TIME }) {
            if (STATE in payloads) holder.bindState(item, time) else holder.bindTime(item, time)
        } else holder.bind(item, time)
    }

    inner class ViewHolder(private val binding: ItemTwitchNotificationBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bindTime(item: TwitchNotification, time: String) = with(binding) {
            if (timestamp.text.toString() != time) {
                timestamp.text = time
                bindDescription(item)
            }
        }

        private fun bindDescription(item: TwitchNotification) = with(binding) {
            root.contentDescription = buildString {
                if (item.isUnread) append(root.context.getString(R.string.unread)).append(", ")
                append(body.text)
                if (timestamp.text.isNotBlank()) append(", ").append(timestamp.text)
            }
        }

        fun bindState(item: TwitchNotification, time: String) = with(binding) {
            root.setOnClickListener { currentItem(item)?.let(onClick) }
            body.tag = item
            if (item.action is TwitchNotificationAction.Drops) {
                // Markdown links in Drop notifications often point at a channel or game.
                // They must not steal the row action and make the native Drops destination
                // appear unreliable.
                body.movementMethod = null
                body.setOnClickListener { currentItem(item)?.let(onClick) }
            } else {
                body.movementMethod = LinkMovementMethod.getInstance()
                body.setOnClickListener(null)
            }
            body.setTypeface(null, if (item.isUnread) Typeface.BOLD else Typeface.NORMAL)
            bindTime(item, time)
            unreadDot.visibility = if (item.isUnread) View.VISIBLE else View.GONE
            markRead.visibility = if (item.isUnread) View.VISIBLE else View.GONE
            markRead.setOnClickListener { currentItem(item)?.takeIf { it.isUnread }?.let(onMarkRead) }
            dismiss.visibility = if (item.canDismiss) View.VISIBLE else View.GONE
            dismiss.setOnClickListener { currentItem(item)?.takeIf { it.canDismiss }?.let(onDismiss) }
            val isChannel = item.action is TwitchNotificationAction.Channel
            image.setOnClickListener(if (isChannel) View.OnClickListener {
                currentItem(item)?.takeIf { it.action is TwitchNotificationAction.Channel }?.let(onAvatarClick)
            } else null)
            image.isClickable = isChannel
            image.isFocusable = isChannel
            bindDescription(item)
        }

        fun bind(item: TwitchNotification, time: String) = with(binding) {
            markwon.setMarkdown(body, item.body)
            bindState(item, time)
            val channel = item.action as? TwitchNotificationAction.Channel
            val isChannel = channel != null
            image.importantForAccessibility = if (isChannel) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val channelName = channel?.displayName?.takeIf { it.isNotBlank() } ?: channel?.login?.takeIf { it.isNotBlank() }
            image.contentDescription = if (isChannel) {
                if (channelName != null) image.context.getString(R.string.player_open_channel, channelName)
                else image.context.getString(R.string.view_profile)
            } else null
            image.setImageResource(R.drawable.ic_twitch_notifications)
            item.imageUrl?.let { url ->
                image.context.imageLoader.enqueue(ImageRequest.Builder(image.context).data(url).crossfade(true).transformations(CircleCropTransformation()).target(image).build())
            }
        }
    }

    private companion object {
        const val STATE = "state"
        const val TIME = "time"
    }
}
