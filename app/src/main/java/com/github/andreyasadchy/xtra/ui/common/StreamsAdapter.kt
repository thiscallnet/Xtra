package com.github.andreyasadchy.xtra.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentStreamsListItemBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.drops.StreamDropsBottomSheet
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.multiview.MultiviewFragment
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.ui.tv.TvFocusHelper
import com.github.andreyasadchy.xtra.util.isTelevision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class StreamsAdapter(
    private val fragment: Fragment,
    private val selectTag: (String) -> Unit,
    private val showGame: Boolean = true,
    private val onStreamClick: ((Stream) -> Unit)? = null,
    private val shelfCardSizing: Boolean = false,
    private val featuredCarousel: Boolean = false,
) : PagingDataAdapter<Stream, StreamsAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<Stream>() {
        override fun areItemsTheSame(oldItem: Stream, newItem: Stream): Boolean =
            oldItem.streamIdentity() == newItem.streamIdentity()

        override fun areContentsTheSame(oldItem: Stream, newItem: Stream): Boolean =
            streamContentsSame(oldItem, newItem)

        override fun getChangePayload(oldItem: Stream, newItem: Stream): Any? =
            if (streamThumbnailOnlyChanged(oldItem, newItem)) StreamThumbnailChangedPayload else null
    }) {

    private val thumbnailLoadScheduler = StreamThumbnailIdleScheduler()
    private val presentationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var presentationPrewarmJob: Job? = null
    private var featuredSnapHelper: PagerSnapHelper? = null
    private var featuredRecyclerView: RecyclerView? = null
    private var shouldCenterFeaturedCard = false
    private var initialFeaturedCardPositioned = false

    private val featuredDataObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = centerFeaturedCardIfReady()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = centerFeaturedCardIfReady()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = centerFeaturedCardIfReady()
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        thumbnailLoadScheduler.attachTo(recyclerView)
        presentationPrewarmJob?.cancel()
        presentationPrewarmJob = presentationScope.launch {
            onPagesUpdatedFlow.collectLatest {
                val context = fragment.context ?: return@collectLatest
                val preferences = FeedUiPreferencesStore.current(context)
                StreamCardPresentationCache.prewarm(
                    context = context,
                    itemCount = itemCount,
                    itemAt = { index -> peek(index) },
                    preferences = preferences,
                )
            }
        }
        if (shelfCardSizing) {
            recyclerView.addOnLayoutChangeListener(shelfLayoutChangeListener)
            if (featuredCarousel) {
                featuredRecyclerView = recyclerView
                registerAdapterDataObserver(featuredDataObserver)
                recyclerView.clipToPadding = false
                val snapHelper = PagerSnapHelper()
                snapHelper.attachToRecyclerView(recyclerView)
                featuredSnapHelper = snapHelper
            }
            recyclerView.post { updateShelfCardSizing(recyclerView) }
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        presentationPrewarmJob?.cancel()
        presentationPrewarmJob = null
        thumbnailLoadScheduler.detach()
        if (shelfCardSizing) recyclerView.removeOnLayoutChangeListener(shelfLayoutChangeListener)
        if (featuredCarousel) {
            unregisterAdapterDataObserver(featuredDataObserver)
            featuredSnapHelper?.attachToRecyclerView(null)
            featuredSnapHelper = null
            featuredRecyclerView = null
        }
        super.onDetachedFromRecyclerView(recyclerView)
    }

    private val shelfLayoutChangeListener = View.OnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
        if (right - left != oldRight - oldLeft) {
            val recyclerView = view as RecyclerView
            recyclerView.post { updateShelfCardSizing(recyclerView) }
        }
    }

    private fun updateShelfCardSizing(recyclerView: RecyclerView) {
        if (!shelfCardSizing || recyclerView.width <= 0) return
        val cardWidth = shelfCardWidth(recyclerView)
        if (featuredCarousel) {
            val sidePadding = ((recyclerView.width - cardWidth) / 2).coerceAtLeast(0)
            if (recyclerView.paddingLeft != sidePadding || recyclerView.paddingRight != sidePadding) {
                recyclerView.setPadding(sidePadding, recyclerView.paddingTop, sidePadding, recyclerView.paddingBottom)
            }
        }
        repeat(recyclerView.childCount) { index ->
            val item = recyclerView.getChildAt(index)
            val params = item.layoutParams ?: return@repeat
            if (params.width != cardWidth) {
                params.width = cardWidth
                item.layoutParams = params
            }
        }
    }

    private fun shelfCardWidth(recyclerView: RecyclerView): Int {
        val measuredWidth = recyclerView.width.takeIf { it > 0 }
            ?: recyclerView.rootView.width.takeIf { it > 0 }
            ?: recyclerView.resources.displayMetrics.widthPixels
        val availableWidth = if (featuredCarousel) {
            measuredWidth
        } else {
            (measuredWidth - recyclerView.paddingLeft - recyclerView.paddingRight).coerceAtLeast(1)
        }
        val density = recyclerView.resources.displayMetrics.density
        val widthDp = availableWidth / density
        val narrowPhone = recyclerView.resources.configuration.smallestScreenWidthDp < 600
        val portrait = recyclerView.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        return when {
            narrowPhone && portrait -> (availableWidth * 0.95f).toInt().coerceAtMost(availableWidth)
            narrowPhone -> ((widthDp / 2f).coerceIn(260f, 360f) * density).toInt()
            widthDp < 840f -> ((widthDp / 1.9f).coerceIn(290f, 360f) * density).toInt()
            else -> ((widthDp / 2f).coerceIn(320f, 380f) * density).toInt()
        }
    }

    fun scrollBy(recyclerView: RecyclerView, direction: Int) {
        if (!featuredCarousel || itemCount == 0) return
        val layoutManager = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
        val currentPosition = layoutManager.findFirstCompletelyVisibleItemPosition()
            .takeIf { it != RecyclerView.NO_POSITION }
            ?: layoutManager.findFirstVisibleItemPosition()
        if (currentPosition == RecyclerView.NO_POSITION) return
        recyclerView.smoothScrollToPosition((currentPosition + direction).coerceIn(0, itemCount - 1))
    }

    fun centerInitialCard(recyclerView: RecyclerView) {
        if (!featuredCarousel || initialFeaturedCardPositioned) return
        shouldCenterFeaturedCard = true
        featuredRecyclerView = recyclerView
        centerFeaturedCardIfReady()
    }

    private fun centerFeaturedCardIfReady() {
        val recyclerView = featuredRecyclerView ?: return
        if (!shouldCenterFeaturedCard || initialFeaturedCardPositioned || itemCount < 2) return
        recyclerView.post {
            if (featuredRecyclerView === recyclerView && shouldCenterFeaturedCard &&
                !initialFeaturedCardPositioned && itemCount > 1
            ) {
                shouldCenterFeaturedCard = false
                initialFeaturedCardPositioned = true
                recyclerView.smoothScrollToPosition(1)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagingViewHolder {
        val binding = FragmentStreamsListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        (parent as? RecyclerView)?.let { recyclerView ->
            if (shelfCardSizing) {
                binding.root.layoutParams.width = shelfCardWidth(recyclerView)
                updateShelfCardSizing(recyclerView)
            }
        }
        val tagViews = createStreamTagViews(
            binding.tagsLayout,
        )
        return PagingViewHolder(binding, fragment, showGame, tagViews, shelfCardSizing)
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) {
        if (shelfCardSizing) {
            (holder.itemView.parent as? RecyclerView)?.let { recyclerView ->
                val params = holder.itemView.layoutParams
                val cardWidth = shelfCardWidth(recyclerView)
                if (params.width != cardWidth) {
                    params.width = cardWidth
                    holder.itemView.layoutParams = params
                }
            }
        }
        holder.beginImageBind(getItem(position))
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it === StreamThumbnailChangedPayload }) {
            holder.beginThumbnailRefresh()
            holder.bindThumbnail(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: PagingViewHolder) {
        (fragment.requireContext().applicationContext as XtraApp).xtraModule.streamPreviewCoordinator
            .detachSurface(holder.previewSurface)
        holder.boundPreviewIdentity = null
        thumbnailLoadScheduler.clear(holder)
        holder.cancelImageWork()
        super.onViewRecycled(holder)
    }

    inner class PagingViewHolder internal constructor(
        private val binding: FragmentStreamsListItemBinding,
        private val fragment: Fragment,
        private val showGame: Boolean,
        private val tagViews: StreamTagViews,
        private val shelfCardSizing: Boolean,
    ) : RecyclerView.ViewHolder(binding.root), FeedImageRequestOwner {
        val previewSurface get() = binding.previewHost
        var boundPreviewIdentity: String? = null
        private val imageRequests = FeedImageRequestBag()
        private var boundImageIdentity: String? = null
        private var boundThumbnailKey: String? = null
        private var boundStream: Stream? = null
        private val uptimeBinder = StreamCardUptimeBinder(binding.uptime)
        private val dropsBadgeBinder = StreamDropsBadgeBinder(
            badge = binding.dropsBadge,
            onClick = { stream -> StreamDropsBottomSheet.show(fragment, stream) },
            touchTarget = if (shelfCardSizing) binding.dropsBadgeTarget else null,
        )

        init {
            if (shelfCardSizing) {
                val density = binding.root.resources.displayMetrics.density
                val targetSize = (48 * density + 0.5f).toInt()
                val avatarParams = binding.userImageTarget.layoutParams as ConstraintLayout.LayoutParams
                avatarParams.width = targetSize
                avatarParams.height = targetSize
                avatarParams.topMargin = (4 * density + 0.5f).toInt()
                avatarParams.bottomMargin = avatarParams.topMargin
                binding.userImageTarget.layoutParams = avatarParams
                binding.root.touchDelegate = ExpandedStreamCardTouchDelegate(
                    card = binding.root,
                    detailTargets = listOf(
                        binding.userImageTarget,
                        binding.username,
                        binding.gameName,
                        binding.dropsBadgeTarget,
                    ),
                    tagTargets = { tagViews.touchTargets },
                    titleScroll = binding.titleScroll,
                    targetSizePx = targetSize,
                )
            }
        }

        init {
            binding.root.setOnClickListener { boundStream?.let(::openStream) }
            TvFocusHelper.install(binding.root)
            if (binding.root.context.isTelevision()) {
                binding.root.setOnLongClickListener {
                    boundStream?.let(::openChannel)
                    boundStream != null
                }
            }
            binding.userImageTarget.setOnClickListener { boundStream?.let(::openChannel) }
            binding.username.setOnClickListener { boundStream?.let(::openChannel) }
            binding.gameName.setOnClickListener { boundStream?.let(::openGame) }
            binding.multiview.setOnClickListener { boundStream?.let(::openMultiview) }
            tagViews.setOnTagClickListener { tag ->
                boundStream?.let { stream ->
                    if (onStreamClick != null) onStreamClick.invoke(stream) else selectTag(tag)
                }
            }
        }

        fun beginImageBind(item: Stream?) {
            thumbnailLoadScheduler.clear(this)
            imageRequests.cancel()
            boundImageIdentity = item?.streamIdentity()
            boundThumbnailKey = null
        }

        fun beginThumbnailRefresh() {
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
            boundImageIdentity = null
            boundThumbnailKey = null
            dropsBadgeBinder.clear()
            uptimeBinder.clear()
        }

        fun bindThumbnail(item: Stream?) {
            val stream = item ?: return
            prepareStreamThumbnailImage(binding.thumbnail, stream)
            restoreWarmStreamThumbnail(stream, binding.thumbnail)
            val key = "${stream.thumbnailIdentity()}|generation=${stream.thumbnailGeneration}"
            boundThumbnailKey = key
            thumbnailLoadScheduler.runOrDefer(this@PagingViewHolder, binding.thumbnail) {
                if (!binding.root.isAttachedToWindow ||
                    boundImageIdentity != stream.streamIdentity() ||
                    boundThumbnailKey != key
                ) return@runOrDefer
                loadStreamThumbnail(
                    context = fragment.requireContext(),
                    imageView = binding.thumbnail,
                    stream = stream,
                    scheduleFreshRequest = { freshRequest ->
                        thumbnailLoadScheduler.runOrDefer(this@PagingViewHolder, binding.thumbnail, freshRequest)
                    },
                )
                    ?.let { imageRequests.replace(binding.thumbnail, it) }
            }
        }

        fun bind(item: Stream?) {
            boundStream = item
            val nextPreviewIdentity = item?.streamIdentity()
            if (boundPreviewIdentity != nextPreviewIdentity) {
                (fragment.requireContext().applicationContext as XtraApp).xtraModule.streamPreviewCoordinator
                    .detachSurface(previewSurface)
                boundPreviewIdentity = nextPreviewIdentity
            }
            with(binding) {
                if (item != null) {
                    val context = fragment.requireContext()
                    dropsBadgeBinder.bind(item)
                    val uiPreferences = FeedUiPreferencesStore.current(context)
                    uptimeBinder.bind(item.createdAt, uiPreferences.showUptime)
                    val presentation = StreamCardPresentationCache.get(item, uiPreferences)
                    if (presentation == null) {
                        StreamCardPresentationCache.request(context, item, uiPreferences) {
                            if (boundStream === item && binding.root.isAttachedToWindow) {
                                applyPresentation(it)
                            }
                        }
                    }
                    val selectionMode = onStreamClick != null
                    multiview.visibility = if (selectionMode || item.channelLogin.isNullOrBlank() || context.isTelevision()) View.GONE else View.VISIBLE
                    if (presentation?.channelImage != null || item.channelImage != null) {
                        userImageTarget.visibility = View.VISIBLE
                        userImage.visibility = View.VISIBLE
                        userImageTarget.contentDescription = item.channelName?.let {
                            context.getString(R.string.player_open_channel, it)
                        }
                        prepareStreamProfileImage(userImage, item)
                        val profileRestored = restoreWarmStreamProfileImage(context, userImage, item)
                        if (!profileRestored) thumbnailLoadScheduler.runOrDefer(this@PagingViewHolder, binding.userImage) {
                            if (binding.root.isAttachedToWindow && boundImageIdentity == item.streamIdentity()) {
                                loadStreamProfileImage(context, userImage, item)?.let {
                                    imageRequests.replace(binding.userImage, it)
                                }
                            }
                        }
                    } else {
                        userImageTarget.visibility = View.GONE
                        userImageTarget.contentDescription = null
                        userImage.visibility = View.GONE
                        userImage.setImageDrawable(null)
                        userImage.tag = null
                    }
                    if (presentation?.username != null || item.channelName != null) {
                        username.visibility = View.VISIBLE
                        username.setVerifiedPartnerName(
                            presentation?.username ?: item.channelName,
                            item.broadcasterType,
                        )
                    } else {
                        username.visibility = View.GONE
                        username.setVerifiedPartnerName(null, null)
                    }
                    val streamTitle = presentation?.title ?: item.title
                    if (!streamTitle.isNullOrBlank()) {
                        title.visibility = View.VISIBLE
                        title.text = streamTitle
                    } else {
                        title.visibility = View.GONE
                    }
                    titleScroll.scrollTo(0, 0)
                    if (showGame && item.gameName != null) {
                        gameName.visibility = View.VISIBLE
                        gameName.text = item.gameName
                    } else {
                        gameName.visibility = View.GONE
                    }
                    if (item.thumbnailURL != null) {
                        thumbnail.visibility = View.VISIBLE
                        liveBadge.visibility = View.VISIBLE
                        bindThumbnail(item)
                    } else {
                        thumbnail.visibility = View.GONE
                        liveBadge.visibility = View.GONE
                        thumbnail.setImageDrawable(null)
                        thumbnail.tag = null
                    }
                    if (presentation?.viewerLabel != null || item.viewerCount != null) {
                        viewers.visibility = View.VISIBLE
                        viewers.text = presentation?.viewerLabel ?: item.viewerCount?.toString()
                    } else {
                        viewers.visibility = View.GONE
                    }
                    val tags = presentation?.tags ?: if (uiPreferences.showTags) item.tags.orEmpty() else emptyList()
                    if (tags.isNotEmpty()) {
                        bindStreamTags(tagViews, tags)
                    } else {
                        clearStreamTags(tagViews)
                    }
                } else {
                    boundStream = null
                    dropsBadgeBinder.clear()
                    (fragment.requireContext().applicationContext as XtraApp).xtraModule.streamPreviewCoordinator
                        .detachSurface(previewSurface)
                    boundPreviewIdentity = null
                    uptimeBinder.clear()
                    userImage.setImageDrawable(null)
                    userImage.tag = null
                    userImageTarget.visibility = View.GONE
                    userImageTarget.contentDescription = null
                    thumbnail.setImageDrawable(null)
                    thumbnail.tag = null
                    username.visibility = View.GONE
                    title.visibility = View.GONE
                    gameName.visibility = View.GONE
                    username.setVerifiedPartnerName(null, null)
                    titleScroll.scrollTo(0, 0)
                    viewers.visibility = View.GONE
                    clearStreamTags(tagViews)
                    tagsLayout.visibility = View.GONE
                    liveBadge.visibility = View.GONE
                }
            }
        }

        private fun applyPresentation(presentation: StreamCardPresentation) {
            if (boundStream == null) return
            with(binding) {
                if (presentation.username != null) {
                    username.visibility = View.VISIBLE
                    username.setVerifiedPartnerName(presentation.username, boundStream?.broadcasterType)
                } else {
                    username.visibility = View.GONE
                    username.setVerifiedPartnerName(null, null)
                }
                if (presentation.title != null) {
                    title.visibility = View.VISIBLE
                    title.text = presentation.title
                } else {
                    title.visibility = View.GONE
                }
                titleScroll.scrollTo(0, 0)
                if (presentation.viewerLabel != null) {
                    viewers.visibility = View.VISIBLE
                    viewers.text = presentation.viewerLabel
                } else {
                    viewers.visibility = View.GONE
                }
                if (presentation.tags.isNotEmpty()) {
                    bindStreamTags(tagViews, presentation.tags)
                } else {
                    clearStreamTags(tagViews)
                }
            }
        }

        private fun openStream(stream: Stream) {
            if (onStreamClick != null) {
                onStreamClick.invoke(stream)
            } else {
                (fragment.activity as? MainActivity)?.startStream(stream)
            }
        }

        private fun openChannel(stream: Stream) {
            if (onStreamClick != null) {
                onStreamClick.invoke(stream)
            } else {
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
        }

        private fun openGame(stream: Stream) {
            if (showGame && !stream.gameName.isNullOrBlank()) {
                if (onStreamClick != null) {
                    onStreamClick.invoke(stream)
                } else {
                    fragment.findNavController().navigate(
                        GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                            gameId = stream.gameId,
                            gameSlug = stream.gameSlug,
                            gameName = stream.gameName,
                        ),
                    )
                }
            }
        }

        private fun openMultiview(stream: Stream) {
            (fragment.activity as? MainActivity)?.let { activity ->
                if (activity.playerFragment != null) activity.closePlayer()
            }
            fragment.findNavController().navigate(R.id.multiviewFragment, MultiviewFragment.arguments(stream))
        }
    }
}
