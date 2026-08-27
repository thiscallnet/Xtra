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
import com.github.andreyasadchy.xtra.ui.common.prepareStreamProfileImage
import com.github.andreyasadchy.xtra.ui.common.prepareStreamThumbnailImage
import com.github.andreyasadchy.xtra.ui.common.restoreWarmStreamThumbnail
import com.github.andreyasadchy.xtra.ui.common.FeedImageRequestBag
import com.github.andreyasadchy.xtra.ui.common.FeedImageRequestOwner
import com.github.andreyasadchy.xtra.ui.common.FeedUiPreferencesStore
import com.github.andreyasadchy.xtra.ui.common.StreamThumbnailIdleScheduler
import com.github.andreyasadchy.xtra.ui.common.thumbnailIdentity
import com.github.andreyasadchy.xtra.ui.common.streamContentsSame
import com.github.andreyasadchy.xtra.ui.common.streamIdentity
import com.github.andreyasadchy.xtra.ui.common.streamThumbnailOnlyChanged
import com.github.andreyasadchy.xtra.ui.common.StreamThumbnailChangedPayload
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlin.time.Clock
import kotlin.time.Instant

class StreamShelfAdapter(
    private val onStreamClick: (Stream) -> Unit,
) : ListAdapter<Stream, StreamShelfAdapter.ViewHolder>(DIFF_CALLBACK) {

    private val thumbnailLoadScheduler = StreamThumbnailIdleScheduler()

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
        holder.beginImageBind(getItem(position))
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it === StreamThumbnailChangedPayload }) {
            holder.beginImageBind(getItem(position))
            holder.bindThumbnail(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        (holder.itemView.context.applicationContext as XtraApp).xtraModule.streamPreviewCoordinator
            .detachSurface(holder.previewSurface)
        holder.boundPreviewIdentity = null
        thumbnailLoadScheduler.clear(holder)
        holder.cancelImageWork()
        super.onViewRecycled(holder)
    }

    private val layoutChangeListener = View.OnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
        if (right - left != oldRight - oldLeft) {
            val shelf = view as RecyclerView
            shelf.post { applyCardSizing(shelf) }
        }
    }

    private fun applyCardSizing(shelf: RecyclerView) {
        repeat(shelf.childCount) { index -> ShelfCardSizing.apply(shelf.getChildAt(index), shelf) }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        thumbnailLoadScheduler.attachTo(recyclerView)
        recyclerView.addOnLayoutChangeListener(layoutChangeListener)
        recyclerView.post { applyCardSizing(recyclerView) }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        thumbnailLoadScheduler.detach()
        recyclerView.removeOnLayoutChangeListener(layoutChangeListener)
        super.onDetachedFromRecyclerView(recyclerView)
    }

    inner class ViewHolder(
        private val binding: ItemStreamShelfBinding,
    ) : RecyclerView.ViewHolder(binding.root), FeedImageRequestOwner {
        val previewSurface get() = binding.previewHost
        var boundPreviewIdentity: String? = null
        private val imageRequests = FeedImageRequestBag()
        private var boundImageIdentity: String? = null
        private var boundThumbnailKey: String? = null
        private var boundStream: Stream? = null

        init {
            binding.root.setOnClickListener { boundStream?.let(onStreamClick) }
        }

        fun beginImageBind(stream: Stream?) {
            thumbnailLoadScheduler.clear(this)
            imageRequests.cancel()
            boundImageIdentity = stream?.streamIdentity()
            boundThumbnailKey = null
        }

        override fun cancelImageRequests() {
            imageRequests.cancel()
        }

        override fun pauseImageRequests() {
            imageRequests.cancel(preserveRegistrations = true)
        }

        fun cancelImageWork() {
            cancelImageRequests()
            boundImageIdentity = null
            boundThumbnailKey = null
            boundStream = null
        }

        fun bindThumbnail(stream: Stream?) {
            val item = stream ?: return
            prepareStreamThumbnailImage(binding.thumbnail, item)
            restoreWarmStreamThumbnail(item, binding.thumbnail)
            val key = "${item.thumbnailIdentity()}|generation=${item.thumbnailGeneration}"
            boundThumbnailKey = key
            thumbnailLoadScheduler.runOrDefer(this@ViewHolder, binding.thumbnail) {
                if (!binding.root.isAttachedToWindow ||
                    boundImageIdentity != item.streamIdentity() ||
                    boundThumbnailKey != key
                ) return@runOrDefer
                loadStreamThumbnail(
                    context = binding.root.context,
                    imageView = binding.thumbnail,
                    stream = item,
                    scheduleFreshRequest = { freshRequest ->
                        thumbnailLoadScheduler.runOrDefer(this@ViewHolder, binding.thumbnail, freshRequest)
                    },
                )
                    ?.let { imageRequests.replace(binding.thumbnail, it) }
            }
        }

        fun bind(stream: Stream) {
            val context = binding.root.context
            val uiPreferences = FeedUiPreferencesStore.current(context)
            boundStream = stream
            val nextPreviewIdentity = stream.streamIdentity()
            if (boundPreviewIdentity != nextPreviewIdentity) {
                (context.applicationContext as XtraApp).xtraModule.streamPreviewCoordinator
                    .detachSurface(previewSurface)
                boundPreviewIdentity = nextPreviewIdentity
            }
            with(binding) {
                bindThumbnail(stream)
                thumbnail.contentDescription = stream.title?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.live)

                liveBadge.text = context.getString(R.string.live)
                viewers.text = stream.viewerCount?.let { count ->
                    context.resources.getQuantityString(
                        R.plurals.viewers,
                        count,
                        TwitchApiHelper.formatCount(
                            count,
                            uiPreferences.truncateViewCount,
                        ),
                    )
                }.orEmpty()
                viewers.visibility = if (viewers.text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE

                val uptimeText = if (uiPreferences.showUptime) {
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

                val tags = if (uiPreferences.showTags) stream.tags.orEmpty().take(2) else emptyList()
                tagOne.text = tags.getOrNull(0).orEmpty()
                tagOne.visibility = if (tags.size > 0) android.view.View.VISIBLE else android.view.View.GONE
                tagTwo.text = tags.getOrNull(1).orEmpty()
                tagTwo.visibility = if (tags.size > 1) android.view.View.VISIBLE else android.view.View.GONE

                if (stream.channelImage != null) {
                    avatar.visibility = android.view.View.VISIBLE
                    prepareStreamProfileImage(avatar, stream)
                    thumbnailLoadScheduler.runOrDefer(this@ViewHolder, avatar) {
                        if (binding.root.isAttachedToWindow && boundImageIdentity == stream.streamIdentity()) {
                            loadStreamProfileImage(context, avatar, stream)?.let {
                                imageRequests.replace(binding.avatar, it)
                            }
                        }
                    }
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

            override fun getChangePayload(oldItem: Stream, newItem: Stream): Any? =
                if (streamThumbnailOnlyChanged(oldItem, newItem)) StreamThumbnailChangedPayload else null
        }
    }
}
