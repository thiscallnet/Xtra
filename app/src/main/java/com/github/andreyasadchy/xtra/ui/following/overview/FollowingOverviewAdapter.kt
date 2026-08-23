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
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.UpcomingStream

data class FollowingOverviewSection(
    val key: String,
    val titleRes: Int,
    val emptyRes: Int,
    val title: CharSequence? = null,
    val streams: List<Stream> = emptyList(),
    val games: List<Game> = emptyList(),
    val videos: List<VideoHistory> = emptyList(),
    val scheduledStreams: List<UpcomingStream> = emptyList(),
    val isLoading: Boolean = false,
    val loadingType: FollowingOverviewLoadingType = FollowingOverviewLoadingType.STREAM,
    val showSeeAll: Boolean = true,
)

class FollowingOverviewAdapter(
    private val onStreamClick: (Stream) -> Unit,
    private val onVideoClick: (VideoHistory) -> Unit,
    private val onUpcomingClick: (UpcomingStream) -> Unit,
    private val onSeeAll: (String) -> Unit,
    private val onGameClick: (Game) -> Unit = {},
    private val onStreamShelfAttached: ((String, RecyclerView, (Int) -> Stream?) -> Unit)? = null,
    private val onStreamShelfDetached: ((String) -> Unit)? = null,
    private val onVideoShelfAttached: ((String, RecyclerView, (Int) -> VideoHistory?) -> Unit)? = null,
    private val onVideoShelfDetached: ((String) -> Unit)? = null,
) : ListAdapter<FollowingOverviewSection, FollowingOverviewAdapter.ViewHolder>(DIFF_CALLBACK) {

    private val recycledViewPool = RecyclerView.RecycledViewPool()
    private val videoRecycledViewPool = RecyclerView.RecycledViewPool()
    private val upcomingRecycledViewPool = RecyclerView.RecycledViewPool()
    private val gameRecycledViewPool = RecyclerView.RecycledViewPool()
    private val skeletonRecycledViewPool = RecyclerView.RecycledViewPool()

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

    override fun onViewRecycled(holder: ViewHolder) {
        holder.detachStreamShelf()
        holder.detachVideoShelf()
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(
        private val binding: ItemFollowingSectionBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private val shelfAdapter = StreamShelfAdapter(onStreamClick)
        private val videoShelfAdapter = VideoShelfAdapter(onVideoClick)
        private val upcomingShelfAdapter = UpcomingStreamShelfAdapter(onUpcomingClick)
        private val gameShelfAdapter = GameShelfAdapter(onGameClick)
        private val skeletonShelfAdapter = ShelfSkeletonAdapter()
        private var shelfType: ShelfType? = null
        private var boundStreamShelfKey: String? = null
        private var boundVideoShelfKey: String? = null

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
            section.title?.let { binding.sectionTitle.text = it } ?: binding.sectionTitle.setText(section.titleRes)
            val hasItems = section.streams.isNotEmpty() || section.games.isNotEmpty() || section.videos.isNotEmpty() || section.scheduledStreams.isNotEmpty()
            val showSkeleton = section.isLoading && !hasItems
            binding.emptyMessage.setText(section.emptyRes)
            binding.emptyMessage.visibility = if (hasItems || showSkeleton) android.view.View.GONE else android.view.View.VISIBLE
            binding.shelfRecyclerView.visibility = if (hasItems || showSkeleton) android.view.View.VISIBLE else android.view.View.GONE
            binding.seeAll.visibility = if (hasItems && section.showSeeAll) android.view.View.VISIBLE else android.view.View.GONE
            binding.seeAll.setOnClickListener { onSeeAll(section.key) }
            val nextShelfType = when {
                showSkeleton -> ShelfType.SKELETON
                section.games.isNotEmpty() -> ShelfType.GAME
                section.videos.isNotEmpty() -> ShelfType.VIDEO
                section.scheduledStreams.isNotEmpty() -> ShelfType.UPCOMING
                else -> ShelfType.STREAM
            }
            if (nextShelfType != ShelfType.STREAM || section.streams.isEmpty()) {
                detachStreamShelf()
            }
            if (nextShelfType != ShelfType.VIDEO || section.videos.isEmpty()) {
                detachVideoShelf()
            }
            if (shelfType != nextShelfType) {
                when (nextShelfType) {
                    ShelfType.VIDEO -> {
                        binding.shelfRecyclerView.swapAdapter(videoShelfAdapter, true)
                        binding.shelfRecyclerView.setRecycledViewPool(videoRecycledViewPool)
                    }
                    ShelfType.UPCOMING -> {
                        binding.shelfRecyclerView.swapAdapter(upcomingShelfAdapter, true)
                        binding.shelfRecyclerView.setRecycledViewPool(upcomingRecycledViewPool)
                    }
                    ShelfType.GAME -> {
                        binding.shelfRecyclerView.swapAdapter(gameShelfAdapter, true)
                        binding.shelfRecyclerView.setRecycledViewPool(gameRecycledViewPool)
                    }
                    ShelfType.SKELETON -> {
                        binding.shelfRecyclerView.swapAdapter(skeletonShelfAdapter, true)
                        binding.shelfRecyclerView.setRecycledViewPool(skeletonRecycledViewPool)
                    }
                    ShelfType.STREAM -> {
                        binding.shelfRecyclerView.swapAdapter(shelfAdapter, true)
                        binding.shelfRecyclerView.setRecycledViewPool(recycledViewPool)
                    }
                }
                shelfType = nextShelfType
            }
            when (nextShelfType) {
                ShelfType.VIDEO -> {
                    videoShelfAdapter.submitList(section.videos)
                    if (hasItems && boundVideoShelfKey != section.key) {
                        detachVideoShelf()
                        boundVideoShelfKey = section.key
                        onVideoShelfAttached?.invoke(section.key, binding.shelfRecyclerView) { position ->
                            videoShelfAdapter.currentList.getOrNull(position)
                        }
                    }
                }
                ShelfType.UPCOMING -> upcomingShelfAdapter.submitList(section.scheduledStreams)
                ShelfType.GAME -> gameShelfAdapter.submitList(section.games)
                ShelfType.SKELETON -> skeletonShelfAdapter.setLoadingType(section.loadingType)
                ShelfType.STREAM -> {
                    shelfAdapter.submitList(section.streams)
                    if (hasItems && boundStreamShelfKey != section.key) {
                        detachStreamShelf()
                        boundStreamShelfKey = section.key
                        onStreamShelfAttached?.invoke(section.key, binding.shelfRecyclerView) { position ->
                            shelfAdapter.currentList.getOrNull(position)
                        }
                    }
                }
            }
        }

        fun detachStreamShelf() {
            boundStreamShelfKey?.let(onStreamShelfDetached ?: {})
            boundStreamShelfKey = null
        }

        fun detachVideoShelf() {
            boundVideoShelfKey?.let(onVideoShelfDetached ?: {})
            boundVideoShelfKey = null
        }
    }

    private enum class ShelfType { STREAM, VIDEO, UPCOMING, GAME, SKELETON }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<FollowingOverviewSection>() {
            override fun areItemsTheSame(oldItem: FollowingOverviewSection, newItem: FollowingOverviewSection): Boolean =
                oldItem.key == newItem.key

            override fun areContentsTheSame(oldItem: FollowingOverviewSection, newItem: FollowingOverviewSection): Boolean =
                oldItem == newItem
        }
    }
}
