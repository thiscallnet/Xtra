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
        private val binding: ItemUpcomingStreamShelfBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: UpcomingStream) {
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
            binding.title.text = item.title.orEmpty()
            binding.title.visibility = if (binding.title.text.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.category.text = item.gameName.orEmpty()
            binding.category.visibility = if (binding.category.text.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.startTime.text = formatStartTime(context, item.startTimeMillis)
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
