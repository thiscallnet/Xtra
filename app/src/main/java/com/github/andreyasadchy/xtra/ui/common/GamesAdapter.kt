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
import com.github.andreyasadchy.xtra.ui.tv.TvFocusHelper
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
    private val presentationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var presentationPrewarmJob: Job? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        imageLoadScheduler.attachTo(recyclerView)
        presentationPrewarmJob?.cancel()
        presentationPrewarmJob = presentationScope.launch {
            onPagesUpdatedFlow.collectLatest {
                val context = fragment.context ?: return@collectLatest
                GameCardPresentationCache.prewarm(
                    context = context,
                    games = snapshot().items.filterNotNull(),
                    preferences = FeedUiPreferencesStore.current(context),
                )
            }
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        presentationPrewarmJob?.cancel()
        presentationPrewarmJob = null
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
            TvFocusHelper.install(binding.root)
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
                    val presentation = GameCardPresentationCache.get(item, uiPreferences)
                    if (presentation == null) {
                        GameCardPresentationCache.request(context, item, uiPreferences) {
                            if (boundGame === item && binding.root.isAttachedToWindow) applyPresentation(it)
                        }
                    }
                    val boxArt = presentation?.boxArt ?: item.boxArt
                    if (boxArt != null) {
                        gameImage.visibility = View.VISIBLE
                        val gameId = item.id
                        val imageKey = "xtra:game-boxart:$gameId|$boxArt"
                        if (gameImage.tag != imageKey) {
                            gameImage.setImageDrawable(null)
                            gameImage.tag = imageKey
                        }
                        val restored = restoreDecodedMemoryImage(imageKey, gameImage)
                        if (!restored) imageLoadScheduler.runOrDefer(this@PagingViewHolder, binding.gameImage) {
                            if (!binding.root.isAttachedToWindow || boundGameId != gameId) return@runOrDefer
                            imageRequests.replace(
                                binding.gameImage,
                                context.imageLoader.enqueue(
                                    ImageRequest.Builder(context).apply {
                                        data(boxArt)
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
                    if (presentation?.name != null || item.name != null) {
                        gameName.visibility = View.VISIBLE
                        gameName.text = presentation?.name ?: item.name
                    } else {
                        gameName.visibility = View.GONE
                    }
                    if (presentation?.viewerLabel != null || item.viewerCount != null) {
                        viewers.visibility = View.VISIBLE
                        viewers.text = presentation?.viewerLabel ?: item.viewerCount?.toString()
                    } else {
                        viewers.visibility = View.GONE
                    }
                    if (presentation?.broadcasterLabel != null || (item.broadcasterCount != null && uiPreferences.showBroadcastersCount)) {
                        broadcastersCount.visibility = View.VISIBLE
                        broadcastersCount.text = presentation?.broadcasterLabel ?: item.broadcasterCount?.toString()
                    } else {
                        broadcastersCount.visibility = View.GONE
                    }
                    val tags = presentation?.tags ?: if (uiPreferences.showTags) item.tags.orEmpty() else emptyList()
                    if (tags.isNotEmpty()) {
                        bindGameTags(tagViews, tags, selectTag)
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

        private fun applyPresentation(presentation: GameCardPresentation) {
            if (boundGame == null) return
            with(binding) {
                if (presentation.name != null) {
                    gameName.visibility = View.VISIBLE
                    gameName.text = presentation.name
                } else {
                    gameName.visibility = View.GONE
                }
                if (presentation.viewerLabel != null) {
                    viewers.visibility = View.VISIBLE
                    viewers.text = presentation.viewerLabel
                } else {
                    viewers.visibility = View.GONE
                }
                if (presentation.broadcasterLabel != null) {
                    broadcastersCount.visibility = View.VISIBLE
                    broadcastersCount.text = presentation.broadcasterLabel
                } else {
                    broadcastersCount.visibility = View.GONE
                }
                if (presentation.tags.isNotEmpty()) {
                    bindGameTags(tagViews, presentation.tags, selectTag)
                } else {
                    clearGameTags(tagViews)
                }
            }
        }
    }
}
