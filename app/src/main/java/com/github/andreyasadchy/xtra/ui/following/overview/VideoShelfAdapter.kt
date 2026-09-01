package com.github.andreyasadchy.xtra.ui.following.overview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.ItemVideoShelfBinding
import com.github.andreyasadchy.xtra.model.VideoHistory
import com.github.andreyasadchy.xtra.ui.common.FeedImageRequestBag
import com.github.andreyasadchy.xtra.ui.common.FeedImageRequestOwner
import com.github.andreyasadchy.xtra.ui.common.StreamThumbnailIdleScheduler
import com.github.andreyasadchy.xtra.ui.common.VideoHistoryCardPresentationCache
import com.github.andreyasadchy.xtra.ui.common.restoreDecodedMemoryImage
import com.github.andreyasadchy.xtra.ui.common.thumbnailState
import com.github.andreyasadchy.xtra.ui.tv.TvFocusHelper
import com.github.andreyasadchy.xtra.util.TwitchApiHelper

class VideoShelfAdapter(
    private val onVideoClick: (VideoHistory) -> Unit,
) : ListAdapter<VideoHistory, VideoShelfAdapter.ViewHolder>(DIFF_CALLBACK) {

    private val imageLoadScheduler = StreamThumbnailIdleScheduler()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemVideoShelfBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        (parent as? RecyclerView)?.let { ShelfCardSizing.apply(binding.root, it) }
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.beginImageBind(getItem(position))
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.detachPreview()
        imageLoadScheduler.clear(holder)
        holder.cancelImageWork()
        super.onViewRecycled(holder)
    }

    private val layoutChangeListener = View.OnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
        if (right - left != oldRight - oldLeft) {
            val shelf = view as RecyclerView
            repeat(shelf.childCount) { index -> ShelfCardSizing.apply(shelf.getChildAt(index), shelf) }
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        imageLoadScheduler.attachTo(recyclerView)
        VideoHistoryCardPresentationCache.prewarm(currentList)
        recyclerView.addOnLayoutChangeListener(layoutChangeListener)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        imageLoadScheduler.detach()
        recyclerView.removeOnLayoutChangeListener(layoutChangeListener)
        super.onDetachedFromRecyclerView(recyclerView)
    }

    inner class ViewHolder(
        private val binding: ItemVideoShelfBinding,
    ) : RecyclerView.ViewHolder(binding.root), FeedImageRequestOwner {
        val previewSurface get() = binding.previewHost
        private var boundPreviewIdentity: String? = null
        private val imageRequests = FeedImageRequestBag()
        private var boundImageIdentity: String? = null
        private var boundItem: VideoHistory? = null

        init {
            binding.root.setOnClickListener { boundItem?.let(onVideoClick) }
            TvFocusHelper.install(binding.root)
        }

        private val streamPreviewCoordinator
            get() = (binding.root.context.applicationContext as XtraApp).xtraModule.streamPreviewCoordinator

        fun beginImageBind(item: VideoHistory) {
            imageLoadScheduler.clear(this)
            imageRequests.cancel()
            boundImageIdentity = "vod:${item.id}"
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
            boundItem = null
        }

        fun bind(item: VideoHistory) {
            val context = binding.root.context
            boundItem = item
            val presentation = VideoHistoryCardPresentationCache.get(item)
            if (presentation == null) {
                VideoHistoryCardPresentationCache.request(item) {
                    if (boundItem === item && binding.root.isAttachedToWindow) applyPresentation(it)
                }
            }
            val nextPreviewIdentity = "vod:${item.id}"
            if (boundPreviewIdentity != nextPreviewIdentity) {
                streamPreviewCoordinator.detachSurface(previewSurface)
                boundPreviewIdentity = nextPreviewIdentity
            }
            val identity = "vod:${item.id}"
            val thumbnailUrl = presentation?.thumbnailUrl ?: item.thumbnailURL?.let(TwitchApiHelper::getVideoThumbnail)
            val thumbnailKey = "xtra:vod-thumbnail:$identity|$thumbnailUrl"
            if (binding.thumbnail.tag != thumbnailKey) {
                binding.thumbnail.setImageDrawable(null)
                binding.thumbnail.tag = thumbnailKey
            }
            val thumbnailRestored = restoreDecodedMemoryImage(thumbnailKey, binding.thumbnail)
            if (!thumbnailRestored) imageLoadScheduler.runOrDefer(this@ViewHolder, binding.thumbnail) {
                if (!binding.root.isAttachedToWindow || boundImageIdentity != identity) return@runOrDefer
                imageRequests.replace(binding.thumbnail, context.imageLoader.enqueue(ImageRequest.Builder(context).apply {
                    data(thumbnailUrl)
                    memoryCacheKey(thumbnailKey)
                    diskCachePolicy(CachePolicy.ENABLED)
                    crossfade(false)
                    target(binding.thumbnail)
                    thumbnailState()
                }.build()))
            }
            binding.title.text = item.title.orEmpty()
            binding.channel.text = item.channelName.orEmpty()
            binding.category.text = item.gameName.orEmpty()
            binding.channel.visibility = if (binding.channel.text.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.category.visibility = if (binding.category.text.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.duration.text = presentation?.duration.orEmpty()
            binding.duration.visibility = if (binding.duration.text.isNullOrBlank()) View.GONE else View.VISIBLE
            val progress = item.durationSeconds?.takeIf { it > 0 && item.position > 0 }?.let { item.position.toFloat() / (it * 1000L) }
            binding.progress.visibility = if (progress != null) View.VISIBLE else View.GONE
            binding.progress.scaleX = progress?.coerceIn(0f, 1f) ?: 0f
            val avatarUrl = presentation?.avatarUrl ?: item.channelImageURL?.let(TwitchApiHelper::getProfileImage)
            binding.avatar.visibility = if (avatarUrl.isNullOrBlank()) View.INVISIBLE else View.VISIBLE
            if (avatarUrl.isNullOrBlank()) {
                binding.avatar.setImageDrawable(null)
                binding.avatar.tag = null
            } else {
                val avatarKey = "xtra:vod-avatar:$identity|$avatarUrl|round=true"
                if (binding.avatar.tag != avatarKey) {
                    binding.avatar.setImageDrawable(null)
                    binding.avatar.tag = avatarKey
                }
                val avatarRestored = restoreDecodedMemoryImage(avatarKey, binding.avatar)
                if (!avatarRestored) imageLoadScheduler.runOrDefer(this@ViewHolder, binding.avatar) {
                    if (!binding.root.isAttachedToWindow || boundImageIdentity != identity) return@runOrDefer
                    imageRequests.replace(binding.avatar, context.imageLoader.enqueue(ImageRequest.Builder(context).apply {
                        data(avatarUrl)
                        memoryCacheKey(avatarKey)
                        diskCachePolicy(CachePolicy.ENABLED)
                        transformations(CircleCropTransformation())
                        target(binding.avatar)
                    }.build()))
                }
            }
        }

        private fun applyPresentation(presentation: com.github.andreyasadchy.xtra.ui.common.VideoHistoryCardPresentation) {
            if (boundItem == null) return
            binding.duration.text = presentation.duration.orEmpty()
            binding.duration.visibility = if (binding.duration.text.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        fun detachPreview() {
            streamPreviewCoordinator.detachSurface(previewSurface)
            boundPreviewIdentity = null
        }
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<VideoHistory>() {
            override fun areItemsTheSame(oldItem: VideoHistory, newItem: VideoHistory): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: VideoHistory, newItem: VideoHistory): Boolean = oldItem == newItem
        }
    }
}
