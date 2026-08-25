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
import com.github.andreyasadchy.xtra.databinding.ItemUpcomingStreamShelfBinding
import com.github.andreyasadchy.xtra.model.ui.UpcomingStream
import com.github.andreyasadchy.xtra.util.TwitchApiHelper

class UpcomingStreamShelfAdapter(
    private val onUpcomingClick: (UpcomingStream) -> Unit,
) : ListAdapter<UpcomingStream, UpcomingStreamShelfAdapter.ViewHolder>(DIFF_CALLBACK) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemUpcomingStreamShelfBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), showPreview = position == 0)
    }

    inner class ViewHolder(
        private val binding: ItemUpcomingStreamShelfBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: UpcomingStream, showPreview: Boolean) {
            val context = binding.root.context
            binding.root.setOnClickListener { onUpcomingClick(item) }
            val avatarUrl = item.channelImageURL?.let(TwitchApiHelper::getProfileImage)
            context.imageLoader.enqueue(ImageRequest.Builder(context).apply {
                data(avatarUrl)
                diskCachePolicy(CachePolicy.ENABLED)
                transformations(CircleCropTransformation())
                crossfade(true)
                target(binding.avatar)
            }.build())
            binding.avatar.visibility = if (avatarUrl.isNullOrBlank()) View.INVISIBLE else View.VISIBLE
            binding.channel.text = item.channelName ?: item.channelLogin.orEmpty()
            binding.channel.visibility = if (binding.channel.text.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.title.text = item.title?.takeIf(String::isNotBlank)
                ?: context.getString(R.string.following_untitled_stream)
            binding.title.visibility = View.VISIBLE
            binding.category.text = item.gameName.orEmpty()
            binding.category.visibility = if (binding.category.text.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.startTime.text = formatStartTime(context, item.startTimeMillis)

            val previewUrl = item.previewImageURL?.takeIf { showPreview && it.isNotBlank() }
            binding.previewHost.visibility = if (previewUrl == null) View.GONE else View.VISIBLE
            binding.previewImage.setImageDrawable(null)
            if (previewUrl != null) {
                context.imageLoader.enqueue(ImageRequest.Builder(context).apply {
                    data(previewUrl)
                    diskCachePolicy(CachePolicy.ENABLED)
                    crossfade(true)
                    target(binding.previewImage)
                }.build())
            }
        }
    }

    private fun formatStartTime(context: android.content.Context, startTimeMillis: Long): String {
        val time = DateUtils.formatDateTime(context, startTimeMillis, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_TIME)
        return if (DateUtils.isToday(startTimeMillis)) {
            context.getString(R.string.following_starts_today, time)
        } else {
            DateUtils.formatDateTime(
                context,
                startTimeMillis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
            )
        }
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<UpcomingStream>() {
            override fun areItemsTheSame(oldItem: UpcomingStream, newItem: UpcomingStream): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: UpcomingStream, newItem: UpcomingStream): Boolean = oldItem == newItem
        }
    }
}
