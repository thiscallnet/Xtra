package com.github.andreyasadchy.xtra.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentGamesListItemBinding
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs

class GamesAdapter(
    private val fragment: Fragment,
    private val selectTag: (Tag) -> Unit,
) : PagingDataAdapter<Game, GamesAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<Game>() {
        override fun areItemsTheSame(oldItem: Game, newItem: Game): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Game, newItem: Game): Boolean =
            oldItem.viewerCount == newItem.viewerCount
    }) {

    private val imageLoadScheduler = StreamThumbnailIdleScheduler()

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        imageLoadScheduler.attachTo(recyclerView)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        imageLoadScheduler.detach()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagingViewHolder {
        val binding = FragmentGamesListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PagingViewHolder(binding, fragment, createGameTagViews(binding.tagsLayout))
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) {
        holder.beginImageBind(getItem(position))
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: PagingViewHolder) {
        imageLoadScheduler.clear(holder)
        holder.cancelImageWork()
        super.onViewRecycled(holder)
    }

    inner class PagingViewHolder internal constructor(
        private val binding: FragmentGamesListItemBinding,
        private val fragment: Fragment,
        private val tagViews: GameTagViews,
    ) : RecyclerView.ViewHolder(binding.root), FeedImageRequestOwner {
        private val imageRequests = FeedImageRequestBag()
        private var boundGameId: String? = null
        private var boundGame: Game? = null

        init {
            binding.root.setOnClickListener {
                boundGame?.let { game ->
                    fragment.findNavController().navigate(GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                        gameId = game.id,
                        gameSlug = game.slug,
                        gameName = game.name,
                        boxArt = game.boxArt,
                    ))
                }
            }
        }

        fun beginImageBind(item: Game?) {
            imageLoadScheduler.clear(this)
            imageRequests.cancel()
            boundGameId = item?.id
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

        fun bind(item: Game?) {
            boundGame = item
            with(binding) {
                if (item != null) {
                    val context = fragment.requireContext()
                    val uiPreferences = FeedUiPreferencesStore.current(context)
                    if (item.boxArt != null) {
                        gameImage.visibility = View.VISIBLE
                        val gameId = item.id
                        val imageKey = "xtra:game-boxart:$gameId|${item.boxArt}"
                        if (gameImage.tag != imageKey) {
                            gameImage.setImageDrawable(null)
                            gameImage.tag = imageKey
                        }
                        restoreDecodedMemoryImage(imageKey, gameImage)
                        imageLoadScheduler.runOrDefer(this@PagingViewHolder, binding.gameImage) {
                            if (!binding.root.isAttachedToWindow || boundGameId != gameId) return@runOrDefer
                            imageRequests.replace(
                                binding.gameImage,
                                context.imageLoader.enqueue(
                                    ImageRequest.Builder(context).apply {
                                        data(item.boxArt)
                                        memoryCacheKey(imageKey)
                                        crossfade(false)
                                        target(gameImage)
                                    }.build(),
                                ),
                            )
                        }
                    } else {
                        gameImage.visibility = View.GONE
                        gameImage.setImageDrawable(null)
                        gameImage.tag = null
                    }
                    if (item.name != null) {
                        gameName.visibility = View.VISIBLE
                        gameName.text = item.name
                    } else {
                        gameName.visibility = View.GONE
                    }
                    if (item.viewerCount != null) {
                        viewers.visibility = View.VISIBLE
                        val count = item.viewerCount ?: 0
                        viewers.text = context.resources.getQuantityString(
                            R.plurals.viewers,
                            count,
                            TwitchApiHelper.formatCount(count, uiPreferences.truncateViewCount)
                        )
                    } else {
                        viewers.visibility = View.GONE
                    }
                    if (item.broadcasterCount != null && uiPreferences.showBroadcastersCount) {
                        broadcastersCount.visibility = View.VISIBLE
                        val count = item.broadcasterCount ?: 0
                        broadcastersCount.text = context.resources.getQuantityString(
                            R.plurals.broadcasters,
                            count,
                            TwitchApiHelper.formatCount(count, uiPreferences.truncateViewCount)
                        )
                    } else {
                        broadcastersCount.visibility = View.GONE
                    }
                    if (!item.tags.isNullOrEmpty() && uiPreferences.showTags) {
                        bindGameTags(tagViews, item.tags.orEmpty(), selectTag)
                    } else {
                        clearGameTags(tagViews)
                    }
                } else {
                    boundGame = null
                    gameImage.setImageDrawable(null)
                    gameImage.visibility = View.GONE
                    gameName.text = null
                    gameName.visibility = View.GONE
                    viewers.text = null
                    viewers.visibility = View.GONE
                    broadcastersCount.text = null
                    broadcastersCount.visibility = View.GONE
                    clearGameTags(tagViews)
                }
            }
        }
    }
}
