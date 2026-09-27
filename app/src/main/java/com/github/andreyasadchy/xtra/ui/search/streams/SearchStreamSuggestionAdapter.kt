package com.github.andreyasadchy.xtra.ui.search.streams

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ItemSearchStreamSuggestionBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.common.FeedImageRequestBag
import com.github.andreyasadchy.xtra.ui.common.FeedImageRequestOwner
import com.github.andreyasadchy.xtra.ui.common.StreamThumbnailIdleScheduler
import com.github.andreyasadchy.xtra.ui.common.loadStreamProfileImage
import com.github.andreyasadchy.xtra.ui.common.loadStreamThumbnail
import com.github.andreyasadchy.xtra.ui.common.prepareStreamProfileImage
import com.github.andreyasadchy.xtra.ui.common.prepareStreamThumbnailImage
import com.github.andreyasadchy.xtra.ui.common.restoreWarmStreamProfileImage
import com.github.andreyasadchy.xtra.ui.common.restoreWarmStreamThumbnail
import com.github.andreyasadchy.xtra.ui.common.streamIdentity
import com.github.andreyasadchy.xtra.ui.common.thumbnailIdentity
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.TwitchApiHelper

class SearchStreamSuggestionAdapter(
    private val fragment: Fragment,
) : ListAdapter<Stream, SearchStreamSuggestionAdapter.ViewHolder>(DIFF_CALLBACK) {

    private val imageScheduler = StreamThumbnailIdleScheduler()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).streamIdentity().hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSearchStreamSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.beginImageBind(getItem(position))
        holder.bind(getItem(position))
    }

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)
        holder.resumeImageWork()
    }

    override fun onViewRecycled(holder: ViewHolder) {
        imageScheduler.clear(holder)
        holder.cancelImageWork()
        super.onViewRecycled(holder)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        imageScheduler.attachTo(recyclerView)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        imageScheduler.detach()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    inner class ViewHolder(
        private val binding: ItemSearchStreamSuggestionBinding,
    ) : RecyclerView.ViewHolder(binding.root), FeedImageRequestOwner {
        private val imageRequests = FeedImageRequestBag()
        private var boundStream: Stream? = null
        private var boundImageIdentity: String? = null
        private var boundThumbnailKey: String? = null

        init {
            binding.root.setOnClickListener {
                boundStream?.let { (fragment.activity as? MainActivity)?.startStream(it) }
            }
            binding.avatar.setOnClickListener { boundStream?.let(::openChannel) }
            binding.channelName.setOnClickListener { boundStream?.let(::openChannel) }
        }

        fun beginImageBind(stream: Stream) {
            imageScheduler.clear(this)
            imageRequests.cancel()
            boundImageIdentity = stream.streamIdentity()
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
            boundStream = null
            boundImageIdentity = null
            boundThumbnailKey = null
        }

        fun bind(stream: Stream) {
            boundStream = stream
            binding.channelName.text = stream.channelName.orEmpty()
            binding.streamTitle.text = stream.title.orEmpty()
            binding.root.contentDescription = listOfNotNull(stream.channelName, stream.title)
                .joinToString(", ")
            binding.avatar.contentDescription = stream.channelName?.let {
                binding.root.context.getString(R.string.player_open_channel, it)
            }
            binding.viewers.text = stream.viewerCount?.let { count ->
                binding.root.resources.getQuantityString(
                    R.plurals.viewers,
                    count,
                    TwitchApiHelper.formatCount(count, compact = true),
                )
            }.orEmpty()
            binding.viewers.visibility = if (stream.viewerCount != null) View.VISIBLE else View.GONE
            bindThumbnail(stream)
            bindProfileImage(stream)
        }

        fun resumeImageWork() {
            val stream = boundStream ?: return
            if (boundImageIdentity != stream.streamIdentity()) return
            bindThumbnail(stream)
            bindProfileImage(stream)
        }

        private fun bindThumbnail(stream: Stream) {
            prepareStreamThumbnailImage(binding.thumbnail, stream)
            restoreWarmStreamThumbnail(stream, binding.thumbnail)
            val key = "${stream.thumbnailIdentity()}|generation=${stream.thumbnailGeneration}"
            boundThumbnailKey = key
            imageScheduler.runOrDefer(this, binding.thumbnail) {
                if (!binding.root.isAttachedToWindow ||
                    boundImageIdentity != stream.streamIdentity() ||
                    boundThumbnailKey != key
                ) return@runOrDefer
                loadStreamThumbnail(
                    context = binding.root.context,
                    imageView = binding.thumbnail,
                    stream = stream,
                    scheduleFreshRequest = { freshRequest ->
                        imageScheduler.runOrDefer(this, binding.thumbnail, freshRequest)
                    },
                )?.let { imageRequests.replace(binding.thumbnail, it) }
            }
        }

        private fun bindProfileImage(stream: Stream) {
            if (stream.channelImage.isNullOrBlank()) {
                binding.avatar.setImageDrawable(null)
                return
            }
            prepareStreamProfileImage(binding.avatar, stream)
            restoreWarmStreamProfileImage(binding.root.context, binding.avatar, stream)
            imageScheduler.runOrDefer(this, binding.avatar) {
                if (binding.root.isAttachedToWindow && boundImageIdentity == stream.streamIdentity()) {
                    loadStreamProfileImage(binding.root.context, binding.avatar, stream)
                        ?.let { imageRequests.replace(binding.avatar, it) }
                }
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
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Stream>() {
            override fun areItemsTheSame(oldItem: Stream, newItem: Stream): Boolean =
                oldItem.streamIdentity() == newItem.streamIdentity()

            override fun areContentsTheSame(oldItem: Stream, newItem: Stream): Boolean =
                oldItem.id == newItem.id &&
                    oldItem.channelName == newItem.channelName &&
                    oldItem.channelImageURL == newItem.channelImageURL &&
                    oldItem.title == newItem.title &&
                    oldItem.thumbnailURL == newItem.thumbnailURL &&
                    oldItem.thumbnailGeneration == newItem.thumbnailGeneration &&
                    oldItem.viewerCount == newItem.viewerCount
        }
    }
}
