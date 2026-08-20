package com.github.andreyasadchy.xtra.ui.following.overview

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ItemFollowingSectionBinding
import com.github.andreyasadchy.xtra.model.VideoHistory
import com.github.andreyasadchy.xtra.model.ui.Stream

data class FollowingOverviewSection(
    val key: String,
    val titleRes: Int,
    val emptyRes: Int,
    val streams: List<Stream> = emptyList(),
    val videos: List<VideoHistory> = emptyList(),
    val isLoading: Boolean = false,
    val showSeeAll: Boolean = true,
)

class FollowingOverviewAdapter(
    private val onStreamClick: (Stream) -> Unit,
    private val onVideoClick: (VideoHistory) -> Unit,
    private val onSeeAll: (String) -> Unit,
) : ListAdapter<FollowingOverviewSection, FollowingOverviewAdapter.ViewHolder>(DIFF_CALLBACK) {

    private val recycledViewPool = RecyclerView.RecycledViewPool()
    private val videoRecycledViewPool = RecyclerView.RecycledViewPool()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).key.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemFollowingSectionBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemFollowingSectionBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private val shelfAdapter = StreamShelfAdapter(onStreamClick)
        private val videoShelfAdapter = VideoShelfAdapter(onVideoClick)
        private var shelfType: ShelfType? = null

        init {
            binding.shelfRecyclerView.apply {
                layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
                setRecycledViewPool(recycledViewPool)
                setHasFixedSize(true)
                itemAnimator = null
                isNestedScrollingEnabled = false
                clipToPadding = false
            }
        }

        fun bind(section: FollowingOverviewSection) {
            binding.sectionTitle.setText(section.titleRes)
            binding.emptyMessage.setText(if (section.isLoading) R.string.loading else section.emptyRes)
            val hasItems = section.streams.isNotEmpty() || section.videos.isNotEmpty()
            binding.emptyMessage.visibility = if (hasItems) android.view.View.GONE else android.view.View.VISIBLE
            binding.shelfRecyclerView.visibility = if (hasItems) android.view.View.VISIBLE else android.view.View.GONE
            binding.seeAll.visibility = if (hasItems && section.showSeeAll) android.view.View.VISIBLE else android.view.View.GONE
            binding.seeAll.setOnClickListener { onSeeAll(section.key) }
            val nextShelfType = if (section.videos.isNotEmpty()) ShelfType.VIDEO else ShelfType.STREAM
            if (shelfType != nextShelfType) {
                if (nextShelfType == ShelfType.VIDEO) {
                    binding.shelfRecyclerView.setRecycledViewPool(videoRecycledViewPool)
                    binding.shelfRecyclerView.adapter = videoShelfAdapter
                } else {
                    binding.shelfRecyclerView.setRecycledViewPool(recycledViewPool)
                    binding.shelfRecyclerView.adapter = shelfAdapter
                }
                shelfType = nextShelfType
            }
            if (nextShelfType == ShelfType.VIDEO) {
                videoShelfAdapter.submitList(section.videos)
            } else {
                shelfAdapter.submitList(section.streams)
            }
        }
    }

    private enum class ShelfType { STREAM, VIDEO }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<FollowingOverviewSection>() {
            override fun areItemsTheSame(oldItem: FollowingOverviewSection, newItem: FollowingOverviewSection): Boolean =
                oldItem.key == newItem.key

            override fun areContentsTheSame(oldItem: FollowingOverviewSection, newItem: FollowingOverviewSection): Boolean =
                oldItem == newItem
        }
    }
}
