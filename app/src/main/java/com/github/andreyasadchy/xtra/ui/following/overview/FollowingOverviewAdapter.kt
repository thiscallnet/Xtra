package com.github.andreyasadchy.xtra.ui.following.overview

import android.os.Parcelable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ItemFollowingSectionBinding
import com.github.andreyasadchy.xtra.model.VideoHistory
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.UpcomingStream
import com.github.andreyasadchy.xtra.ui.common.streamContentsSame
import com.github.andreyasadchy.xtra.ui.common.streamIdentity

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
    val hasResolved: Boolean = false,
    val loadingType: FollowingOverviewLoadingType = FollowingOverviewLoadingType.STREAM,
    val showSeeAll: Boolean = true,
    val isFeatured: Boolean = false,
)

class FollowingOverviewAdapter(
    private val onStreamClick: (Stream) -> Unit,
    private val onVideoClick: (VideoHistory) -> Unit,
    private val onUpcomingClick: (UpcomingStream) -> Unit,
    private val onSeeAll: (String) -> Unit,
    private val onGameClick: (Game) -> Unit = {},
    private val onStreamShelfAttached: ((String, RecyclerView, (Int) -> Stream?, Boolean) -> Unit)? = null,
    private val onStreamShelfDetached: ((String) -> Unit)? = null,
    private val onVideoShelfAttached: ((String, RecyclerView, (Int) -> VideoHistory?) -> Unit)? = null,
    private val onVideoShelfDetached: ((String) -> Unit)? = null,
) : ListAdapter<FollowingOverviewSection, FollowingOverviewAdapter.ViewHolder>(DIFF_CALLBACK) {

    private val recycledViewPool = RecyclerView.RecycledViewPool()
    private val videoRecycledViewPool = RecyclerView.RecycledViewPool()
    private val upcomingRecycledViewPool = RecyclerView.RecycledViewPool()
    private val gameRecycledViewPool = RecyclerView.RecycledViewPool()
    private val featuredRecycledViewPool = RecyclerView.RecycledViewPool()
    private val skeletonRecycledViewPool = RecyclerView.RecycledViewPool()
    private val shelfLayoutStates = mutableMapOf<String, Parcelable>()

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
        holder.saveShelfState()
        holder.detachStreamShelf()
        holder.detachVideoShelf()
        holder.clearBoundSection()
        super.onViewRecycled(holder)
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        holder.saveShelfState()
        super.onViewDetachedFromWindow(holder)
    }

        inner class ViewHolder(
        private val binding: ItemFollowingSectionBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private val shelfAdapter = StreamShelfAdapter(onStreamClick)
        private val videoShelfAdapter = VideoShelfAdapter(onVideoClick)
        private val upcomingShelfAdapter = UpcomingStreamShelfAdapter(onUpcomingClick)
        private val gameShelfAdapter = GameShelfAdapter(onGameClick)
        private val featuredShelfAdapter = FeaturedStreamShelfAdapter(onStreamClick)
        private val skeletonShelfAdapter = ShelfSkeletonAdapter()
        private var shelfType: ShelfType? = null
        private var boundStreamShelfKey: String? = null
        private var boundVideoShelfKey: String? = null
        private var boundSectionKey: String? = null
        private var submittedStreams: List<Stream>? = null
        private var submittedGames: List<Game>? = null
        private var submittedVideos: List<VideoHistory>? = null
        private var submittedScheduledStreams: List<UpcomingStream>? = null

        init {
            configureShelfLayout(ShelfType.STREAM)
            binding.shelfRecyclerView.apply {
                setRecycledViewPool(recycledViewPool)
                setHasFixedSize(true)
                itemAnimator = null
                isNestedScrollingEnabled = false
                clipToPadding = false
            }
            binding.seeAll.setOnClickListener {
                boundSectionKey?.let(onSeeAll)
            }
            binding.heroPrevious.setOnClickListener {
                featuredShelfAdapter.scrollBy(binding.shelfRecyclerView, -1)
            }
            binding.heroNext.setOnClickListener {
                featuredShelfAdapter.scrollBy(binding.shelfRecyclerView, 1)
            }
        }

        fun bind(section: FollowingOverviewSection) {
            if (boundSectionKey != section.key) {
                saveShelfState()
                boundSectionKey = section.key
                submittedStreams = null
                submittedGames = null
                submittedVideos = null
                submittedScheduledStreams = null
            }
            section.title?.let { binding.sectionTitle.text = it } ?: binding.sectionTitle.setText(section.titleRes)
            val hasItems = section.streams.isNotEmpty() || section.games.isNotEmpty() || section.videos.isNotEmpty() || section.scheduledStreams.isNotEmpty()
            val showSkeleton = section.isLoading && !hasItems && !section.hasResolved
            binding.emptyMessage.setText(section.emptyRes)
            binding.emptyMessage.visibility = if (hasItems || showSkeleton) android.view.View.GONE else android.view.View.VISIBLE
            binding.shelfRecyclerView.visibility = if (hasItems || showSkeleton) android.view.View.VISIBLE else android.view.View.GONE
            binding.seeAll.visibility = if (hasItems && section.showSeeAll) android.view.View.VISIBLE else android.view.View.GONE
            val nextShelfType = when {
                showSkeleton -> ShelfType.SKELETON
                section.isFeatured && section.streams.isNotEmpty() -> ShelfType.FEATURED
                section.games.isNotEmpty() -> ShelfType.GAME
                section.videos.isNotEmpty() -> ShelfType.VIDEO
                section.scheduledStreams.isNotEmpty() -> ShelfType.UPCOMING
                else -> ShelfType.STREAM
            }
            if ((nextShelfType != ShelfType.STREAM && nextShelfType != ShelfType.FEATURED) || section.streams.isEmpty()) {
                detachStreamShelf()
            }
            if (nextShelfType != ShelfType.VIDEO || section.videos.isEmpty()) {
                detachVideoShelf()
            }
            if (shelfType != nextShelfType) {
                when (nextShelfType) {
                    ShelfType.STREAM, ShelfType.FEATURED -> submittedStreams = null
                    ShelfType.VIDEO -> submittedVideos = null
                    ShelfType.UPCOMING -> submittedScheduledStreams = null
                    ShelfType.GAME -> submittedGames = null
                    ShelfType.SKELETON -> Unit
                }
                configureShelfLayout(nextShelfType)
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
                    ShelfType.FEATURED -> {
                        binding.shelfRecyclerView.swapAdapter(featuredShelfAdapter, true)
                        binding.shelfRecyclerView.setRecycledViewPool(featuredRecycledViewPool)
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
            binding.heroPrevious.visibility = if (section.isFeatured && section.streams.size > 1) android.view.View.VISIBLE else android.view.View.GONE
            binding.heroNext.visibility = if (section.isFeatured && section.streams.size > 1) android.view.View.VISIBLE else android.view.View.GONE
            when (nextShelfType) {
                ShelfType.VIDEO -> {
                    if (submittedVideos == null || submittedVideos != section.videos) {
                        videoShelfAdapter.submitList(section.videos)
                        submittedVideos = section.videos
                    }
                    restoreShelfState(section.key)
                    if (hasItems && boundVideoShelfKey != section.key) {
                        detachVideoShelf()
                        boundVideoShelfKey = section.key
                        onVideoShelfAttached?.invoke(section.key, binding.shelfRecyclerView) { position ->
                            videoShelfAdapter.currentList.getOrNull(position)
                        }
                    }
                }
                ShelfType.UPCOMING -> {
                    if (submittedScheduledStreams == null || submittedScheduledStreams != section.scheduledStreams) {
                        upcomingShelfAdapter.submitList(section.scheduledStreams)
                        submittedScheduledStreams = section.scheduledStreams
                    }
                    restoreShelfState(section.key)
                }
                ShelfType.GAME -> {
                    if (submittedGames == null || !gameListsSame(submittedGames!!, section.games)) {
                        gameShelfAdapter.submitList(section.games)
                        submittedGames = section.games
                    }
                    restoreShelfState(section.key)
                }
                ShelfType.FEATURED -> {
                    if (submittedStreams == null || !streamListsSame(submittedStreams!!, section.streams)) {
                        featuredShelfAdapter.submitList(section.streams)
                        submittedStreams = section.streams
                    }
                    if (shelfLayoutStates[section.key] == null) {
                        featuredShelfAdapter.centerInitialCard(binding.shelfRecyclerView)
                    }
                    restoreShelfState(section.key)
                    if (hasItems && boundStreamShelfKey != section.key) {
                        detachStreamShelf()
                        boundStreamShelfKey = section.key
                        onStreamShelfAttached?.invoke(section.key, binding.shelfRecyclerView, { position ->
                            featuredShelfAdapter.currentList.getOrNull(position)
                        }, true)
                    }
                }
                ShelfType.SKELETON -> skeletonShelfAdapter.setLoadingType(section.loadingType)
                ShelfType.STREAM -> {
                    if (submittedStreams == null || !streamListsSame(submittedStreams!!, section.streams)) {
                        shelfAdapter.submitList(section.streams)
                        submittedStreams = section.streams
                    }
                    restoreShelfState(section.key)
                    if (hasItems && boundStreamShelfKey != section.key) {
                        detachStreamShelf()
                        boundStreamShelfKey = section.key
                        onStreamShelfAttached?.invoke(section.key, binding.shelfRecyclerView, { position ->
                            shelfAdapter.currentList.getOrNull(position)
                        }, false)
                    }
                }
            }
        }

        private fun configureShelfLayout(type: ShelfType) {
            val shelf = binding.shelfRecyclerView
            if (type == ShelfType.UPCOMING) {
                val spanCount = if (shelf.resources.configuration.smallestScreenWidthDp >= 600) 2 else 1
                shelf.layoutManager = GridLayoutManager(shelf.context, spanCount).apply {
                    spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int =
                            if (spanCount > 1 && position == 0) spanCount else 1
                    }
                }
                shelf.setHasFixedSize(false)
            } else {
                shelf.layoutManager = LinearLayoutManager(shelf.context, RecyclerView.HORIZONTAL, false)
                shelf.setHasFixedSize(true)
            }
        }

        fun saveShelfState() {
            val key = boundSectionKey ?: return
            binding.shelfRecyclerView.layoutManager?.onSaveInstanceState()?.let { shelfLayoutStates[key] = it }
        }

        private fun restoreShelfState(key: String) {
            val state = shelfLayoutStates.remove(key) ?: return
            binding.shelfRecyclerView.post {
                if (boundSectionKey == key) {
                    binding.shelfRecyclerView.layoutManager?.onRestoreInstanceState(state)
                } else {
                    shelfLayoutStates.putIfAbsent(key, state)
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

        fun clearBoundSection() {
            boundSectionKey = null
        }
    }

    private enum class ShelfType { STREAM, FEATURED, VIDEO, UPCOMING, GAME, SKELETON }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<FollowingOverviewSection>() {
            override fun areItemsTheSame(oldItem: FollowingOverviewSection, newItem: FollowingOverviewSection): Boolean =
                oldItem.key == newItem.key

            override fun areContentsTheSame(oldItem: FollowingOverviewSection, newItem: FollowingOverviewSection): Boolean =
                followingOverviewSectionContentsSame(oldItem, newItem)
        }
    }
}

internal fun followingOverviewSectionsSame(
    old: List<FollowingOverviewSection>,
    new: List<FollowingOverviewSection>,
): Boolean {
    return old.size == new.size && old.indices.all { index ->
        old[index].key == new[index].key &&
            followingOverviewSectionContentsSame(old[index], new[index])
    }
}

internal fun followingOverviewSectionContentsSame(
    old: FollowingOverviewSection,
    new: FollowingOverviewSection,
): Boolean {
    if (old.titleRes != new.titleRes ||
        old.emptyRes != new.emptyRes ||
        old.title != new.title ||
        old.isLoading != new.isLoading ||
        old.hasResolved != new.hasResolved ||
        old.loadingType != new.loadingType ||
        old.showSeeAll != new.showSeeAll ||
        old.isFeatured != new.isFeatured
    ) return false

    return streamListsSame(old.streams, new.streams) &&
        gameListsSame(old.games, new.games) &&
        old.videos == new.videos &&
        old.scheduledStreams == new.scheduledStreams
}

private fun streamListsSame(old: List<Stream>, new: List<Stream>): Boolean {
    if (old === new) return true
    return old.size == new.size && old.indices.all { index ->
        val oldStream = old[index]
        val newStream = new[index]
        oldStream.streamIdentity() == newStream.streamIdentity() &&
            streamContentsSame(oldStream, newStream)
    }
}

private fun gameListsSame(old: List<Game>, new: List<Game>): Boolean {
    if (old === new) return true
    return old.size == new.size && old.indices.all { index ->
        val oldGame = old[index]
        val newGame = new[index]
        oldGame.id == newGame.id &&
            oldGame.slug == newGame.slug &&
            oldGame.name == newGame.name &&
            oldGame.boxArtURL == newGame.boxArtURL &&
            oldGame.viewerCount == newGame.viewerCount &&
            oldGame.broadcasterCount == newGame.broadcasterCount &&
            oldGame.followerCount == newGame.followerCount &&
            oldGame.vodPosition == newGame.vodPosition &&
            oldGame.vodDuration == newGame.vodDuration &&
            oldGame.accountFollow == newGame.accountFollow &&
            oldGame.localFollow == newGame.localFollow &&
            tagListsSame(oldGame.tags, newGame.tags)
    }
}

private fun tagListsSame(
    old: List<com.github.andreyasadchy.xtra.model.ui.Tag>?,
    new: List<com.github.andreyasadchy.xtra.model.ui.Tag>?,
): Boolean {
    if (old === new) return true
    val oldTags = old.orEmpty()
    val newTags = new.orEmpty()
    return oldTags.size == newTags.size && oldTags.indices.all { index ->
        oldTags[index].id == newTags[index].id && oldTags[index].name == newTags[index].name
    }
}
