package com.github.andreyasadchy.xtra.ui.common

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentStreamsListItemBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.multiview.MultiviewFragment
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlin.time.Clock
import kotlin.time.Instant

class StreamsAdapter(
    private val fragment: Fragment,
    private val selectTag: (String) -> Unit,
    private val showGame: Boolean = true,
    private val onStreamClick: ((Stream) -> Unit)? = null,
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

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        thumbnailLoadScheduler.attachTo(recyclerView)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        thumbnailLoadScheduler.detach()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagingViewHolder {
        val binding = FragmentStreamsListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
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
    ) : RecyclerView.ViewHolder(binding.root), FeedImageRequestOwner {
        val previewSurface get() = binding.previewHost
        var boundPreviewIdentity: String? = null
        private val imageRequests = FeedImageRequestBag()
        private var boundImageIdentity: String? = null
        private var boundThumbnailKey: String? = null
        private var boundStream: Stream? = null

        init {
            binding.root.setOnClickListener { boundStream?.let(::openStream) }
            binding.userImage.setOnClickListener { boundStream?.let(::openChannel) }
            binding.username.setOnClickListener { boundStream?.let(::openChannel) }
            binding.gameName.setOnClickListener { boundStream?.let(::openGame) }
            binding.multiview.setOnClickListener { boundStream?.let(::openMultiview) }
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
                    val selectionMode = onStreamClick != null
                    multiview.visibility = if (selectionMode || item.channelLogin.isNullOrBlank()) View.GONE else View.VISIBLE
                    if (item.channelImage != null) {
                        userImage.visibility = View.VISIBLE
                        userImage.contentDescription = item.channelName?.let {
                            context.getString(R.string.player_open_channel, it)
                        }
                        prepareStreamProfileImage(userImage, item)
                        thumbnailLoadScheduler.runOrDefer(this@PagingViewHolder, binding.userImage) {
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
                    if (item.channelName != null) {
                        username.visibility = View.VISIBLE
                        username.text = if (item.channelLogin != null && !item.channelLogin.equals(item.channelName, true)) {
                            when (uiPreferences.nameDisplay) {
                                "0" -> "${item.channelName}(${item.channelLogin})"
                                "1" -> item.channelName
                                else -> item.channelLogin
                            }
                        } else {
                            item.channelName
                        }
                    } else {
                        username.visibility = View.GONE
                    }
                    val streamTitle = item.title
                    if (!streamTitle.isNullOrBlank()) {
                        title.visibility = View.VISIBLE
                        title.text = streamTitle.trim()
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
                    if (item.viewerCount != null) {
                        viewers.visibility = View.VISIBLE
                        val count = item.viewerCount ?: 0
                        viewers.text = context.resources.getQuantityString(
                            R.plurals.viewers,
                            count,
                            TwitchApiHelper.formatCount(count, uiPreferences.truncateViewCount)
                        )
                    } else {
                        viewers.visibility = View.GONE
                    }
                    if (uiPreferences.showUptime && item.createdAt != null) {
                        val text = item.createdAt?.let {
                            Instant.parseOrNull(it)?.takeIf { time -> time.toEpochMilliseconds() > 0 }?.let { createdAt ->
                                val uptime = Clock.System.now() - createdAt
                                if (uptime.isPositive()) {
                                    DateUtils.formatElapsedTime(uptime.inWholeSeconds)
                                } else null
                            }
                        }
                        if (text != null) {
                            uptime.visibility = View.VISIBLE
                            uptime.text = text
                        } else {
                            uptime.visibility = View.GONE
                        }
                    } else {
                        uptime.visibility = View.GONE
                    }
                    if (!item.tags.isNullOrEmpty() && uiPreferences.showTags) {
                        bindStreamTags(tagViews, item.tags) { tag ->
                            if (selectionMode) {
                                onStreamClick.invoke(item)
                            } else {
                                selectTag(tag)
                            }
                        }
                    } else {
                        tagsLayout.visibility = View.GONE
                    }
                } else {
                    boundStream = null
                    userImage.setImageDrawable(null)
                    userImage.tag = null
                    thumbnail.setImageDrawable(null)
                    thumbnail.tag = null
                    username.visibility = View.GONE
                    title.visibility = View.GONE
                    gameName.visibility = View.GONE
                    viewers.visibility = View.GONE
                    uptime.visibility = View.GONE
                        clearStreamTags(tagViews)
                    tagsLayout.visibility = View.GONE
                    liveBadge.visibility = View.GONE
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
