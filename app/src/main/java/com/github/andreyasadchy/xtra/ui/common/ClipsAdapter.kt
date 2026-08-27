package com.github.andreyasadchy.xtra.ui.common

import android.content.Intent
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentVideosListItemBinding
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlin.time.Instant

class ClipsAdapter(
    private val fragment: Fragment,
    private val showDownloadDialog: (Clip) -> Unit,
    private val showGame: Boolean = true,
    private val showChannel: Boolean = true,
) : PagingDataAdapter<Clip, ClipsAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<Clip>() {
        override fun areItemsTheSame(oldItem: Clip, newItem: Clip): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Clip, newItem: Clip): Boolean =
            oldItem.viewCount == newItem.viewCount &&
                    oldItem.title == newItem.title
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
        val binding = FragmentVideosListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PagingViewHolder(binding, fragment, showGame, showChannel)
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

    inner class PagingViewHolder(
        private val binding: FragmentVideosListItemBinding,
        private val fragment: Fragment,
        private val showGame: Boolean,
        private val showChannel: Boolean,
    ) : RecyclerView.ViewHolder(binding.root), FeedImageRequestOwner {
        private val imageRequests = FeedImageRequestBag()
        private var boundClipId: String? = null
        private var boundClip: Clip? = null

        init {
            binding.root.setOnClickListener {
                boundClip?.let { (fragment.activity as? MainActivity)?.startClip(it) }
            }
            binding.root.setOnLongClickListener {
                boundClip?.let {
                    showDownloadDialog(it)
                    true
                } ?: false
            }
            binding.userImage.setOnClickListener { boundClip?.let(::openChannel) }
            binding.username.setOnClickListener { boundClip?.let(::openChannel) }
            binding.gameName.setOnClickListener { boundClip?.let(::openGame) }
            binding.options.setOnClickListener { showOptions(it) }
        }

        fun beginImageBind(item: Clip?) {
            imageLoadScheduler.clear(this)
            imageRequests.cancel()
            boundClipId = item?.id
        }

        override fun cancelImageRequests() {
            imageRequests.cancel()
        }

        override fun pauseImageRequests() {
            imageRequests.cancel(preserveRegistrations = true)
        }

        fun cancelImageWork() {
            cancelImageRequests()
            boundClipId = null
            boundClip = null
        }

        fun bind(item: Clip?) {
            boundClip = item
            with(binding) {
                if (item != null) {
                    val context = fragment.requireContext()
                    val uiPreferences = FeedUiPreferencesStore.current(context)
                    val clipId = item.id
                    val thumbnailKey = "xtra:clip-thumbnail:$clipId|${item.thumbnail}"
                    if (thumbnail.tag != thumbnailKey) {
                        thumbnail.setImageDrawable(null)
                        thumbnail.tag = thumbnailKey
                    }
                    restoreDecodedMemoryImage(thumbnailKey, thumbnail)
                    imageLoadScheduler.runOrDefer(this@PagingViewHolder, thumbnail) {
                        if (!root.isAttachedToWindow || boundClipId != clipId) return@runOrDefer
                        imageRequests.replace(
                            thumbnail,
                            context.imageLoader.enqueue(
                                ImageRequest.Builder(context).apply {
                                    data(item.thumbnail)
                                    memoryCacheKey(thumbnailKey)
                                    diskCachePolicy(CachePolicy.ENABLED)
                                    crossfade(false)
                                    target(thumbnail)
                                    thumbnailState()
                                }.build(),
                            ),
                        )
                    }
                    if (item.createdAt != null) {
                        val text = Instant.parseOrNull(item.createdAt)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 }?.let {
                            TwitchApiHelper.formatDate(context, it)
                        }
                        if (text != null) {
                            date.visibility = View.VISIBLE
                            date.text = text
                        } else {
                            date.visibility = View.GONE
                        }
                    } else {
                        date.visibility = View.GONE
                    }
                    if (item.viewCount != null) {
                        views.visibility = View.VISIBLE
                        val count = item.viewCount
                        views.text = context.resources.getQuantityString(
                            R.plurals.views,
                            count,
                            TwitchApiHelper.formatCount(count, uiPreferences.truncateViewCount)
                        )
                    } else {
                        views.visibility = View.GONE
                    }
                    if (item.durationSeconds != null) {
                        duration.visibility = View.VISIBLE
                        duration.text = DateUtils.formatElapsedTime(item.durationSeconds.toLong())
                    } else {
                        duration.visibility = View.GONE
                    }
                    if (showChannel) {
                        if (item.channelImage != null) {
                            userImage.visibility = View.VISIBLE
                            userImage.contentDescription = item.channelName?.let {
                                context.getString(R.string.player_open_channel, it)
                            }
                            val profileKey = "xtra:clip-avatar:$clipId|${item.channelImage}|round=${uiPreferences.roundUserImage}"
                            if (userImage.tag != profileKey) {
                                userImage.setImageDrawable(null)
                                userImage.tag = profileKey
                            }
                            restoreDecodedMemoryImage(profileKey, userImage)
                            imageLoadScheduler.runOrDefer(this@PagingViewHolder, userImage) {
                                if (!root.isAttachedToWindow || boundClipId != clipId) return@runOrDefer
                                imageRequests.replace(
                                    userImage,
                                    context.imageLoader.enqueue(
                                        ImageRequest.Builder(context).apply {
                                            data(item.channelImage)
                                            memoryCacheKey(profileKey)
                                            if (uiPreferences.roundUserImage) {
                                                transformations(CircleCropTransformation())
                                            }
                                            crossfade(false)
                                            target(userImage)
                                        }.build(),
                                    ),
                                )
                            }
                    } else {
                        userImage.visibility = View.GONE
                        userImage.contentDescription = null
                    }
                        if (item.channelName != null) {
                            username.visibility = View.VISIBLE
                            username.text = if (item.channelLogin != null && !item.channelLogin.equals(item.channelName, true)) {
                                when (uiPreferences.nameDisplay) {
                                    "0" -> "${item.channelName}(${item.channelLogin})"
                                    "1" -> item.channelName
                                    else -> item.channelLogin
                                }
                            } else {
                                item.channelName
                            }
                        } else {
                            username.visibility = View.GONE
                        }
                    } else {
                        userImage.visibility = View.GONE
                        username.visibility = View.GONE
                    }
                    if (!item.title.isNullOrBlank()) {
                        title.visibility = View.VISIBLE
                        title.text = item.title.trim()
                    } else {
                        title.visibility = View.GONE
                    }
                    if (showGame && item.gameName != null) {
                        gameName.visibility = View.VISIBLE
                        gameName.text = item.gameName
                    } else {
                        gameName.visibility = View.GONE
                    }
                }
            }
        }

        private fun openChannel(clip: Clip) {
            if (!showChannel) return
            fragment.findNavController().navigate(
                ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                    channelId = clip.channelId,
                    channelLogin = clip.channelLogin,
                    channelName = clip.channelName,
                    channelImage = clip.channelImage,
                ),
            )
        }

        private fun openGame(clip: Clip) {
            if (!showGame || clip.gameName.isNullOrBlank()) return
            fragment.findNavController().navigate(
                GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                    gameId = clip.gameId,
                    gameSlug = clip.gameSlug,
                    gameName = clip.gameName,
                ),
            )
        }

        private fun showOptions(anchor: View) {
            val clip = boundClip ?: return
            val context = fragment.requireContext()
            PopupMenu(context, anchor).apply {
                inflate(R.menu.media_item)
                setOnMenuItemClickListener {
                    when (it.itemId) {
                        R.id.download -> showDownloadDialog(clip)
                        R.id.share -> {
                            context.startActivity(Intent.createChooser(Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, "https://twitch.tv/${clip.channelLogin}/clip/${clip.id}")
                                clip.title?.let { title -> putExtra(Intent.EXTRA_TITLE, title) }
                                type = "text/plain"
                            }, null))
                        }
                        else -> menu.close()
                    }
                    true
                }
                show()
            }
        }
    }
}
