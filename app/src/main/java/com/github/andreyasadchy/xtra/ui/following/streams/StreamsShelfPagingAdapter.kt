package com.github.andreyasadchy.xtra.ui.following.streams

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
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
import com.github.andreyasadchy.xtra.ui.common.StreamThumbnailIdleScheduler
import com.github.andreyasadchy.xtra.ui.common.thumbnailIdentity
import com.github.andreyasadchy.xtra.ui.common.streamContentsSame
import com.github.andreyasadchy.xtra.ui.common.streamIdentity
import com.github.andreyasadchy.xtra.ui.common.streamThumbnailOnlyChanged
import com.github.andreyasadchy.xtra.ui.common.StreamThumbnailChangedPayload
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.multiview.MultiviewFragment

class StreamsShelfPagingAdapter(
    private val fragment: Fragment,
    private val selectTag: (String) -> Unit,
) : PagingDataAdapter<Stream, StreamsShelfPagingAdapter.ViewHolder>(DIFF_CALLBACK) {

    private val thumbnailLoadScheduler = StreamThumbnailIdleScheduler()

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
    }

    internal fun detachImageScheduler() {
        thumbnailLoadScheduler.detach()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemStreamShelfBinding.inflate(LayoutInflater.from(parent.context), parent, false))
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
        recycleViewHolder(holder)
        super.onViewRecycled(holder)
    }

    internal fun recycleViewHolder(holder: ViewHolder) {
        (fragment.requireContext().applicationContext as XtraApp).xtraModule.streamPreviewCoordinator
            .detachSurface(holder.previewSurface)
        holder.boundPreviewIdentity = null
        thumbnailLoadScheduler.clear(holder)
        holder.cancelImageWork()
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
        private var boundTags: List<String> = emptyList()

        init {
            binding.root.setOnClickListener {
                boundStream?.let { (fragment.activity as? MainActivity)?.startStream(it) }
            }
            binding.avatar.setOnClickListener { boundStream?.let(::openChannel) }
            binding.channel.setOnClickListener { boundStream?.let(::openChannel) }
            binding.category.setOnClickListener { boundStream?.let(::openGame) }
            binding.multiview.setOnClickListener { boundStream?.let(::openMultiview) }
            binding.tagOne.setOnClickListener { boundTags.getOrNull(0)?.let(selectTag) }
            binding.tagTwo.setOnClickListener { boundTags.getOrNull(1)?.let(selectTag) }
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
            boundStream = null
            boundTags = emptyList()
        }

        fun bindThumbnail(item: Stream?) {
            val stream = item ?: return
            prepareStreamThumbnailImage(binding.thumbnail, stream)
            restoreWarmStreamThumbnail(stream, binding.thumbnail)
            val key = "${stream.thumbnailIdentity()}|generation=${stream.thumbnailGeneration}"
            boundThumbnailKey = key
            thumbnailLoadScheduler.runOrDefer(this@ViewHolder, binding.thumbnail) {
                if (!binding.root.isAttachedToWindow ||
                    boundImageIdentity != stream.streamIdentity() ||
                    boundThumbnailKey != key
                ) return@runOrDefer
                loadStreamThumbnail(
                    context = binding.root.context,
                    imageView = binding.thumbnail,
                    stream = stream,
                    scheduleFreshRequest = { freshRequest ->
                        thumbnailLoadScheduler.runOrDefer(this@ViewHolder, binding.thumbnail, freshRequest)
                    },
                )
                    ?.let { imageRequests.replace(binding.thumbnail, it) }
            }
        }

        fun bind(item: Stream?) {
            val context = fragment.requireContext()
            val uiPreferences = FeedUiPreferencesStore.current(context)
            val presentation = item?.let { StreamCardPresentationCache.get(it, uiPreferences) }
            boundStream = item
            if (item != null && presentation == null) {
                StreamCardPresentationCache.request(context, item, uiPreferences) {
                    if (boundStream === item && binding.root.isAttachedToWindow) applyPresentation(it)
                }
            }
            val nextPreviewIdentity = item?.streamIdentity()
            if (boundPreviewIdentity != nextPreviewIdentity) {
                (context.applicationContext as XtraApp).xtraModule.streamPreviewCoordinator
                    .detachSurface(previewSurface)
                boundPreviewIdentity = nextPreviewIdentity
            }
            with(binding) {
                if (item == null) {
                    reset()
                    return
                }

                bindThumbnail(item)
                thumbnail.contentDescription = item.title?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.live)
                liveBadge.visibility = View.VISIBLE
                liveBadge.text = context.getString(R.string.live)

                viewers.text = presentation?.viewerLabel ?: item.viewerCount?.toString().orEmpty()
                viewers.visibility = if (viewers.text.isNullOrBlank()) View.GONE else View.VISIBLE

                val uptimeText = presentation?.uptime
                uptime.text = uptimeText.orEmpty()
                uptime.visibility = if (uptimeText.isNullOrBlank()) View.GONE else View.VISIBLE

                title.text = presentation?.title ?: item.title.orEmpty()
                title.visibility = if (title.text.isNullOrBlank()) View.GONE else View.VISIBLE
                if (presentation?.username != null || item.channelName != null) {
                    channel.visibility = View.VISIBLE
                    channel.text = presentation?.username ?: item.channelName.orEmpty()
                } else {
                    channel.text = null
                    channel.visibility = View.GONE
                }
                category.text = presentation?.gameName ?: item.gameName.orEmpty()
                category.visibility = if (category.text.isNullOrBlank()) View.GONE else View.VISIBLE

                if (presentation?.channelImage != null || item.channelImage != null) {
                    avatar.visibility = View.VISIBLE
                    prepareStreamProfileImage(avatar, item)
                    restoreWarmStreamProfileImage(context, avatar, item)
                    thumbnailLoadScheduler.runOrDefer(this@ViewHolder, avatar) {
                        if (binding.root.isAttachedToWindow && boundImageIdentity == item.streamIdentity()) {
                            loadStreamProfileImage(context, avatar, item)?.let {
                                imageRequests.replace(binding.avatar, it)
                            }
                        }
                    }
                } else {
                    avatar.visibility = View.INVISIBLE
                    avatar.setImageDrawable(null)
                    avatar.tag = null
                }

                multiview.visibility = if (item.channelLogin.isNullOrBlank()) View.GONE else View.VISIBLE

                val tags = presentation?.tags?.take(2)
                    ?: if (uiPreferences.showTags) item.tags.orEmpty().take(2) else emptyList()
                boundTags = tags
                tagOne.text = tags.getOrNull(0).orEmpty()
                tagOne.visibility = if (tags.isNotEmpty()) View.VISIBLE else View.GONE
                tagTwo.text = tags.getOrNull(1).orEmpty()
                tagTwo.visibility = if (tags.size > 1) View.VISIBLE else View.GONE
            }
        }

        private fun applyPresentation(presentation: com.github.andreyasadchy.xtra.ui.common.StreamCardPresentation) {
            if (boundStream == null) return
            with(binding) {
                viewers.text = presentation.viewerLabel.orEmpty()
                viewers.visibility = if (viewers.text.isNullOrBlank()) View.GONE else View.VISIBLE
                uptime.text = presentation.uptime.orEmpty()
                uptime.visibility = if (uptime.text.isNullOrBlank()) View.GONE else View.VISIBLE
                title.text = presentation.title.orEmpty()
                title.visibility = if (title.text.isNullOrBlank()) View.GONE else View.VISIBLE
                channel.text = presentation.username.orEmpty()
                channel.visibility = if (channel.text.isNullOrBlank()) View.GONE else View.VISIBLE
                category.text = presentation.gameName.orEmpty()
                category.visibility = if (category.text.isNullOrBlank()) View.GONE else View.VISIBLE
                val tags = presentation.tags.take(2)
                boundTags = tags
                tagOne.text = tags.getOrNull(0).orEmpty()
                tagOne.visibility = if (tags.isNotEmpty()) View.VISIBLE else View.GONE
                tagTwo.text = tags.getOrNull(1).orEmpty()
                tagTwo.visibility = if (tags.size > 1) View.VISIBLE else View.GONE
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
                )
            )
        }

        private fun openGame(stream: Stream) {
            if (stream.gameName.isNullOrBlank()) return
            fragment.findNavController().navigate(
                GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                    gameId = stream.gameId,
                    gameSlug = stream.gameSlug,
                    gameName = stream.gameName,
                )
            )
        }

        private fun openMultiview(stream: Stream) {
            (fragment.activity as? MainActivity)?.let { activity ->
                if (activity.playerFragment != null) activity.closePlayer()
            }
            fragment.findNavController().navigate(R.id.multiviewFragment, MultiviewFragment.arguments(stream))
        }

        private fun reset() {
            with(binding) {
                boundStream = null
                boundTags = emptyList()
                avatar.visibility = View.INVISIBLE
                avatar.setImageDrawable(null)
                avatar.tag = null
                thumbnail.setImageDrawable(null)
                thumbnail.tag = null
                title.text = null
                title.visibility = View.GONE
                channel.text = null
                channel.visibility = View.GONE
                category.text = null
                category.visibility = View.GONE
                tagOne.text = null
                tagOne.visibility = View.GONE
                tagTwo.text = null
                tagTwo.visibility = View.GONE
                viewers.text = null
                viewers.visibility = View.GONE
                uptime.text = null
                uptime.visibility = View.GONE
                liveBadge.visibility = View.GONE
                multiview.visibility = View.GONE
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
