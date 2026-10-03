package com.github.andreyasadchy.xtra.ui.following.overview

import android.content.res.Configuration
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.ItemStreamShelfBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.common.StreamCardUptimeBinder
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.common.loadStreamProfileImage
import com.github.andreyasadchy.xtra.ui.common.loadStreamThumbnail
import com.github.andreyasadchy.xtra.ui.common.prepareStreamProfileImage
import com.github.andreyasadchy.xtra.ui.common.prepareStreamThumbnailImage
import com.github.andreyasadchy.xtra.ui.common.restoreWarmStreamThumbnail
import com.github.andreyasadchy.xtra.ui.common.restoreWarmStreamProfileImage
import com.github.andreyasadchy.xtra.ui.common.FeedImageRequestBag
import com.github.andreyasadchy.xtra.ui.common.FeedImageRequestOwner
import com.github.andreyasadchy.xtra.ui.common.FeedUiPreferencesStore
import com.github.andreyasadchy.xtra.ui.common.MAX_STREAM_TAGS
import com.github.andreyasadchy.xtra.ui.common.StreamTagViews
import com.github.andreyasadchy.xtra.ui.common.StreamCardPresentationCache
import com.github.andreyasadchy.xtra.ui.common.setVerifiedPartnerName
import com.github.andreyasadchy.xtra.ui.common.StreamDropsBadgeBinder
import com.github.andreyasadchy.xtra.ui.common.StreamThumbnailIdleScheduler
import com.github.andreyasadchy.xtra.ui.common.bindStreamTags
import com.github.andreyasadchy.xtra.ui.common.thumbnailIdentity
import com.github.andreyasadchy.xtra.ui.common.streamContentsSame
import com.github.andreyasadchy.xtra.ui.common.streamIdentity
import com.github.andreyasadchy.xtra.ui.common.streamThumbnailOnlyChanged
import com.github.andreyasadchy.xtra.ui.common.StreamThumbnailChangedPayload
import com.github.andreyasadchy.xtra.ui.common.ExpressiveShapeStyling
import com.github.andreyasadchy.xtra.ui.common.createStreamTagViews
import com.github.andreyasadchy.xtra.ui.common.usesExpressiveInterface
import androidx.constraintlayout.widget.ConstraintLayout
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.tv.TvFocusHelper
import com.github.andreyasadchy.xtra.ui.drops.StreamDropsBottomSheet

class StreamShelfAdapter(
    private val fragment: androidx.fragment.app.Fragment,
    private val onStreamClick: (Stream) -> Unit,
    private val onTagClick: (String) -> Unit,
    private val compactOverviewCards: Boolean,
) : ListAdapter<Stream, StreamShelfAdapter.ViewHolder>(DIFF_CALLBACK) {

    private val thumbnailLoadScheduler = StreamThumbnailIdleScheduler()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).streamIdentity().hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val expressive = parent.context.usesExpressiveInterface()
        val layout = when {
            expressive && compactOverviewCards -> R.layout.item_stream_shelf_compact
            expressive -> R.layout.item_stream_shelf
            else -> R.layout.item_stream_shelf_classic
        }
        val itemView = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        val binding = ItemStreamShelfBinding.bind(itemView)
        if (expressive) ExpressiveShapeStyling.applyStreamShelfItem(itemView)
        (parent as? RecyclerView)?.let {
            if (expressive && compactOverviewCards) applyOverviewCardSizing(binding.root, it)
            else ShelfCardSizing.apply(binding.root, it)
        }
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.beginImageBind(getItem(position))
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it === StreamThumbnailChangedPayload }) {
            val stream = getItem(position)
            holder.beginThumbnailRefresh(stream)
            holder.bindThumbnail(stream)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)
        holder.resumeImageWork()
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
        repeat(shelf.childCount) { index ->
            val item = shelf.getChildAt(index)
            if (compactOverviewCards && item.context.usesExpressiveInterface()) {
                applyOverviewCardSizing(item, shelf)
            } else {
                ShelfCardSizing.apply(item, shelf)
            }
        }
    }

    private fun applyOverviewCardSizing(item: View, shelf: RecyclerView) {
        val params = item.layoutParams ?: RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.WRAP_CONTENT,
            RecyclerView.LayoutParams.WRAP_CONTENT,
        )
        val measuredWidth = shelf.width.takeIf { it > 0 }
            ?: shelf.rootView.width.takeIf { it > 0 }
            ?: shelf.resources.displayMetrics.widthPixels
        val availableWidth = (measuredWidth - shelf.paddingLeft - shelf.paddingRight).coerceAtLeast(1)
        val density = shelf.resources.displayMetrics.density
        val widthDp = availableWidth / density
        val narrowPhone = shelf.resources.configuration.smallestScreenWidthDp < 600
        val portrait = shelf.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val cardWidth = when {
            narrowPhone && portrait ->
                (availableWidth * 0.95f).toInt().coerceAtMost(availableWidth)
            narrowPhone -> ((widthDp / 2f).coerceIn(260f, 360f) * density).toInt()
            widthDp < 840f -> ((widthDp / 1.9f).coerceIn(290f, 360f) * density).toInt()
            else -> ((widthDp / 2f).coerceIn(320f, 380f) * density).toInt()
        }
        if (params.width != cardWidth) {
            params.width = cardWidth
            item.layoutParams = params
        }
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
        private var boundImageStream: Stream? = null
        private var boundThumbnailKey: String? = null
        private var boundStream: Stream? = null
        private var boundTags: List<String> = emptyList()
        private val uptimeBinder = StreamCardUptimeBinder(binding.uptime)
        private var lastCompactLandscapeDensity: Boolean? = null
        private val compactCardStatus = binding.root.findViewById<android.widget.LinearLayout>(R.id.streamCardStatus)
        private val compactChannelIdentity = binding.root.findViewById<View>(R.id.channelIdentity)
        private val compactTitleScroll = binding.root.findViewById<com.github.andreyasadchy.xtra.ui.view.DirectionalHorizontalScrollView>(R.id.titleScroll)
        private val compactTagsScroll = binding.root.findViewById<com.github.andreyasadchy.xtra.ui.view.DirectionalHorizontalScrollView>(R.id.tagsScroll)
        private val compactTagTargets = listOfNotNull(
            binding.root.findViewById<android.widget.FrameLayout>(R.id.tagOneTarget),
            binding.root.findViewById<android.widget.FrameLayout>(R.id.tagTwoTarget),
            binding.root.findViewById<android.widget.FrameLayout>(R.id.tagThreeTarget),
        ).toMutableList()
        private val compactTagViews = mutableListOf(binding.tagOne, binding.tagTwo, binding.tagThree)
        private val horizontalTagViews: StreamTagViews? = if (compactTagTargets.isEmpty()) {
            compactTagsScroll?.let { scroll ->
                val row = binding.root.findViewById<android.widget.LinearLayout>(R.id.tags)
                createStreamTagViews(scroll, row, compactTagViews) {
                    if (binding.root.context.usesExpressiveInterface()) {
                        ExpressiveShapeStyling.applyStreamShelfTagChip(it)
                    }
                }
            }
        } else {
            null
        }
        private val dropsBadgeBinder = StreamDropsBadgeBinder(binding.dropsBadge) { stream ->
            StreamDropsBottomSheet.show(fragment, stream)
        }

        init {
            binding.root.setOnClickListener { boundStream?.let(onStreamClick) }
            if (compactChannelIdentity != null) {
                compactChannelIdentity.setOnClickListener { boundStream?.let(::openChannel) }
                binding.channel.setOnClickListener { boundStream?.let(::openChannel) }
                binding.channel.isClickable = true
                binding.channel.isFocusable = true
                binding.channel.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                binding.title.setOnClickListener { boundStream?.let(onStreamClick) }
            } else {
                binding.avatar.setOnClickListener {
                    boundStream?.let(::openChannel)
                }
                binding.channel.setOnClickListener { boundStream?.let(::openChannel) }
            }
            binding.category.setOnClickListener { boundStream?.let(::openGame) }
            if (horizontalTagViews != null) {
                horizontalTagViews.setOnTagClickListener(onTagClick)
            } else if (compactTagTargets.isEmpty()) {
                binding.tagOne.setOnClickListener { boundTags.getOrNull(0)?.let(onTagClick) }
                binding.tagTwo.setOnClickListener { boundTags.getOrNull(1)?.let(onTagClick) }
                binding.tagThree.setOnClickListener { boundTags.getOrNull(2)?.let(onTagClick) }
            } else {
                compactTagTargets.forEachIndexed { index, target ->
                    target.setOnClickListener { boundTags.getOrNull(index)?.let(onTagClick) }
                }
            }
            binding.root.addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    view.post { configureCompactCardLayout() }
                }
            }
            TvFocusHelper.install(binding.root)
        }

        fun beginImageBind(stream: Stream?) {
            thumbnailLoadScheduler.clear(this)
            imageRequests.cancel()
            boundImageStream = stream
            boundImageIdentity = stream?.streamIdentity()
            boundThumbnailKey = null
        }

        fun beginThumbnailRefresh(stream: Stream?) {
            boundImageStream = stream
            boundImageIdentity = stream?.streamIdentity()
            imageRequests.cancel(binding.thumbnail)
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
            boundImageStream = null
            boundImageIdentity = null
            boundThumbnailKey = null
            boundStream = null
            boundTags = emptyList()
            compactTitleScroll?.scrollTo(0, 0)
            compactTagsScroll?.scrollTo(0, 0)
            dropsBadgeBinder.clear()
            uptimeBinder.clear()
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

        fun resumeImageWork() {
            val stream = boundImageStream ?: return
            if (boundImageIdentity != stream.streamIdentity()) return
            bindThumbnail(stream)
            bindProfileImage(stream)
        }

        private fun bindProfileImage(stream: Stream) {
            val context = binding.root.context
            if (stream.channelImage != null) {
                binding.avatar.visibility = View.VISIBLE
                prepareStreamProfileImage(binding.avatar, stream)
                val profileRestored = restoreWarmStreamProfileImage(context, binding.avatar, stream)
                if (!profileRestored) thumbnailLoadScheduler.runOrDefer(this@ViewHolder, binding.avatar) {
                    if (binding.root.isAttachedToWindow && boundImageIdentity == stream.streamIdentity()) {
                        loadStreamProfileImage(context, binding.avatar, stream)?.let {
                            imageRequests.replace(binding.avatar, it)
                        }
                    }
                }
            } else {
                binding.avatar.visibility = View.INVISIBLE
                binding.avatar.setImageDrawable(null)
                binding.avatar.tag = null
            }
        }

        fun bind(stream: Stream) {
            val context = binding.root.context
            configureCompactCardLayout()
            dropsBadgeBinder.bind(stream)
            compactCardStatus?.visibility =
                if (binding.dropsBadge.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            val uiPreferences = FeedUiPreferencesStore.current(context)
            val presentation = StreamCardPresentationCache.get(stream, uiPreferences)
            boundStream = stream
            uptimeBinder.bind(stream.createdAt, uiPreferences.showUptime)
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
                title.maxLines = if (compactTitleScroll != null) 1 else titleMaxLines
                bindThumbnail(stream)
                thumbnail.contentDescription = stream.title?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.live)

                liveBadge.text = context.getString(R.string.live)
                viewers.text = presentation?.viewerLabel ?: stream.viewerCount?.toString().orEmpty()
                viewers.visibility = if (viewers.text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE

                title.text = presentation?.title ?: stream.title.orEmpty()
                title.visibility = if (title.text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
                compactTitleScroll?.scrollTo(0, 0)
                channel.setVerifiedPartnerName(
                    presentation?.username ?: stream.channelName.orEmpty(),
                    stream.broadcasterType,
                )
                channel.visibility = if (channel.text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
                compactChannelIdentity?.contentDescription = channel.text?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.account_view_channel)
                category.text = presentation?.gameName ?: stream.gameName.orEmpty()
                category.visibility = if (category.text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE

                val tags = presentation?.tags ?: if (uiPreferences.showTags) stream.tags.orEmpty() else emptyList()
                bindTags(tags)
            }
            bindProfileImage(stream)
        }

        private val isShortLandscapeCompactCard: Boolean
            get() = compactOverviewCards && binding.root.context.usesExpressiveInterface() &&
                binding.root.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                binding.root.resources.configuration.screenHeightDp <= 440

        private fun configureCompactCardLayout() {
            if (!compactOverviewCards || !binding.root.context.usesExpressiveInterface()) return
            val thumbnailFrame = binding.root.getChildAt(0) as? ConstraintLayout ?: return
            moveViewerCountToThumbnail(thumbnailFrame)

            val shortLandscape = isShortLandscapeCompactCard
            if (lastCompactLandscapeDensity != shortLandscape) {
                lastCompactLandscapeDensity = shortLandscape
                val verticalPadding = dp(if (shortLandscape) 4 else 6)
                binding.root.setPadding(dp(6), verticalPadding, dp(6), verticalPadding)

                val details = binding.root.getChildAt(1) as? android.widget.LinearLayout
                (details?.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { params ->
                    params.topMargin = dp(if (shortLandscape) 4 else 6)
                    details.layoutParams = params
                }
                binding.root.findViewById<android.widget.LinearLayout>(R.id.streamCardFooter)?.let { footer ->
                    (footer.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { params ->
                        params.topMargin = dp(0)
                        footer.layoutParams = params
                    }
                }
            }
        }

        private fun moveViewerCountToThumbnail(thumbnailFrame: ConstraintLayout) {
            val viewers = binding.viewers
            if (viewers.parent !== thumbnailFrame) {
                (viewers.parent as? ViewGroup)?.removeView(viewers)
                val params = ConstraintLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    endToStart = R.id.multiview
                    marginStart = dp(4)
                    bottomMargin = dp(4)
                    marginEnd = dp(4)
                    horizontalBias = 0f
                    constrainedWidth = true
                }
                thumbnailFrame.addView(viewers, 2, params)
                viewers.setBackgroundResource(R.drawable.bg_stream_overlay)
                viewers.setTextColor(Color.WHITE)
                viewers.setPadding(dp(4), dp(2), dp(4), dp(2))
                viewers.maxLines = 1
                viewers.ellipsize = android.text.TextUtils.TruncateAt.END
            }
        }

        private fun dp(value: Int): Int =
            (value * binding.root.resources.displayMetrics.density).toInt()

        private fun applyPresentation(presentation: com.github.andreyasadchy.xtra.ui.common.StreamCardPresentation) {
            if (boundStream == null) return
            with(binding) {
                viewers.text = presentation.viewerLabel.orEmpty()
                viewers.visibility = if (viewers.text.isNullOrBlank()) View.GONE else View.VISIBLE
                title.text = presentation.title.orEmpty()
                title.visibility = if (title.text.isNullOrBlank()) View.GONE else View.VISIBLE
                compactTitleScroll?.scrollTo(0, 0)
                channel.setVerifiedPartnerName(presentation.username, boundStream?.broadcasterType)
                channel.visibility = if (channel.text.isNullOrBlank()) View.GONE else View.VISIBLE
                compactChannelIdentity?.contentDescription = channel.text?.takeIf { it.isNotBlank() }
                    ?: binding.root.context.getString(R.string.account_view_channel)
                category.text = presentation.gameName.orEmpty()
                category.visibility = if (category.text.isNullOrBlank()) View.GONE else View.VISIBLE
                bindTags(presentation.tags)
            }
        }

        private fun bindTags(tags: List<String>) {
            val compact = compactTagTargets.isNotEmpty()
            val tagValues = tags.asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .take(MAX_STREAM_TAGS)
                .toList()
            boundTags = tagValues
            compactTagsScroll?.scrollTo(0, 0)
            if (compact) {
                ensureCompactTagTargets(tagValues.size)
                binding.tags.visibility = if (tagValues.isNotEmpty()) View.VISIBLE else View.GONE
                compactTagsScroll?.visibility = if (tagValues.isNotEmpty()) View.VISIBLE else View.GONE
                compactTagTargets.forEachIndexed { index, target ->
                    val visible = index < tagValues.size
                    target.visibility = if (visible) View.VISIBLE else View.GONE
                    if (visible) {
                        val value = tagValues[index]
                        target.contentDescription = value
                        compactTagViews[index].text = value
                        val params = target.layoutParams as? android.widget.LinearLayout.LayoutParams
                        params?.let {
                            it.marginEnd = dp(4)
                            target.layoutParams = it
                        }
                    }
                }
            } else if (horizontalTagViews != null) {
                bindStreamTags(horizontalTagViews, tagValues)
            } else {
                binding.tags.visibility = if (tagValues.isNotEmpty()) View.VISIBLE else View.GONE
                binding.tagOne.visibility = if (tagValues.size > 0) View.VISIBLE else View.GONE
                binding.tagTwo.visibility = if (tagValues.size > 1) View.VISIBLE else View.GONE
                binding.tagThree.visibility = if (tagValues.size > 2) View.VISIBLE else View.GONE
                binding.tagOne.text = tagValues.getOrNull(0).orEmpty()
                binding.tagTwo.text = tagValues.getOrNull(1).orEmpty()
                binding.tagThree.text = tagValues.getOrNull(2).orEmpty()
            }
        }

        private fun ensureCompactTagTargets(count: Int) {
            while (compactTagTargets.size < count) {
                val index = compactTagTargets.size
                val context = binding.root.context
                val target = android.widget.FrameLayout(context).apply {
                    id = View.generateViewId()
                    minimumWidth = dp(48)
                    minimumHeight = dp(48)
                    isClickable = true
                    isFocusable = true
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    contentDescription = boundTags.getOrNull(index).orEmpty()
                    val selectableItemBackground = android.util.TypedValue().also {
                        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                    }
                    foreground = if (selectableItemBackground.resourceId != 0) {
                        androidx.core.content.ContextCompat.getDrawable(context, selectableItemBackground.resourceId)
                    } else {
                        null
                    }
                    setOnClickListener { boundTags.getOrNull(index)?.let(onTagClick) }
                }
                val chip = LayoutInflater.from(context).inflate(R.layout.item_stream_tag, target, false) as android.widget.TextView
                chip.isClickable = false
                chip.isFocusable = false
                chip.foreground = null
                chip.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                ExpressiveShapeStyling.applyStreamShelfTagChip(chip)
                target.addView(
                    chip,
                    android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.CENTER,
                    ),
                )
                val params = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(4) }
                binding.tags.addView(target, params)
                compactTagTargets += target
                compactTagViews += chip
            }
        }

        private fun openChannel(stream: Stream) {
            fragment.findNavController().navigate(
                ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                    channelId = stream.channelId,
                    channelLogin = stream.channelLogin,
                    channelName = stream.channelName,
                    channelImage = stream.channelImage,
                    streamId = stream.id,
                ),
            )
        }

        private fun openGame(stream: Stream) {
            if (stream.gameName.isNullOrBlank()) return
            fragment.findNavController().navigate(
                GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                    gameId = stream.gameId,
                    gameSlug = stream.gameSlug,
                    gameName = stream.gameName,
                ),
            )
        }

        private val titleMaxLines: Int
            get() = when {
                compactOverviewCards && binding.root.context.usesExpressiveInterface() ->
                    if (isShortLandscapeCompactCard) 1 else 2
                binding.root.resources.configuration.smallestScreenWidthDp >= 600 -> 1
                else -> 2
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
