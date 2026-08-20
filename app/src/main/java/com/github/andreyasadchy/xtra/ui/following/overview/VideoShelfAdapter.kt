package com.github.andreyasadchy.xtra.ui.following.overview

import android.text.format.DateUtils
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
import com.github.andreyasadchy.xtra.databinding.ItemVideoShelfBinding
import com.github.andreyasadchy.xtra.model.VideoHistory
import com.github.andreyasadchy.xtra.util.TwitchApiHelper

class VideoShelfAdapter(
    private val onVideoClick: (VideoHistory) -> Unit,
) : ListAdapter<VideoHistory, VideoShelfAdapter.ViewHolder>(DIFF_CALLBACK) {

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
        (holder.itemView.parent as? RecyclerView)?.let { ShelfCardSizing.apply(holder.itemView, it) }
        holder.bind(getItem(position))
    }

    private val layoutChangeListener = View.OnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
        if (right - left != oldRight - oldLeft) {
            val shelf = view as RecyclerView
            repeat(shelf.childCount) { index -> ShelfCardSizing.apply(shelf.getChildAt(index), shelf) }
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.addOnLayoutChangeListener(layoutChangeListener)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeOnLayoutChangeListener(layoutChangeListener)
        super.onDetachedFromRecyclerView(recyclerView)
    }

    inner class ViewHolder(
        private val binding: ItemVideoShelfBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: VideoHistory) {
            val context = binding.root.context
            binding.root.setOnClickListener { onVideoClick(item) }
            context.imageLoader.enqueue(ImageRequest.Builder(context).apply {
                data(item.thumbnailURL?.let(TwitchApiHelper::getVideoThumbnail))
                diskCachePolicy(CachePolicy.ENABLED)
                crossfade(true)
                target(binding.thumbnail)
            }.build())
            binding.title.text = item.title.orEmpty()
            binding.channel.text = item.channelName.orEmpty()
            binding.category.text = item.gameName.orEmpty()
            binding.channel.visibility = if (binding.channel.text.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.category.visibility = if (binding.category.text.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.duration.text = item.durationSeconds?.let { DateUtils.formatElapsedTime(it.toLong()) }.orEmpty()
            binding.duration.visibility = if (binding.duration.text.isNullOrBlank()) View.GONE else View.VISIBLE
            val progress = item.durationSeconds?.takeIf { it > 0 }?.let { item.position.toFloat() / (it * 1000L) }
            binding.progress.visibility = if (progress != null) View.VISIBLE else View.GONE
            binding.progress.scaleX = progress?.coerceIn(0f, 1f) ?: 0f
            val avatarUrl = item.channelImageURL?.let(TwitchApiHelper::getProfileImage)
            context.imageLoader.enqueue(ImageRequest.Builder(context).apply {
                data(avatarUrl)
                diskCachePolicy(CachePolicy.ENABLED)
                transformations(CircleCropTransformation())
                target(binding.avatar)
            }.build())
            binding.avatar.visibility = if (avatarUrl.isNullOrBlank()) View.INVISIBLE else View.VISIBLE
        }
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<VideoHistory>() {
            override fun areItemsTheSame(oldItem: VideoHistory, newItem: VideoHistory): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: VideoHistory, newItem: VideoHistory): Boolean = oldItem == newItem
        }
    }
}
