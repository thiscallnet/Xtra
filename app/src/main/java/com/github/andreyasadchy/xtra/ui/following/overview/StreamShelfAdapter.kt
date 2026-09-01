package com.github.andreyasadchy.xtra.ui.following.overview

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
import com.github.andreyasadchy.xtra.ui.common.restoreWarmStreamProfileImage
import com.github.andreyasadchy.xtra.ui.common.FeedImageRequestBag
import com.github.andreyasadchy.xtra.ui.common.FeedImageRequestOwner
import com.github.andreyasadchy.xtra.ui.common.FeedUiPreferencesStore
import com.github.andreyasadchy.xtra.ui.common.StreamCardPresentationCache
import com.github.andreyasadchy.xtra.ui.common.StreamThumbnailIdleScheduler
import com.github.andreyasadchy.xtra.ui.common.StreamUptimeViewHolder
import com.github.andreyasadchy.xtra.ui.common.VisibleStreamUptimeTicker
import com.github.andreyasadchy.xtra.ui.common.formatStreamUptime
import com.github.andreyasadchy.xtra.ui.common.parseStreamStartedAtMs
import com.github.andreyasadchy.xtra.ui.common.thumbnailIdentity
import com.github.andreyasadchy.xtra.ui.common.streamContentsSame
import com.github.andreyasadchy.xtra.ui.common.streamIdentity
import com.github.andreyasadchy.xtra.ui.common.streamThumbnailOnlyChanged
import com.github.andreyasadchy.xtra.ui.common.StreamThumbnailChangedPayload
import com.github.andreyasadchy.xtra.ui.tv.TvFocusHelper

class StreamShelfAdapter(
    private val fragment: androidx.fragment.app.Fragment,
    private val onStreamClick: (Stream) -> Unit,
) : ListAdapter<Stream, StreamShelfAdapter.ViewHolder>(DIFF_CALLBACK) {

    private val thumbnailLoadScheduler = StreamThumbnailIdleScheduler()
    private val uptimeTicker = VisibleStreamUptimeTicker(fragment)

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
        uptimeTicker.attach(recyclerView)
        recyclerView.addOnLayoutChangeListener(layoutChangeListener)
        recyclerView.post { applyCardSizing(recyclerView) }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        thumbnailLoadScheduler.detach()
        uptimeTicker.detach()
        recyclerView.removeOnLayoutChangeListener(layoutChangeListener)
        super.onDetachedFromRecyclerView(recyclerView)
    }

    inner class ViewHolder(
        private val binding: ItemStreamShelfBinding,
    ) : RecyclerView.ViewHolder(binding.root), FeedImageRequestOwner, StreamUptimeViewHolder {
        val previewSurface get() = binding.previewHost
        var boundPreviewIdentity: String? = null
        private val imageRequests = FeedImageRequestBag()
        private var boundImageIdentity: String? = null
        private var boundThumbnailKey: String? = null
        private var boundStream: Stream? = null
        private var uptimeStartedAtMs: Long? = null
        private var uptimeEnabled = false
        private var lastRenderedUptimeSecond = Long.MIN_VALUE

        init {
            binding.root.setOnClickListener { boundStream?.let(onStreamClick) }
            TvFocusHelper.install(binding.root)
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
            clearUptime()
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
            val presentation = StreamCardPresentationCache.get(stream, uiPreferences)
            boundStream = stream
            uptimeEnabled = uiPreferences.showUptime
            uptimeStartedAtMs = if (uptimeEnabled) parseStreamStartedAtMs(stream.createdAt) else null
            lastRenderedUptimeSecond = Long.MIN_VALUE
            updateUptime(System.currentTimeMillis())
            if (presentation == null) {
                StreamCardPresentationCache.request(context, stream, uiPreferences) {
                    if (boundStream === stream && binding.root.isAttachedToWindow) applyPresentation(it)
                }
            }
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
                viewers.text = presentation?.viewerLabel ?: stream.viewerCount?.toString().orEmpty()
                viewers.visibility = if (viewers.text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE

                title.text = presentation?.title ?: stream.title.orEmpty()
                title.visibility = if (title.text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
                channel.text = presentation?.username ?: stream.channelName.orEmpty()
                channel.visibility = if (channel.text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
                category.text = presentation?.gameName ?: stream.gameName.orEmpty()
                category.visibility = if (category.text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE

                val tags = presentation?.tags ?: if (uiPreferences.showTags) stream.tags.orEmpty() else emptyList()
                val firstTag = tags.getOrNull(0)?.trim()?.takeIf(String::isNotEmpty)
                val secondTag = tags.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty)
                tagOne.text = firstTag.orEmpty()
                tagOne.visibility = if (firstTag != null) android.view.View.VISIBLE else android.view.View.GONE
                tagTwo.text = secondTag.orEmpty()
                tagTwo.visibility = if (secondTag != null) android.view.View.VISIBLE else android.view.View.GONE

                if (stream.channelImage != null) {
                    avatar.visibility = android.view.View.VISIBLE
                    prepareStreamProfileImage(avatar, stream)
                    val profileRestored = restoreWarmStreamProfileImage(context, avatar, stream)
                    if (!profileRestored) thumbnailLoadScheduler.runOrDefer(this@ViewHolder, avatar) {
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

        private fun applyPresentation(presentation: com.github.andreyasadchy.xtra.ui.common.StreamCardPresentation) {
            if (boundStream == null) return
            with(binding) {
                viewers.text = presentation.viewerLabel.orEmpty()
                viewers.visibility = if (viewers.text.isNullOrBlank()) View.GONE else View.VISIBLE
                title.text = presentation.title.orEmpty()
                title.visibility = if (title.text.isNullOrBlank()) View.GONE else View.VISIBLE
                channel.text = presentation.username.orEmpty()
                channel.visibility = if (channel.text.isNullOrBlank()) View.GONE else View.VISIBLE
                category.text = presentation.gameName.orEmpty()
                category.visibility = if (category.text.isNullOrBlank()) View.GONE else View.VISIBLE
                val tags = presentation.tags.take(2)
                tagOne.text = tags.firstOrNull()?.trim().orEmpty()
                tagOne.visibility = if (tagOne.text.isNullOrBlank()) View.GONE else View.VISIBLE
                tagTwo.text = tags.getOrNull(1)?.trim().orEmpty()
                tagTwo.visibility = if (tagTwo.text.isNullOrBlank()) View.GONE else View.VISIBLE
            }
        }

        override fun updateUptime(nowMs: Long) {
            val startedAtMs = uptimeStartedAtMs
            if (!uptimeEnabled || startedAtMs == null || nowMs <= startedAtMs) {
                lastRenderedUptimeSecond = Long.MIN_VALUE
                if (binding.uptime.visibility != View.GONE) binding.uptime.visibility = View.GONE
                return
            }

            val elapsedSeconds = (nowMs - startedAtMs) / 1000L
            if (elapsedSeconds == lastRenderedUptimeSecond) return

            lastRenderedUptimeSecond = elapsedSeconds
            val text = formatStreamUptime(startedAtMs, nowMs) ?: return
            if (binding.uptime.text.toString() != text) binding.uptime.text = text
            if (binding.uptime.visibility != View.VISIBLE) binding.uptime.visibility = View.VISIBLE
        }

        private fun clearUptime() {
            uptimeEnabled = false
            uptimeStartedAtMs = null
            lastRenderedUptimeSecond = Long.MIN_VALUE
            binding.uptime.visibility = View.GONE
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
