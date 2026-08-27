package com.github.andreyasadchy.xtra.ui.following.overview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ItemGameShelfBinding
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.ui.common.FeedImageRequestBag
import com.github.andreyasadchy.xtra.ui.common.FeedImageRequestOwner
import com.github.andreyasadchy.xtra.ui.common.FeedUiPreferencesStore
import com.github.andreyasadchy.xtra.ui.common.restoreDecodedMemoryImage
import com.github.andreyasadchy.xtra.ui.common.StreamThumbnailIdleScheduler
import com.github.andreyasadchy.xtra.util.TwitchApiHelper

class GameShelfAdapter(
    private val onGameClick: (Game) -> Unit,
) : ListAdapter<Game, GameShelfAdapter.ViewHolder>(DIFF_CALLBACK) {

    private val imageLoadScheduler = StreamThumbnailIdleScheduler()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGameShelfBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        (parent as? RecyclerView)?.let { ShelfCardSizing.apply(binding.root, it) }
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        (holder.itemView.parent as? RecyclerView)?.let { ShelfCardSizing.apply(holder.itemView, it) }
        holder.beginImageBind(getItem(position))
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        imageLoadScheduler.clear(holder)
        holder.cancelImageWork()
        super.onViewRecycled(holder)
    }

    private val layoutChangeListener = View.OnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
        if (right - left != oldRight - oldLeft) {
            val shelf = view as RecyclerView
            shelf.post { applyCardSizing(shelf) }
        }
    }

    private fun applyCardSizing(shelf: RecyclerView) {
        repeat(shelf.childCount) { index -> ShelfCardSizing.apply(shelf.getChildAt(index), shelf) }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        imageLoadScheduler.attachTo(recyclerView)
        recyclerView.addOnLayoutChangeListener(layoutChangeListener)
        recyclerView.post { applyCardSizing(recyclerView) }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        imageLoadScheduler.detach()
        recyclerView.removeOnLayoutChangeListener(layoutChangeListener)
        super.onDetachedFromRecyclerView(recyclerView)
    }

    inner class ViewHolder(
        private val binding: ItemGameShelfBinding,
    ) : RecyclerView.ViewHolder(binding.root), FeedImageRequestOwner {

        private val imageRequests = FeedImageRequestBag()
        private var boundGameId: String? = null
        private var boundGame: Game? = null

        init {
            binding.root.setOnClickListener { boundGame?.let(onGameClick) }
        }

        fun beginImageBind(game: Game) {
            imageLoadScheduler.clear(this)
            imageRequests.cancel()
            boundGameId = game.id
        }

        override fun cancelImageRequests() {
            imageRequests.cancel()
        }

        override fun pauseImageRequests() {
            imageRequests.cancel(preserveRegistrations = true)
        }

        fun cancelImageWork() {
            cancelImageRequests()
            boundGameId = null
            boundGame = null
        }

        fun bind(game: Game) {
            val context = binding.root.context
            boundGame = game
            binding.gameName.text = game.name.orEmpty()
            binding.gameName.visibility = if (game.name.isNullOrBlank()) View.GONE else View.VISIBLE
            val uiPreferences = FeedUiPreferencesStore.current(context)
            val viewerCount = game.viewerCount
            binding.viewers.text = viewerCount?.let {
                context.resources.getQuantityString(
                    R.plurals.viewers,
                    it,
                    TwitchApiHelper.formatCount(
                        it,
                        uiPreferences.truncateViewCount,
                    ),
                )
            }.orEmpty()
            binding.viewers.visibility = if (binding.viewers.text.isNullOrBlank()) View.GONE else View.VISIBLE
            val imageUrl = game.boxArt
            binding.gameImage.contentDescription = game.name
            if (imageUrl.isNullOrBlank()) {
                binding.gameImage.visibility = View.INVISIBLE
                binding.gameImage.setImageDrawable(null)
                binding.gameImage.tag = null
            } else {
                binding.gameImage.visibility = View.VISIBLE
                val imageKey = "xtra:game-boxart:${game.id}|$imageUrl"
                if (binding.gameImage.tag != imageKey) {
                    binding.gameImage.setImageDrawable(null)
                    binding.gameImage.tag = imageKey
                }
                restoreDecodedMemoryImage(imageKey, binding.gameImage)
                imageLoadScheduler.runOrDefer(this@ViewHolder, binding.gameImage) {
                    if (!binding.root.isAttachedToWindow || boundGameId != game.id) return@runOrDefer
                    imageRequests.replace(
                        binding.gameImage,
                        context.imageLoader.enqueue(
                            ImageRequest.Builder(context)
                                .data(imageUrl)
                                .memoryCacheKey(imageKey)
                                .crossfade(false)
                                .target(binding.gameImage)
                                .build(),
                        ),
                    )
                }
            }
        }
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Game>() {
            override fun areItemsTheSame(oldItem: Game, newItem: Game): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Game, newItem: Game): Boolean =
                oldItem.name == newItem.name && oldItem.boxArtURL == newItem.boxArtURL && oldItem.viewerCount == newItem.viewerCount
        }
    }
}
