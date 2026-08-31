package com.github.andreyasadchy.xtra.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentStreamsListItemCompactBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.tv.TvFocusHelper
import com.github.andreyasadchy.xtra.util.isTelevision
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.multiview.MultiviewFragment
import com.github.andreyasadchy.xtra.XtraApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class StreamsCompactAdapter(
    private val fragment: Fragment,
    private val selectTag: (String) -> Unit,
    private val showGame: Boolean = true,
    private val onStreamClick: ((Stream) -> Unit)? = null,
) : PagingDataAdapter<Stream, StreamsCompactAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<Stream>() {
        override fun areItemsTheSame(oldItem: Stream, newItem: Stream): Boolean =
            oldItem.streamIdentity() == newItem.streamIdentity()

        override fun areContentsTheSame(oldItem: Stream, newItem: Stream): Boolean =
            streamContentsSame(oldItem, newItem)

        override fun getChangePayload(oldItem: Stream, newItem: Stream): Any? =
            if (streamThumbnailOnlyChanged(oldItem, newItem)) StreamThumbnailChangedPayload else null
    }) {

    private val thumbnailLoadScheduler = StreamThumbnailIdleScheduler()
    private val uptimeTicker = VisibleStreamUptimeTicker(fragment)
    private val presentationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var presentationPrewarmJob: Job? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachImageScheduler(recyclerView)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        detachImageScheduler()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    internal fun attachImageScheduler(recyclerView: RecyclerView) {
        thumbnailLoadScheduler.attachTo(recyclerView)
        uptimeTicker.attach(recyclerView)
        presentationPrewarmJob?.cancel()
        presentationPrewarmJob = presentationScope.launch {
            onPagesUpdatedFlow.collectLatest {
                val context = fragment.context ?: return@collectLatest
                val preferences = FeedUiPreferencesStore.current(context)
                StreamCardPresentationCache.prewarm(
                    context = context,
                    streams = snapshot().items.filterNotNull(),
                    preferences = preferences,
                )
            }
        }
    }

    internal fun detachImageScheduler() {
        presentationPrewarmJob?.cancel()
        presentationPrewarmJob = null
        thumbnailLoadScheduler.detach()
        uptimeTicker.detach()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagingViewHolder {
        val binding = FragmentStreamsListItemCompactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PagingViewHolder(binding, fragment, showGame, createStreamTagViews(binding.tagsLayout))
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) {
        holder.beginImageBind(getItem(position))
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it === StreamThumbnailChangedPayload }) {
            holder.beginImageBind(getItem(position))
            holder.bindThumbnail(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: PagingViewHolder) {
        recycleViewHolder(holder)
        super.onViewRecycled(holder)
    }

    internal fun recycleViewHolder(holder: PagingViewHolder) {
        (fragment.requireContext().applicationContext as XtraApp).xtraModule.streamPreviewCoordinator
            .detachSurface(holder.previewSurface)
        holder.boundPreviewIdentity = null
        thumbnailLoadScheduler.clear(holder)
        holder.cancelImageWork()
    }

    inner class PagingViewHolder internal constructor(
        private val binding: FragmentStreamsListItemCompactBinding,
        private val fragment: Fragment,
        private val showGame: Boolean,
        private val tagViews: StreamTagViews,
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
            binding.root.setOnClickListener { boundStream?.let(::openStream) }
            TvFocusHelper.install(binding.root)
            binding.userImage.setOnClickListener { boundStream?.let(::openChannel) }
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
            clearUptime()
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
                    val uiPreferences = FeedUiPreferencesStore.current(context)
                    uptimeEnabled = uiPreferences.showUptime
                    uptimeStartedAtMs = if (uptimeEnabled) parseStreamStartedAtMs(item.createdAt) else null
                    lastRenderedUptimeSecond = Long.MIN_VALUE
                    updateUptime(System.currentTimeMillis())
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
                        userImage.visibility = View.VISIBLE
                        userImage.contentDescription = item.channelName?.let {
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
                        userImage.visibility = View.GONE
                        userImage.contentDescription = null
                        userImage.setImageDrawable(null)
                        userImage.tag = null
                    }
                    if (presentation?.username != null || item.channelName != null) {
                        username.visibility = View.VISIBLE
                        username.text = presentation?.username ?: item.channelName
                    } else {
                        username.visibility = View.GONE
                    }
                    val streamTitle = presentation?.title ?: item.title?.takeIf { it.isNotBlank() }
                    if (!streamTitle.isNullOrBlank()) {
                        title.visibility = View.VISIBLE
                        title.text = streamTitle
                    } else {
                        title.visibility = View.GONE
                    }
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
                    clearUptime()
                    userImage.setImageDrawable(null)
                    userImage.tag = null
                    thumbnail.setImageDrawable(null)
                    thumbnail.tag = null
                    username.visibility = View.GONE
                    title.visibility = View.GONE
                    gameName.visibility = View.GONE
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
                    username.text = presentation.username
                } else {
                    username.visibility = View.GONE
                }
                if (presentation.title != null) {
                    title.visibility = View.VISIBLE
                    title.text = presentation.title
                } else {
                    title.visibility = View.GONE
                }
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
