package com.github.andreyasadchy.xtra.ui.following.overview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.core.view.MarginLayoutParamsCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.ItemFeaturedStreamShelfBinding
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
import com.github.andreyasadchy.xtra.ui.common.thumbnailIdentity
import com.github.andreyasadchy.xtra.ui.common.parseStreamStartedAtMs
import com.github.andreyasadchy.xtra.ui.common.streamContentsSame
import com.github.andreyasadchy.xtra.ui.common.streamIdentity
import com.github.andreyasadchy.xtra.ui.common.streamThumbnailOnlyChanged
import com.github.andreyasadchy.xtra.ui.common.StreamThumbnailChangedPayload
import com.github.andreyasadchy.xtra.ui.tv.TvFocusHelper
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlin.math.abs
import kotlin.math.max

/** A centered, snap-to-card stream shelf used for the Discover hero. */
class FeaturedStreamShelfAdapter(
    private val fragment: Fragment,
    private val onStreamClick: (Stream) -> Unit,
) : ListAdapter<Stream, FeaturedStreamShelfAdapter.ViewHolder>(DIFF_CALLBACK) {

    private val thumbnailLoadScheduler = StreamThumbnailIdleScheduler()
    private val uptimeTicker = VisibleStreamUptimeTicker(fragment)

    private var snapHelper: PagerSnapHelper? = null
    private var initialCardPositioned = false
    private var originalPadding: IntArray? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).streamIdentity().hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemFeaturedStreamShelfBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        (holder.itemView.parent as? RecyclerView)?.let(::updateShelfLayout)
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

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        thumbnailLoadScheduler.attachTo(recyclerView)
        uptimeTicker.attach(recyclerView)
        originalPadding = intArrayOf(
            recyclerView.paddingLeft,
            recyclerView.paddingTop,
            recyclerView.paddingRight,
            recyclerView.paddingBottom,
        )
        recyclerView.clipToPadding = false
        val helper = PagerSnapHelper()
        helper.attachToRecyclerView(recyclerView)
        snapHelper = helper
        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.addOnLayoutChangeListener(layoutChangeListener)
        recyclerView.post { updateShelfLayout(recyclerView) }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        thumbnailLoadScheduler.detach()
        uptimeTicker.detach()
        recyclerView.removeOnScrollListener(scrollListener)
        recyclerView.removeOnLayoutChangeListener(layoutChangeListener)
        if (snapHelper != null) snapHelper?.attachToRecyclerView(null)
        snapHelper = null
        originalPadding?.let { padding ->
            recyclerView.setPadding(padding[0], padding[1], padding[2], padding[3])
        }
        originalPadding = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun scrollBy(recyclerView: RecyclerView, direction: Int) {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val currentPosition = layoutManager.findFirstCompletelyVisibleItemPosition()
            .takeIf { it != RecyclerView.NO_POSITION }
            ?: layoutManager.findFirstVisibleItemPosition()
        if (currentPosition == RecyclerView.NO_POSITION) return
        recyclerView.smoothScrollToPosition((currentPosition + direction).coerceIn(0, itemCount - 1))
    }

    fun centerInitialCard(recyclerView: RecyclerView) {
        if (initialCardPositioned || itemCount < 2) return
        recyclerView.post {
            if (!initialCardPositioned && itemCount > 1) {
                initialCardPositioned = true
                recyclerView.smoothScrollToPosition(1)
            }
        }
    }

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            transformChildren(recyclerView)
        }

        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            transformChildren(recyclerView)
        }
    }

    private val layoutChangeListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        updateShelfLayout(view as RecyclerView)
    }

    private fun updateShelfLayout(recyclerView: RecyclerView) {
        if (recyclerView.width <= 0) return
        val cardWidth = cardWidth(recyclerView)
        val sidePadding = ((recyclerView.width - cardWidth) / 2).coerceAtLeast(0)
        if (recyclerView.paddingLeft != sidePadding || recyclerView.paddingRight != sidePadding) {
            recyclerView.setPadding(sidePadding, recyclerView.paddingTop, sidePadding, recyclerView.paddingBottom)
        }
        repeat(recyclerView.childCount) { index ->
            val child = recyclerView.getChildAt(index)
            val params = (child.layoutParams as? RecyclerView.LayoutParams)
                ?: RecyclerView.LayoutParams(cardWidth, heroHeight(recyclerView, cardWidth))
            val targetHeight = heroHeight(recyclerView, cardWidth)
            val targetMarginEnd = -(48 * recyclerView.resources.displayMetrics.density).toInt()
            if (params.width != cardWidth || params.height != targetHeight ||
                MarginLayoutParamsCompat.getMarginEnd(params) != targetMarginEnd
            ) {
                params.width = cardWidth
                params.height = targetHeight
                MarginLayoutParamsCompat.setMarginEnd(params, targetMarginEnd)
                child.layoutParams = params
            }
        }
        transformChildren(recyclerView)
    }

    private fun transformChildren(recyclerView: RecyclerView) {
        val center = recyclerView.width / 2f
        val span = max(1f, cardWidth(recyclerView) * 0.9f)
        repeat(recyclerView.childCount) { index ->
            val child = recyclerView.getChildAt(index)
            val childCenter = (child.left + child.right) / 2f
            val distance = (abs(childCenter - center) / span).coerceIn(0f, 1f)
            val emphasis = 1f - distance
            child.scaleX = 0.88f + emphasis * 0.12f
            child.scaleY = 0.88f + emphasis * 0.12f
            child.alpha = 0.62f + emphasis * 0.38f
            child.translationZ = emphasis * 8f
        }
    }

    private fun cardWidth(recyclerView: RecyclerView): Int {
        val width = recyclerView.width
        val density = recyclerView.resources.displayMetrics.density
        val widthDp = width / density
        val fraction = if (widthDp >= 600f) 0.68f else 0.9f
        return (width * fraction).toInt().coerceAtLeast((280 * density).toInt().coerceAtMost(width))
    }

    private fun heroHeight(recyclerView: RecyclerView, cardWidth: Int): Int {
        val density = recyclerView.resources.displayMetrics.density
        val mediaWidth = cardWidth * 1.7f / 2.7f
        return max((220 * density).toInt(), (mediaWidth * 9f / 16f).toInt())
    }

    inner class ViewHolder(
        private val binding: ItemFeaturedStreamShelfBinding,
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
                viewers.visibility = if (viewers.text.isNullOrBlank()) View.GONE else View.VISIBLE

                title.text = presentation?.title ?: stream.title.orEmpty()
                title.visibility = if (title.text.isNullOrBlank()) View.GONE else View.VISIBLE
                channel.text = presentation?.username ?: stream.channelName.orEmpty()
                channel.visibility = if (channel.text.isNullOrBlank()) View.GONE else View.VISIBLE
                category.text = presentation?.gameName ?: stream.gameName.orEmpty()
                category.visibility = if (category.text.isNullOrBlank()) View.GONE else View.VISIBLE

                val tags = presentation?.tags ?: if (uiPreferences.showTags) stream.tags.orEmpty() else emptyList()
                val firstTag = tags.firstOrNull()?.trim()?.takeIf(String::isNotEmpty)
                tagOne.text = firstTag.orEmpty()
                tagOne.visibility = if (firstTag != null) View.VISIBLE else View.GONE

                if (stream.channelImage != null) {
                    avatar.visibility = View.VISIBLE
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
                    avatar.visibility = View.GONE
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
                val firstTag = presentation.tags.firstOrNull()?.trim()?.takeIf(String::isNotEmpty)
                tagOne.text = firstTag.orEmpty()
                tagOne.visibility = if (firstTag != null) View.VISIBLE else View.GONE
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
