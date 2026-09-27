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
import com.github.andreyasadchy.xtra.ui.common.StreamCardPresentationCache
import com.github.andreyasadchy.xtra.ui.common.StreamDropsBadgeBinder
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
import com.github.andreyasadchy.xtra.ui.common.ExpressiveShapeStyling
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
    private val uptimeTicker = VisibleStreamUptimeTicker(fragment)

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
        val cardWidthDp = when {
            shelf.resources.configuration.smallestScreenWidthDp < 600 ->
                (widthDp - 16f).coerceIn(280f, 360f)
            widthDp < 840f -> (widthDp / 1.9f).coerceIn(290f, 360f)
            else -> (widthDp / 2f).coerceIn(320f, 380f)
        }
        val cardWidth = (cardWidthDp * density).toInt()
        if (params.width != cardWidth) {
            params.width = cardWidth
            item.layoutParams = params
        }
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
        private var boundImageStream: Stream? = null
        private var boundThumbnailKey: String? = null
        private var boundStream: Stream? = null
        private var boundTags: List<String> = emptyList()
        private var overflowTags: List<String> = emptyList()
        private var overflowTagTargetIndex: Int? = null
        private var uptimeStartedAtMs: Long? = null
        private var uptimeEnabled = false
        private var lastRenderedUptimeSecond = Long.MIN_VALUE
        private var lastCardOrientation: Int? = null
        private val compactCardStatus = binding.root.findViewById<android.widget.LinearLayout>(R.id.streamCardStatus)
        private val compactIdentitySeparator = binding.root.findViewById<View>(R.id.identitySeparator)
        private val compactAvatarTouchTarget = binding.root.findViewById<View>(R.id.avatarTouchTarget)
        private val compactTagTargets = listOf(
            binding.root.findViewById<View>(R.id.tagOneTarget),
            binding.root.findViewById<View>(R.id.tagTwoTarget),
            binding.root.findViewById<View>(R.id.tagThreeTarget),
        )
        private val compactTagViews = listOf(binding.tagOne, binding.tagTwo, binding.tagThree)
        private val dropsBadgeBinder = StreamDropsBadgeBinder(binding.dropsBadge) { stream ->
            StreamDropsBottomSheet.show(fragment, stream)
        }

        init {
            binding.root.setOnClickListener { boundStream?.let(onStreamClick) }
            (compactAvatarTouchTarget ?: binding.avatar).setOnClickListener {
                boundStream?.let(::openChannel)
            }
            binding.channel.setOnClickListener { boundStream?.let(::openChannel) }
            binding.category.setOnClickListener { boundStream?.let(::openGame) }
            if (compactTagTargets.all { it == null }) {
                binding.tagOne.setOnClickListener { boundTags.getOrNull(0)?.let(onTagClick) }
                binding.tagTwo.setOnClickListener { boundTags.getOrNull(1)?.let(onTagClick) }
                binding.tagThree.setOnClickListener { boundTags.getOrNull(2)?.let(onTagClick) }
            } else {
                compactTagTargets.forEachIndexed { index, target ->
                    target?.setOnClickListener { onCompactTagClick(index, target) }
                }
            }
            binding.tags.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) updateTagWidths(boundTags.size)
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
            dropsBadgeBinder.clear()
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
                title.maxLines = if (isCompactLandscape) 1 else titleMaxLines
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
                updateIdentitySeparator()

                val tags = presentation?.tags ?: if (uiPreferences.showTags) stream.tags.orEmpty() else emptyList()
                bindTags(tags)
            }
            bindProfileImage(stream)
        }

        private val isCompactLandscape: Boolean
            get() = compactOverviewCards && binding.root.context.usesExpressiveInterface() &&
                binding.root.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        private fun configureCompactCardLayout() {
            if (!compactOverviewCards || !binding.root.context.usesExpressiveInterface()) return
            val orientation = binding.root.resources.configuration.orientation
            if (lastCardOrientation == orientation) return
            lastCardOrientation = orientation

            val footer = binding.root.findViewById<android.widget.LinearLayout>(R.id.streamCardFooter) ?: return
            val status = compactCardStatus ?: return
            val thumbnailFrame = binding.root.getChildAt(0) as? ConstraintLayout ?: return
            val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
            footer.orientation = if (landscape) {
                android.widget.LinearLayout.HORIZONTAL
            } else {
                android.widget.LinearLayout.VERTICAL
            }
            val tagsParams = binding.tags.layoutParams as android.widget.LinearLayout.LayoutParams
            val statusParams = status.layoutParams as android.widget.LinearLayout.LayoutParams
            tagsParams.width = if (landscape) 0 else ViewGroup.LayoutParams.MATCH_PARENT
            tagsParams.weight = if (landscape) 1f else 0f
            tagsParams.topMargin = 0
            statusParams.width = if (landscape) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
            statusParams.weight = 0f
            statusParams.marginStart = if (landscape) dp(6) else 0
            statusParams.topMargin = if (landscape) 0 else dp(4)
            val footerParams = footer.layoutParams as android.widget.LinearLayout.LayoutParams
            footerParams.topMargin = dp(if (landscape) 2 else 6)
            footer.layoutParams = footerParams
            binding.tags.layoutParams = tagsParams
            status.layoutParams = statusParams

            val paddingHorizontal = dp(6)
            val paddingVertical = if (landscape) 0 else dp(6)
            binding.root.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
            moveViewerCountToThumbnail(thumbnailFrame)
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
                channel.text = presentation.username.orEmpty()
                channel.visibility = if (channel.text.isNullOrBlank()) View.GONE else View.VISIBLE
                category.text = presentation.gameName.orEmpty()
                category.visibility = if (category.text.isNullOrBlank()) View.GONE else View.VISIBLE
                updateIdentitySeparator()
                bindTags(presentation.tags)
            }
        }

        private fun updateIdentitySeparator() {
            compactIdentitySeparator?.visibility =
                if (binding.channel.visibility == View.VISIBLE && binding.category.visibility == View.VISIBLE) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
        }

        private fun bindTags(tags: List<String>) {
            val tagValues = tags.asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .take(3)
                .toList()
            boundTags = tagValues
            binding.tagOne.text = tagValues.getOrNull(0).orEmpty()
            binding.tagTwo.text = tagValues.getOrNull(1).orEmpty()
            binding.tagThree.text = tagValues.getOrNull(2).orEmpty()
            binding.tags.visibility = if (tagValues.isNotEmpty()) View.VISIBLE else View.GONE
            if (compactTagTargets.all { it != null }) {
                compactTagViews.forEach { it.visibility = View.VISIBLE }
                updateTagWidths(tagValues.size)
            } else {
                binding.tagOne.visibility = if (tagValues.size > 0) View.VISIBLE else View.GONE
                binding.tagTwo.visibility = if (tagValues.size > 1) View.VISIBLE else View.GONE
                binding.tagThree.visibility = if (tagValues.size > 2) View.VISIBLE else View.GONE
            }
        }

        private fun updateTagWidths(tagCount: Int) {
            if (!compactOverviewCards || !binding.root.context.usesExpressiveInterface() || compactTagTargets.any { it == null }) return
            val targets = compactTagTargets.filterNotNull()
            if (tagCount == 0) {
                targets.forEach { it.visibility = View.GONE }
                binding.tags.visibility = View.GONE
                overflowTags = emptyList()
                overflowTagTargetIndex = null
                return
            }

            val density = binding.root.resources.displayMetrics.density
            val measuredWidth = binding.tags.width.takeIf { it > 0 }
                ?: run {
                    val cardWidth = binding.root.layoutParams?.width?.takeIf { it > 0 }?.div(density) ?: return
                    val thumbnailWidth = binding.root.getChildAt(0).layoutParams?.width?.div(density) ?: 120f
                    val detailsMargin = (binding.root.getChildAt(1).layoutParams as? android.widget.LinearLayout.LayoutParams)
                        ?.marginStart?.div(density) ?: 10f
                    val horizontalPadding = (binding.root.paddingStart + binding.root.paddingEnd) / density
                    ((cardWidth - thumbnailWidth - detailsMargin - horizontalPadding) * density).toInt()
                }

            val minTargetWidth = dp(48)
            val gap = dp(4)
            val normalTargetCount = minOf(tagCount, targets.size)
            var realTagCount = normalTargetCount
            var visibleTargetCount = normalTargetCount
            overflowTagTargetIndex = null
            if (normalTargetCount * minTargetWidth + (normalTargetCount - 1) * gap > measuredWidth) {
                realTagCount = -1
                for (candidateRealTagCount in (normalTargetCount - 1 downTo 0)) {
                    val candidateTargetCount = candidateRealTagCount + 1
                    val requiredWidth = candidateTargetCount * minTargetWidth + (candidateTargetCount - 1) * gap
                    if (requiredWidth <= measuredWidth) {
                        realTagCount = candidateRealTagCount
                        visibleTargetCount = candidateTargetCount
                        overflowTagTargetIndex = candidateRealTagCount
                        break
                    }
                }
                if (realTagCount < 0) {
                    targets.forEach { it.visibility = View.GONE }
                    binding.tags.visibility = View.GONE
                    overflowTags = emptyList()
                    overflowTagTargetIndex = null
                    return
                }
            }

            overflowTags = if (overflowTagTargetIndex != null) boundTags.drop(realTagCount) else emptyList()
            binding.tags.visibility = View.VISIBLE
            targets.forEachIndexed { index, target ->
                val isVisible = index < visibleTargetCount
                target.visibility = if (isVisible) View.VISIBLE else View.GONE
                if (!isVisible) return@forEachIndexed

                val isOverflow = index == overflowTagTargetIndex
                val value = if (isOverflow) {
                    binding.root.context.getString(R.string.compact_stream_more_tags_short, overflowTags.size)
                } else {
                    boundTags.getOrNull(index).orEmpty()
                }
                compactTagViews[index].text = value
                compactTagViews[index].importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                target.contentDescription = if (isOverflow) {
                    binding.root.context.resources.getQuantityString(
                        R.plurals.compact_stream_more_tags_description,
                        overflowTags.size,
                        overflowTags.size,
                    )
                } else {
                    value
                }

                val params = target.layoutParams as android.widget.LinearLayout.LayoutParams
                params.marginEnd = if (index < visibleTargetCount - 1) gap else 0
                target.layoutParams = params
            }

            val maxTagWidth = dp(84)
            var remainingWidth = measuredWidth
            repeat(visibleTargetCount) { index ->
                val target = targets[index]
                val tag = compactTagViews[index]
                val params = target.layoutParams as android.widget.LinearLayout.LayoutParams
                val marginEnd = params.marginEnd
                val targetsAfter = visibleTargetCount - index - 1
                val reservedForLaterTargets = targetsAfter * (minTargetWidth + gap)
                val maxWidth = (remainingWidth - marginEnd - reservedForLaterTargets).coerceAtLeast(minTargetWidth)
                val preferredWidth = (tag.paint.measureText(tag.text?.toString().orEmpty()) + tag.compoundPaddingLeft + tag.compoundPaddingRight)
                    .toInt()
                    .plus(dp(2))
                    .coerceAtMost(maxTagWidth)
                val width = preferredWidth.coerceAtMost(maxWidth).coerceAtLeast(minTargetWidth)
                params.width = width
                target.layoutParams = params
                tag.maxWidth = width
                remainingWidth -= width + marginEnd
            }
        }

        private fun onCompactTagClick(index: Int, target: View) {
            if (index != overflowTagTargetIndex) {
                boundTags.getOrNull(index)?.let(onTagClick)
                return
            }
            val tags = overflowTags
            if (tags.isEmpty()) return

            android.widget.PopupMenu(target.context, target).apply {
                tags.forEachIndexed { itemId, tag -> menu.add(0, itemId, itemId, tag) }
                setOnMenuItemClickListener { item ->
                    tags.getOrNull(item.itemId)?.let(onTagClick)
                    true
                }
            }.show()
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
            get() = if (binding.root.resources.configuration.smallestScreenWidthDp >= 600) 1 else 2

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
