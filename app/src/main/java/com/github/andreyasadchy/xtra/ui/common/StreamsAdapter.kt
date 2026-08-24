package com.github.andreyasadchy.xtra.ui.common

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentStreamsListItemBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.multiview.MultiviewFragment
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlin.time.Clock
import kotlin.time.Instant

class StreamsAdapter(
    private val fragment: Fragment,
    private val selectTag: (String) -> Unit,
    private val showGame: Boolean = true,
    private val onStreamClick: ((Stream) -> Unit)? = null,
) : PagingDataAdapter<Stream, StreamsAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<Stream>() {
        override fun areItemsTheSame(oldItem: Stream, newItem: Stream): Boolean =
            oldItem.streamIdentity() == newItem.streamIdentity()

        override fun areContentsTheSame(oldItem: Stream, newItem: Stream): Boolean =
            streamContentsSame(oldItem, newItem)
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagingViewHolder {
        val binding = FragmentStreamsListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PagingViewHolder(binding, fragment, showGame)
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: PagingViewHolder) {
        (fragment.requireContext().applicationContext as XtraApp).xtraModule.streamPreviewCoordinator
            .detachSurface(holder.previewSurface)
        holder.boundPreviewIdentity = null
        super.onViewRecycled(holder)
    }

    inner class PagingViewHolder(
        private val binding: FragmentStreamsListItemBinding,
        private val fragment: Fragment,
        private val showGame: Boolean,
    ) : RecyclerView.ViewHolder(binding.root) {
        val previewSurface get() = binding.previewHost
        var boundPreviewIdentity: String? = null

        fun bind(item: Stream?) {
            val nextPreviewIdentity = item?.streamIdentity()
            if (boundPreviewIdentity != nextPreviewIdentity) {
                (fragment.requireContext().applicationContext as XtraApp).xtraModule.streamPreviewCoordinator
                    .detachSurface(previewSurface)
                boundPreviewIdentity = nextPreviewIdentity
            }
            with(binding) {
                if (item != null) {
                    val context = fragment.requireContext()
                    val selectionMode = onStreamClick != null
                    val channelListener: (View) -> Unit = {
                        if (selectionMode) {
                            onStreamClick.invoke(item)
                        } else {
                            fragment.findNavController().navigate(
                                ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                                    channelId = item.channelId,
                                    channelLogin = item.channelLogin,
                                    channelName = item.channelName,
                                    channelImage = item.channelImage,
                                    streamId = item.id
                                )
                            )
                        }
                    }
                    root.setOnClickListener {
                        if (selectionMode) {
                            onStreamClick.invoke(item)
                        } else {
                            (fragment.activity as MainActivity).startStream(item)
                        }
                    }
                    multiview.visibility = if (selectionMode || item.channelLogin.isNullOrBlank()) View.GONE else View.VISIBLE
                    multiview.setOnClickListener {
                        (fragment.activity as? MainActivity)?.let { activity ->
                            if (activity.playerFragment != null) activity.closePlayer()
                        }
                        fragment.findNavController().navigate(R.id.multiviewFragment, MultiviewFragment.arguments(item))
                    }
                    if (item.channelImage != null) {
                        userImage.visibility = View.VISIBLE
                        userImage.contentDescription = item.channelName?.let {
                            context.getString(R.string.player_open_channel, it)
                        }
                        loadStreamProfileImage(context, userImage, item)
                        userImage.setOnClickListener(channelListener)
                    } else {
                        userImage.visibility = View.GONE
                        userImage.contentDescription = null
                        userImage.setImageDrawable(null)
                        userImage.tag = null
                    }
                    if (item.channelName != null) {
                        username.visibility = View.VISIBLE
                        username.text = if (item.channelLogin != null && !item.channelLogin.equals(item.channelName, true)) {
                            when (context.prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                                "0" -> "${item.channelName}(${item.channelLogin})"
                                "1" -> item.channelName
                                else -> item.channelLogin
                            }
                        } else {
                            item.channelName
                        }
                        username.setOnClickListener(channelListener)
                    } else {
                        username.visibility = View.GONE
                    }
                    val streamTitle = item.title
                    if (!streamTitle.isNullOrBlank()) {
                        title.visibility = View.VISIBLE
                        title.text = streamTitle.trim()
                    } else {
                        title.visibility = View.GONE
                    }
                    if (showGame && item.gameName != null) {
                        val gameListener: (View) -> Unit = {
                            if (selectionMode) {
                                onStreamClick.invoke(item)
                            } else {
                                fragment.findNavController().navigate(GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                    gameId = item.gameId,
                                    gameSlug = item.gameSlug,
                                    gameName = item.gameName
                                ))
                            }
                        }
                        gameName.visibility = View.VISIBLE
                        gameName.text = item.gameName
                        gameName.setOnClickListener(gameListener)
                    } else {
                        gameName.visibility = View.GONE
                    }
                    if (item.thumbnailURL != null) {
                        thumbnail.visibility = View.VISIBLE
                        liveBadge.visibility = View.VISIBLE
                        loadStreamThumbnail(context, thumbnail, item)
                    } else {
                        thumbnail.visibility = View.GONE
                        liveBadge.visibility = View.GONE
                        thumbnail.setImageDrawable(null)
                        thumbnail.tag = null
                    }
                    if (item.viewerCount != null) {
                        viewers.visibility = View.VISIBLE
                        val count = item.viewerCount ?: 0
                        viewers.text = context.resources.getQuantityString(
                            R.plurals.viewers,
                            count,
                            TwitchApiHelper.formatCount(count, context.prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true))
                        )
                    } else {
                        viewers.visibility = View.GONE
                    }
                    if (context.prefs().getBoolean(C.UI_UPTIME, true) && item.createdAt != null) {
                        val text = item.createdAt?.let {
                            Instant.parseOrNull(it)?.takeIf { time -> time.toEpochMilliseconds() > 0 }?.let { createdAt ->
                                val uptime = Clock.System.now() - createdAt
                                if (uptime.isPositive()) {
                                    DateUtils.formatElapsedTime(uptime.inWholeSeconds)
                                } else null
                            }
                        }
                        if (text != null) {
                            uptime.visibility = View.VISIBLE
                            uptime.text = text
                        } else {
                            uptime.visibility = View.GONE
                        }
                    } else {
                        uptime.visibility = View.GONE
                    }
                    if (!item.tags.isNullOrEmpty() && context.prefs().getBoolean(C.UI_TAGS, true)) {
                        bindStreamTags(context, tagsLayout, item.tags) { tag ->
                            if (selectionMode) {
                                onStreamClick.invoke(item)
                            } else {
                                selectTag(tag)
                            }
                        }
                    } else {
                        tagsLayout.visibility = View.GONE
                    }
                } else {
                    root.setOnClickListener(null)
                    multiview.setOnClickListener(null)
                    userImage.setOnClickListener(null)
                    userImage.setImageDrawable(null)
                    userImage.tag = null
                    thumbnail.setImageDrawable(null)
                    thumbnail.tag = null
                    username.visibility = View.GONE
                    title.visibility = View.GONE
                    gameName.visibility = View.GONE
                    viewers.visibility = View.GONE
                    uptime.visibility = View.GONE
                    tagsLayout.removeAllViews()
                    tagsLayout.visibility = View.GONE
                    liveBadge.visibility = View.GONE
                }
            }
        }
    }
}
