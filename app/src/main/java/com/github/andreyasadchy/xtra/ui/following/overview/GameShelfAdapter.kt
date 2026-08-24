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
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs

class GameShelfAdapter(
    private val onGameClick: (Game) -> Unit,
) : ListAdapter<Game, GameShelfAdapter.ViewHolder>(DIFF_CALLBACK) {

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
        holder.bind(getItem(position))
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
        recyclerView.addOnLayoutChangeListener(layoutChangeListener)
        recyclerView.post { applyCardSizing(recyclerView) }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeOnLayoutChangeListener(layoutChangeListener)
        super.onDetachedFromRecyclerView(recyclerView)
    }

    inner class ViewHolder(
        private val binding: ItemGameShelfBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(game: Game) {
            val context = binding.root.context
            binding.root.setOnClickListener { onGameClick(game) }
            binding.gameName.text = game.name.orEmpty()
            binding.gameName.visibility = if (game.name.isNullOrBlank()) View.GONE else View.VISIBLE
            val viewerCount = game.viewerCount
            binding.viewers.text = viewerCount?.let {
                context.resources.getQuantityString(
                    R.plurals.viewers,
                    it,
                    TwitchApiHelper.formatCount(
                        it,
                        context.prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true),
                    ),
                )
            }.orEmpty()
            binding.viewers.visibility = if (binding.viewers.text.isNullOrBlank()) View.GONE else View.VISIBLE
            val imageUrl = game.boxArt
            binding.gameImage.contentDescription = game.name
            if (imageUrl.isNullOrBlank()) {
                binding.gameImage.visibility = View.INVISIBLE
                binding.gameImage.setImageDrawable(null)
            } else {
                binding.gameImage.visibility = View.VISIBLE
                context.imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(imageUrl)
                        .crossfade(true)
                        .target(binding.gameImage)
                        .build(),
                )
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
