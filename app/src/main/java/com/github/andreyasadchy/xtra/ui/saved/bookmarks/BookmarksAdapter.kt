package com.github.andreyasadchy.xtra.ui.saved.bookmarks

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
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
import com.github.andreyasadchy.xtra.databinding.FragmentVideosListItemBinding
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.ui.Bookmark
import com.github.andreyasadchy.xtra.model.ui.BookmarkIgnoredUser
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.common.FeedImageRequestBag
import com.github.andreyasadchy.xtra.ui.common.FeedImageRequestOwner
import com.github.andreyasadchy.xtra.ui.common.FeedUiPreferencesStore
import com.github.andreyasadchy.xtra.ui.common.StreamThumbnailIdleScheduler
import com.github.andreyasadchy.xtra.ui.common.restoreDecodedMemoryImage
import com.github.andreyasadchy.xtra.ui.common.thumbnailState
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.TwitchApiHelper.getDurationFromSeconds
import com.github.andreyasadchy.xtra.util.prefs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class BookmarksAdapter(
    private val fragment: Fragment,
    private val refreshVideo: (String?) -> Unit,
    private val showDownloadDialog: (Video) -> Unit,
    private val vodIgnoreUser: (String) -> Unit,
    private val deleteVideo: (Bookmark) -> Unit,
) : ListAdapter<Bookmark, BookmarksAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<Bookmark>() {
        override fun areItemsTheSame(oldItem: Bookmark, newItem: Bookmark): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Bookmark, newItem: Bookmark): Boolean =
                    oldItem.title == newItem.title &&
                    oldItem.duration == newItem.duration
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
        return PagingViewHolder(binding, fragment)
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) {
        holder.beginImageBind(getItem(position))
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(
        holder: PagingViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.isNotEmpty() && payloads.all { it === BOOKMARK_POSITION_CHANGED_PAYLOAD }) {
            holder.bindPosition(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: PagingViewHolder) {
        imageLoadScheduler.clear(holder)
        holder.cancelImageWork()
        super.onViewRecycled(holder)
    }

    private var positions: Map<Long, Long> = emptyMap()

    fun setVideoPositions(positions: List<VideoPosition>) {
        val oldPositions = this.positions
        val newPositions = positions.associate { it.id to it.position }
        this.positions = newPositions
        for (index in 0 until itemCount) {
            val id = getItem(index)?.videoId?.toLongOrNull() ?: continue
            if (oldPositions[id] != newPositions[id]) {
                notifyItemChanged(index, BOOKMARK_POSITION_CHANGED_PAYLOAD)
            }
        }
    }

    private var ignored: List<BookmarkIgnoredUser>? = null

    fun setIgnoredUsers(list: List<BookmarkIgnoredUser>) {
        val oldIds = ignored.orEmpty().mapTo(HashSet()) { it.userId }
        val newIds = list.mapTo(HashSet()) { it.userId }
        this.ignored = list
        if (oldIds != newIds) {
            val changedIds = (oldIds - newIds) + (newIds - oldIds)
            for (index in 0 until itemCount) {
                if (getItem(index)?.userId in changedIds) {
                    notifyItemChanged(index)
                }
            }
        }
    }

    inner class PagingViewHolder(
        private val binding: FragmentVideosListItemBinding,
        private val fragment: Fragment,
    ) : RecyclerView.ViewHolder(binding.root), FeedImageRequestOwner {
        private val imageRequests = FeedImageRequestBag()
        private var boundBookmarkId: Int? = null

        fun beginImageBind(item: Bookmark?) {
            imageLoadScheduler.clear(this)
            imageRequests.cancel()
            boundBookmarkId = item?.id
        }

        override fun cancelImageRequests() {
            imageRequests.cancel()
        }

        override fun pauseImageRequests() {
            imageRequests.cancel(preserveRegistrations = true)
        }

        fun cancelImageWork() {
            cancelImageRequests()
            boundBookmarkId = null
        }

        fun bind(item: Bookmark?) {
            with(binding) {
                if (item != null) {
                    val context = fragment.requireContext()
                    val uiPreferences = FeedUiPreferencesStore.current(context)
                    val channelListener: (View) -> Unit = {
                        fragment.findNavController().navigate(
                            ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                                channelId = item.userId,
                                channelLogin = item.userLogin,
                                channelName = item.userName,
                                channelImage = item.userLogo,
                                updateLocal = true
                            )
                        )
                    }
                    val gameListener: (View) -> Unit = {
                        fragment.findNavController().navigate(GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                            gameId = item.gameId,
                            gameSlug = item.gameSlug,
                            gameName = item.gameName
                        ))
                    }
                    val durationSeconds = item.duration?.let { duration -> duration.toIntOrNull() ?: TwitchApiHelper.getDuration(duration) }
                    val position = item.videoId?.toLongOrNull()?.let { id -> positions[id] }
                    val startFromBeginning = position != null && durationSeconds != null && durationSeconds > 0 && position >= (durationSeconds * 1000)
                    val ignore = ignored?.find { it.userId == item.userId } != null
                    root.setOnClickListener {
                        (fragment.activity as MainActivity).startVideo(
                            Video(
                                id = item.videoId,
                                channelId = item.userId,
                                channelLogin = item.userLogin,
                                channelName = item.userName,
                                channelImageURL = item.userLogo,
                                gameId = item.gameId,
                                gameSlug = item.gameSlug,
                                gameName = item.gameName,
                                title = item.title,
                                thumbnailURL = item.thumbnail,
                                createdAt = item.createdAt,
                                durationSeconds = durationSeconds,
                                type = item.type,
                                animatedPreviewURL = item.animatedPreviewURL,
                            ),
                            if (startFromBeginning) {
                                0
                            } else {
                                position
                            },
                            startFromBeginning
                        )
                    }
                    root.setOnLongClickListener {
                        deleteVideo(item)
                        true
                    }
                    val thumbnailKey = "xtra:bookmark-thumbnail:${item.id}|${item.thumbnail}"
                    if (thumbnail.tag != thumbnailKey) {
                        thumbnail.setImageDrawable(null)
                        thumbnail.tag = thumbnailKey
                    }
                    restoreDecodedMemoryImage(thumbnailKey, thumbnail)
                    imageLoadScheduler.runOrDefer(this@PagingViewHolder, thumbnail) {
                        if (!root.isAttachedToWindow || boundBookmarkId != item.id) return@runOrDefer
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
                                }.build()
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
                    if (item.type?.lowercase() == "archive" && item.createdAt != null && context.prefs().getBoolean(C.UI_BOOKMARK_TIME_LEFT, true) && !ignore) {
                        val text = Instant.parseOrNull(item.createdAt)?.takeIf { time -> time.toEpochMilliseconds() > 0 }?.let { createdAt ->
                            val userType = item.userType ?: item.userBroadcasterType
                            val days = if (userType.isNullOrBlank()) {
                                7
                            } else {
                                when (userType.lowercase()) {
                                    "affiliate" -> 14
                                    else -> 60 // Partners, Prime, Turbo
                                }
                            }
                            val timeLeft = (createdAt + days.days) - Clock.System.now()
                            if (timeLeft.isPositive()) {
                                getDurationFromSeconds(context, timeLeft.inWholeSeconds.toString())
                            } else null
                        }
                        if (text != null) {
                            views.visibility = View.VISIBLE
                            views.text = context.getString(R.string.vod_time_left, text)
                        } else {
                            views.visibility = View.GONE
                        }
                    } else {
                        views.visibility = View.GONE
                    }
                    if (durationSeconds != null) {
                        duration.visibility = View.VISIBLE
                        duration.text = DateUtils.formatElapsedTime(durationSeconds.toLong())
                    } else {
                        duration.visibility = View.GONE
                    }
                    if (item.type != null) {
                        val text = TwitchApiHelper.getType(context, item.type)
                        if (text != null) {
                            type.visibility = View.VISIBLE
                            type.text = text
                        } else {
                            type.visibility = View.GONE
                        }
                    } else {
                        type.visibility = View.GONE
                    }
                    if (item.userLogo != null) {
                        userImage.visibility = View.VISIBLE
                        userImage.contentDescription = item.userName?.let {
                            context.getString(R.string.player_open_channel, it)
                        }
                        val profileKey = "xtra:bookmark-avatar:${item.id}|${item.userLogo}|round=${uiPreferences.roundUserImage}"
                        if (userImage.tag != profileKey) {
                            userImage.setImageDrawable(null)
                            userImage.tag = profileKey
                        }
                        restoreDecodedMemoryImage(profileKey, userImage)
                        imageLoadScheduler.runOrDefer(this@PagingViewHolder, userImage) {
                            if (!root.isAttachedToWindow || boundBookmarkId != item.id) return@runOrDefer
                            imageRequests.replace(
                                userImage,
                                context.imageLoader.enqueue(
                                    ImageRequest.Builder(context).apply {
                                        data(item.userLogo)
                                        memoryCacheKey(profileKey)
                                        diskCachePolicy(CachePolicy.ENABLED)
                                        if (uiPreferences.roundUserImage) {
                                            transformations(CircleCropTransformation())
                                        }
                                        crossfade(false)
                                        target(userImage)
                                    }.build()
                                ),
                            )
                        }
                        userImage.setOnClickListener(channelListener)
                    } else {
                        userImage.visibility = View.GONE
                        userImage.contentDescription = null
                    }
                    if (item.userName != null) {
                        username.visibility = View.VISIBLE
                        username.text = if (item.userLogin != null && !item.userLogin.equals(item.userName, true)) {
                            when (context.prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                                "0" -> "${item.userName}(${item.userLogin})"
                                "1" -> item.userName
                                else -> item.userLogin
                            }
                        } else {
                            item.userName
                        }
                        username.setOnClickListener(channelListener)
                    } else {
                        username.visibility = View.GONE
                    }
                    bindPosition(item)
                    if (!item.title.isNullOrBlank()) {
                        title.visibility = View.VISIBLE
                        title.text = item.title.trim()
                    } else {
                        title.visibility = View.GONE
                    }
                    if (item.gameName != null) {
                        gameName.visibility = View.VISIBLE
                        gameName.text = item.gameName
                        gameName.setOnClickListener(gameListener)
                    } else {
                        gameName.visibility = View.GONE
                    }
                    options.setOnClickListener { it ->
                        PopupMenu(context, it).apply {
                            inflate(R.menu.bookmark_item)
                            if (!item.videoId.isNullOrBlank()) {
                                menu.findItem(R.id.refresh).isVisible = true
                                menu.findItem(R.id.download).isVisible = true
                            }
                            if (item.type?.lowercase() == "archive" && item.userId != null && context.prefs().getBoolean(C.UI_BOOKMARK_TIME_LEFT, true)) {
                                menu.findItem(R.id.vodIgnore).isVisible = true
                                if (ignore) {
                                    menu.findItem(R.id.vodIgnore).title = context.getString(R.string.vod_remove_ignore)
                                } else {
                                    menu.findItem(R.id.vodIgnore).title = context.getString(R.string.vod_ignore_user)
                                }
                            }
                            setOnMenuItemClickListener {
                                when (it.itemId) {
                                    R.id.delete -> deleteVideo(item)
                                    R.id.download -> showDownloadDialog(
                                        Video(
                                            id = item.videoId,
                                            channelId = item.userId,
                                            channelLogin = item.userLogin,
                                            channelName = item.userName,
                                            channelImageURL = item.userLogo,
                                            gameId = item.gameId,
                                            gameName = item.gameName,
                                            title = item.title,
                                            thumbnailURL = item.thumbnail,
                                            createdAt = item.createdAt,
                                            durationSeconds = durationSeconds,
                                            type = item.type,
                                            animatedPreviewURL = item.animatedPreviewURL,
                                        )
                                    )
                                    R.id.vodIgnore -> item.userId?.let { id -> vodIgnoreUser(id) }
                                    R.id.refresh -> refreshVideo(item.videoId)
                                    else -> menu.close()
                                }
                                true
                            }
                            show()
                        }
                    }
                }
            }
        }

        fun bindPosition(item: Bookmark?) {
            val durationSeconds = item?.duration?.let { duration ->
                duration.toIntOrNull() ?: TwitchApiHelper.getDuration(duration)
            }
            val position = item?.videoId?.toLongOrNull()?.let { id -> positions[id] }
            if (position != null && durationSeconds != null && durationSeconds > 0) {
                binding.progressBar.progress = (position / (durationSeconds * 10)).toInt()
                binding.progressBar.visibility = View.VISIBLE
            } else {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private companion object {
        val BOOKMARK_POSITION_CHANGED_PAYLOAD = Any()
    }
}
