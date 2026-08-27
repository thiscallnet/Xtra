package com.github.andreyasadchy.xtra.ui.common

import android.content.Intent
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
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.FragmentVideosListItemBinding
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.ui.Bookmark
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class VideosAdapter(
    private val fragment: Fragment,
    private val showDownloadDialog: (Video) -> Unit,
    private val saveBookmark: (Video) -> Unit,
    private val showGame: Boolean = true,
    private val showChannel: Boolean = true,
) : PagingDataAdapter<Video, VideosAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<Video>() {
        override fun areItemsTheSame(oldItem: Video, newItem: Video): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Video, newItem: Video): Boolean =
            oldItem.viewCount == newItem.viewCount &&
                    oldItem.thumbnailURL == newItem.thumbnailURL &&
                    oldItem.title == newItem.title &&
                    oldItem.durationSeconds == newItem.durationSeconds
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
                VideoCardPresentationCache.prewarm(
                    context = context,
                    videos = snapshot().items.filterNotNull(),
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
        val binding = FragmentVideosListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PagingViewHolder(binding, fragment, showGame, showChannel)
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) {
        holder.beginImageBind(getItem(position))
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it === VIDEO_POSITION_CHANGED_PAYLOAD }) {
            holder.bindPosition(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: PagingViewHolder) {
        holder.detachPreview()
        imageLoadScheduler.clear(holder)
        holder.cancelImageWork()
        super.onViewRecycled(holder)
    }

    private var positions: Map<Long, Long> = emptyMap()

    fun setVideoPositions(positions: List<VideoPosition>) {
        val oldPositions = this.positions
        val newPositions = positions.associate { it.id to it.position }
        this.positions = newPositions
        if (itemCount == 0) return

        for (index in 0 until itemCount) {
            val id = getItem(index)?.id?.toLongOrNull() ?: continue
            if (oldPositions[id] != newPositions[id]) {
                notifyItemChanged(index, VIDEO_POSITION_CHANGED_PAYLOAD)
            }
        }
    }

    private var bookmarks: List<Bookmark>? = null

    fun setBookmarksList(list: List<Bookmark>) {
        this.bookmarks = list
    }

    inner class PagingViewHolder(
        private val binding: FragmentVideosListItemBinding,
        private val fragment: Fragment,
        private val showGame: Boolean,
        private val showChannel: Boolean,
    ) : RecyclerView.ViewHolder(binding.root), FeedImageRequestOwner {
        val previewSurface get() = binding.previewHost
        private var boundPreviewIdentity: String? = null
        private val imageRequests = FeedImageRequestBag()
        private var boundImageIdentity: String? = null
        private var boundVideo: Video? = null

        init {
            binding.root.setOnClickListener { boundVideo?.let(::openVideo) }
            binding.root.setOnLongClickListener {
                boundVideo?.let {
                    showDownloadDialog(it)
                    true
                } ?: false
            }
            binding.userImage.setOnClickListener { boundVideo?.let(::openChannel) }
            binding.username.setOnClickListener { boundVideo?.let(::openChannel) }
            binding.gameName.setOnClickListener { boundVideo?.let(::openGame) }
            binding.options.setOnClickListener { showOptions(it) }
        }

        fun beginImageBind(item: Video?) {
            imageLoadScheduler.clear(this)
            imageRequests.cancel()
            boundImageIdentity = item?.id?.trim()?.takeIf { it.isNotEmpty() }?.let { "vod:$it" }
        }

        override fun cancelImageRequests() {
            imageRequests.cancel()
        }

        override fun pauseImageRequests() {
            imageRequests.cancel(preserveRegistrations = true)
        }

        fun cancelImageWork() {
            cancelImageRequests()
            boundImageIdentity = null
            boundVideo = null
        }

        private fun bindThumbnail(item: Video, context: android.content.Context) {
            val identity = "vod:${item.id.orEmpty()}"
            val thumbnailUrl = item.thumbnail
            val thumbnailKey = "xtra:vod-thumbnail:$identity|$thumbnailUrl"
            if (binding.thumbnail.tag != thumbnailKey) {
                binding.thumbnail.setImageDrawable(null)
                binding.thumbnail.tag = thumbnailKey
            }
            restoreDecodedMemoryImage(thumbnailKey, binding.thumbnail)
            imageLoadScheduler.runOrDefer(this@PagingViewHolder, binding.thumbnail) {
                if (!binding.root.isAttachedToWindow || boundImageIdentity != identity) return@runOrDefer
                imageRequests.replace(
                    binding.thumbnail,
                    context.imageLoader.enqueue(
                        ImageRequest.Builder(context).apply {
                            data(thumbnailUrl)
                            memoryCacheKey(thumbnailKey)
                            diskCachePolicy(CachePolicy.ENABLED)
                            crossfade(false)
                            target(binding.thumbnail)
                            thumbnailState()
                        }.build(),
                    ),
                )
            }
        }

        private fun bindProfile(
            item: Video,
            context: android.content.Context,
            uiPreferences: FeedUiPreferences,
        ) {
            val identity = "vod:${item.id.orEmpty()}"
            val url = item.channelImage ?: return
            val profileKey = "xtra:vod-avatar:$identity|$url|round=${uiPreferences.roundUserImage}"
            if (binding.userImage.tag != profileKey) {
                binding.userImage.setImageDrawable(null)
                binding.userImage.tag = profileKey
            }
            restoreDecodedMemoryImage(profileKey, binding.userImage)
            imageLoadScheduler.runOrDefer(this@PagingViewHolder, binding.userImage) {
                if (!binding.root.isAttachedToWindow || boundImageIdentity != identity) return@runOrDefer
                imageRequests.replace(
                    binding.userImage,
                    context.imageLoader.enqueue(
                        ImageRequest.Builder(context).apply {
                            data(url)
                            memoryCacheKey(profileKey)
                            if (uiPreferences.roundUserImage) {
                                transformations(CircleCropTransformation())
                            }
                            crossfade(false)
                            target(binding.userImage)
                        }.build(),
                    ),
                )
            }
        }

        private val streamPreviewCoordinator
            get() = (binding.root.context.applicationContext as XtraApp).xtraModule.streamPreviewCoordinator

        fun bind(item: Video?) {
            boundVideo = item
            val nextPreviewIdentity = item?.id?.trim()?.takeIf { it.isNotEmpty() }?.let { "vod:$it" }
            with(binding) {
                if (boundPreviewIdentity != nextPreviewIdentity) {
                    streamPreviewCoordinator.detachSurface(previewHost)
                    boundPreviewIdentity = nextPreviewIdentity
                }
                if (item != null) {
                    val context = fragment.requireContext()
                    val uiPreferences = FeedUiPreferencesStore.current(context)
                    val presentation = VideoCardPresentationCache.get(item, uiPreferences)
                    if (presentation == null) {
                        VideoCardPresentationCache.request(context, item, uiPreferences) {
                            if (boundVideo === item && binding.root.isAttachedToWindow) applyPresentation(it)
                        }
                    }
                    val position = item.id?.toLongOrNull()?.let { id -> positions[id] }
                    bindThumbnail(item, context)
                    if (presentation?.date != null || item.createdAt != null) {
                        val text = presentation?.date
                        if (text != null) {
                            date.visibility = View.VISIBLE
                            date.text = text
                        } else {
                            date.visibility = View.GONE
                        }
                    } else {
                        date.visibility = View.GONE
                    }
                    if (presentation?.viewsLabel != null || item.viewCount != null) {
                        views.visibility = View.VISIBLE
                        views.text = presentation?.viewsLabel ?: item.viewCount?.toString()
                    } else {
                        views.visibility = View.GONE
                    }
                    if (presentation?.duration != null || item.durationSeconds != null) {
                        duration.visibility = View.VISIBLE
                        duration.text = presentation?.duration ?: item.durationSeconds?.toString()
                    } else {
                        duration.visibility = View.GONE
                    }
                    if (presentation?.type != null || item.type != null) {
                        val text = presentation?.type
                        if (text != null) {
                            type.visibility = View.VISIBLE
                            type.text = text
                        } else {
                            type.visibility = View.GONE
                        }
                    } else {
                        type.visibility = View.GONE
                    }
                    bindPosition(item, position)
                    if (showChannel) {
                        if (presentation?.channelImage != null || item.channelImage != null) {
                            userImage.visibility = View.VISIBLE
                            userImage.contentDescription = item.channelName?.let {
                                context.getString(R.string.player_open_channel, it)
                            }
                            bindProfile(item, context, uiPreferences)
                    } else {
                        userImage.visibility = View.GONE
                        userImage.contentDescription = null
                    }
                        if (presentation?.username != null || item.channelName != null) {
                            username.visibility = View.VISIBLE
                            username.text = presentation?.username ?: if (item.channelLogin != null && !item.channelLogin.equals(item.channelName, true)) {
                                when (uiPreferences.nameDisplay) {
                                    "0" -> "${item.channelName}(${item.channelLogin})"
                                    "1" -> item.channelName
                                    else -> item.channelLogin
                                }
                            } else item.channelName
                        } else {
                            username.visibility = View.GONE
                        }
                    } else {
                        userImage.visibility = View.GONE
                        username.visibility = View.GONE
                    }
                    if (presentation?.title != null || !item.title.isNullOrBlank()) {
                        title.visibility = View.VISIBLE
                        title.text = presentation?.title ?: item.title
                    } else {
                        title.visibility = View.GONE
                    }
                    if (showGame && (presentation?.gameName != null || item.gameName != null)) {
                        gameName.visibility = View.VISIBLE
                        gameName.text = presentation?.gameName ?: item.gameName
                    } else {
                        gameName.visibility = View.GONE
                    }
                }
            }
        }

        private fun applyPresentation(presentation: VideoCardPresentation) {
            if (boundVideo == null) return
            with(binding) {
                if (presentation.date != null) {
                    date.visibility = View.VISIBLE
                    date.text = presentation.date
                } else {
                    date.visibility = View.GONE
                }
                if (presentation.viewsLabel != null) {
                    views.visibility = View.VISIBLE
                    views.text = presentation.viewsLabel
                } else {
                    views.visibility = View.GONE
                }
                if (presentation.duration != null) {
                    duration.visibility = View.VISIBLE
                    duration.text = presentation.duration
                } else {
                    duration.visibility = View.GONE
                }
                if (presentation.type != null) {
                    type.visibility = View.VISIBLE
                    type.text = presentation.type
                } else {
                    type.visibility = View.GONE
                }
                if (showChannel) {
                    if (presentation.username != null) {
                        username.visibility = View.VISIBLE
                        username.text = presentation.username
                    } else {
                        username.visibility = View.GONE
                    }
                }
                if (presentation.title != null) {
                    title.visibility = View.VISIBLE
                    title.text = presentation.title
                } else {
                    title.visibility = View.GONE
                }
                if (showGame) {
                    if (presentation.gameName != null) {
                        gameName.visibility = View.VISIBLE
                        gameName.text = presentation.gameName
                    } else {
                        gameName.visibility = View.GONE
                    }
                }
            }
        }

        private fun openVideo(video: Video) {
            val position = video.id?.toLongOrNull()?.let { positions[it] }
            val startFromBeginning = position != null &&
                    video.durationSeconds != null &&
                    video.durationSeconds > 0 &&
                    position >= video.durationSeconds * 1000
            (fragment.activity as? MainActivity)?.startVideo(
                video,
                if (startFromBeginning) 0 else position,
                startFromBeginning,
            )
        }

        private fun openChannel(video: Video) {
            if (!showChannel) return
            fragment.findNavController().navigate(
                ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                    channelId = video.channelId,
                    channelLogin = video.channelLogin,
                    channelName = video.channelName,
                    channelImage = video.channelImage,
                ),
            )
        }

        private fun openGame(video: Video) {
            if (!showGame || video.gameName.isNullOrBlank()) return
            fragment.findNavController().navigate(
                GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                    gameId = video.gameId,
                    gameSlug = video.gameSlug,
                    gameName = video.gameName,
                ),
            )
        }

        private fun showOptions(anchor: View) {
            val video = boundVideo ?: return
            val context = fragment.requireContext()
            PopupMenu(context, anchor).apply {
                inflate(R.menu.media_item)
                if (!video.id.isNullOrBlank()) {
                    menu.findItem(R.id.bookmark).isVisible = true
                    menu.findItem(R.id.bookmark).title = if (bookmarks?.any { it.videoId == video.id } == true) {
                        context.getString(R.string.remove_bookmark)
                    } else {
                        context.getString(R.string.add_bookmark)
                    }
                }
                setOnMenuItemClickListener {
                    when (it.itemId) {
                        R.id.download -> showDownloadDialog(video)
                        R.id.bookmark -> saveBookmark(video)
                        R.id.share -> {
                            context.startActivity(Intent.createChooser(Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, "https://twitch.tv/videos/${video.id}")
                                video.title?.let { title -> putExtra(Intent.EXTRA_TITLE, title) }
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

        fun bindPosition(item: Video?, position: Long? = item?.id?.toLongOrNull()?.let { id -> positions[id] }) {
            with(binding.progressBar) {
                if (position != null && item?.durationSeconds != null && item.durationSeconds > 0L) {
                    progress = (position / (item.durationSeconds * 10)).toInt()
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }
        }

        fun detachPreview() {
            streamPreviewCoordinator.detachSurface(previewSurface)
            boundPreviewIdentity = null
        }
    }

    private companion object {
        val VIDEO_POSITION_CHANGED_PAYLOAD = Any()
    }
}
