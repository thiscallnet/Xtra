package com.github.andreyasadchy.xtra.ui.following.overview

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.ItemStreamShelfBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.common.loadStreamProfileImage
import com.github.andreyasadchy.xtra.ui.common.loadStreamThumbnail
import com.github.andreyasadchy.xtra.ui.common.streamContentsSame
import com.github.andreyasadchy.xtra.ui.common.streamIdentity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlin.time.Clock
import kotlin.time.Instant

class StreamShelfAdapter(
    private val onStreamClick: (Stream) -> Unit,
) : ListAdapter<Stream, StreamShelfAdapter.ViewHolder>(DIFF_CALLBACK) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).streamIdentity().hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemStreamShelfBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        (parent as? RecyclerView)?.let { ShelfCardSizing.apply(binding.root, it) }
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        (holder.itemView.parent as? RecyclerView)?.let { ShelfCardSizing.apply(holder.itemView, it) }
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        (holder.itemView.context.applicationContext as XtraApp).xtraModule.streamPreviewCoordinator
            .detachSurface(holder.previewSurface)
        super.onViewRecycled(holder)
    }

    private val layoutChangeListener = View.OnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
        if (right - left != oldRight - oldLeft) {
            val shelf = view as RecyclerView
            repeat(shelf.childCount) { index -> ShelfCardSizing.apply(shelf.getChildAt(index), shelf) }
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.addOnLayoutChangeListener(layoutChangeListener)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeOnLayoutChangeListener(layoutChangeListener)
        super.onDetachedFromRecyclerView(recyclerView)
    }

    inner class ViewHolder(
        private val binding: ItemStreamShelfBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        val previewSurface get() = binding.previewPlayerView

        fun bind(stream: Stream) {
            val context = binding.root.context
            with(binding) {
                (context.applicationContext as XtraApp).xtraModule.streamPreviewCoordinator
                    .detachSurface(previewPlayerView)
                root.setOnClickListener { onStreamClick(stream) }

                loadStreamThumbnail(context, thumbnail, stream)
                thumbnail.contentDescription = stream.title?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.live)

                liveBadge.text = context.getString(R.string.live)
                viewers.text = stream.viewerCount?.let { count ->
                    context.resources.getQuantityString(
                        R.plurals.viewers,
                        count,
                        TwitchApiHelper.formatCount(
                            count,
                            context.prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true),
                        ),
                    )
                }.orEmpty()
                viewers.visibility = if (viewers.text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE

                val uptimeText = if (context.prefs().getBoolean(C.UI_UPTIME, true)) {
                    stream.createdAt?.let { value ->
                        Instant.parseOrNull(value)?.let { createdAt ->
                            val uptime = Clock.System.now() - createdAt
                            if (uptime.isPositive()) DateUtils.formatElapsedTime(uptime.inWholeSeconds) else null
                        }
                    }
                } else null
                uptime.text = uptimeText.orEmpty()
                uptime.visibility = if (uptimeText.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE

                title.text = stream.title?.trim().orEmpty()
                title.visibility = if (title.text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
                channel.text = stream.channelName?.trim().orEmpty()
                channel.visibility = if (channel.text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
                category.text = stream.gameName?.trim().orEmpty()
                category.visibility = if (category.text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE

                val tags = if (context.prefs().getBoolean(C.UI_TAGS, true)) stream.tags.orEmpty().take(2) else emptyList()
                tagOne.text = tags.getOrNull(0).orEmpty()
                tagOne.visibility = if (tags.size > 0) android.view.View.VISIBLE else android.view.View.GONE
                tagTwo.text = tags.getOrNull(1).orEmpty()
                tagTwo.visibility = if (tags.size > 1) android.view.View.VISIBLE else android.view.View.GONE

                if (stream.channelImage != null) {
                    avatar.visibility = android.view.View.VISIBLE
                    loadStreamProfileImage(context, avatar, stream)
                } else {
                    avatar.visibility = android.view.View.INVISIBLE
                    avatar.setImageDrawable(null)
                    avatar.tag = null
                }
            }
        }
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Stream>() {
            override fun areItemsTheSame(oldItem: Stream, newItem: Stream): Boolean =
                oldItem.streamIdentity() == newItem.streamIdentity()

            override fun areContentsTheSame(oldItem: Stream, newItem: Stream): Boolean =
                streamContentsSame(oldItem, newItem)
        }
    }
}
