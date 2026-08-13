package com.github.andreyasadchy.xtra.ui.statistics

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.StatisticsChannelItemBinding
import com.github.andreyasadchy.xtra.util.viewingstats.ChannelWatchTotal
import kotlin.math.roundToInt

class StatisticsChannelAdapter(
    private val onChannelClick: (ChannelWatchTotal) -> Unit,
) : ListAdapter<StatisticsChannelRow, StatisticsChannelAdapter.ViewHolder>(DIFF_CALLBACK) {

    fun submitChannels(items: List<ChannelWatchTotal>, totalWatchMs: Long) {
        submitList(
            items.map { channel ->
                StatisticsChannelRow(
                    channel = channel,
                    sharePercent = if (totalWatchMs > 0L) {
                        (channel.watchedMs.toDouble() * 100.0 / totalWatchMs)
                            .roundToInt()
                            .coerceIn(0, 100)
                    } else {
                        0
                    },
                )
            },
        )
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            StatisticsChannelItemBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onChannelClick,
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: StatisticsChannelItemBinding,
        private val onChannelClick: (ChannelWatchTotal) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: StatisticsChannelRow) {
            val item = row.channel
            val context = binding.root.context
            val name = item.channelName?.takeIf { it.isNotBlank() }
                ?: item.channelLogin?.takeIf { it.isNotBlank() }
                ?: item.channelId
            val share = row.sharePercent
            binding.channelName.text = name
            binding.channelDuration.text = context.getString(
                R.string.statistics_channel_duration,
                formatDuration(context, item.watchedMs),
                share,
            )
            binding.progress.progress = share
            binding.root.contentDescription = context.getString(
                R.string.statistics_channel_accessibility,
                name,
                formatDuration(context, item.watchedMs),
                share,
            )
            binding.root.setOnClickListener { onChannelClick(item) }
            binding.avatar.setImageDrawable(null)
            if (item.channelImage.isNullOrBlank()) {
                binding.avatar.alpha = 0.35f
            } else {
                binding.avatar.alpha = 1f
                binding.root.context.imageLoader.enqueue(
                    ImageRequest.Builder(context).apply {
                        data(item.channelImage)
                        transformations(CircleCropTransformation())
                        crossfade(true)
                        target(binding.avatar)
                    }.build()
                )
            }
        }

        private fun formatDuration(context: android.content.Context, milliseconds: Long): String {
            val minutes = milliseconds.coerceAtLeast(0L) / 60_000L
            val hours = minutes / 60L
            return when {
                hours > 0L -> context.getString(R.string.statistics_duration_hours, hours, minutes % 60L)
                minutes > 0L -> context.getString(R.string.statistics_duration_minutes, minutes)
                else -> context.getString(R.string.statistics_duration_less_than_minute)
            }
        }
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<StatisticsChannelRow>() {
            override fun areItemsTheSame(oldItem: StatisticsChannelRow, newItem: StatisticsChannelRow): Boolean =
                oldItem.channel.channelId == newItem.channel.channelId

            override fun areContentsTheSame(oldItem: StatisticsChannelRow, newItem: StatisticsChannelRow): Boolean =
                oldItem == newItem
        }
    }
}

data class StatisticsChannelRow(
    val channel: ChannelWatchTotal,
    val sharePercent: Int,
)
