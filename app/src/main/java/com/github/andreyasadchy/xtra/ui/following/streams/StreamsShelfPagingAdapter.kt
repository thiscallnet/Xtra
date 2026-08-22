package com.github.andreyasadchy.xtra.ui.following.streams

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
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.ItemStreamShelfBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.common.loadStreamProfileImage
import com.github.andreyasadchy.xtra.ui.common.loadStreamThumbnail
import com.github.andreyasadchy.xtra.ui.common.streamContentsSame
import com.github.andreyasadchy.xtra.ui.common.streamIdentity
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.multiview.MultiviewFragment
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlin.time.Clock
import kotlin.time.Instant

class StreamsShelfPagingAdapter(
    private val fragment: Fragment,
    private val selectTag: (String) -> Unit,
) : PagingDataAdapter<Stream, StreamsShelfPagingAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemStreamShelfBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        (fragment.requireContext().applicationContext as XtraApp).xtraModule.streamPreviewCoordinator
            .detachSurface(holder.previewSurface)
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(
        private val binding: ItemStreamShelfBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        val previewSurface get() = binding.previewPlayerView

        fun bind(item: Stream?) {
            val context = fragment.requireContext()
            with(binding) {
                (context.applicationContext as XtraApp).xtraModule.streamPreviewCoordinator
                    .detachSurface(previewPlayerView)

                if (item == null) {
                    reset()
                    return
                }

                root.setOnClickListener { (fragment.activity as? MainActivity)?.startStream(item) }
                loadStreamThumbnail(context, thumbnail, item)
                thumbnail.contentDescription = item.title?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.live)
                liveBadge.visibility = View.VISIBLE
                liveBadge.text = context.getString(R.string.live)

                viewers.text = item.viewerCount?.let { count ->
                    context.resources.getQuantityString(
                        R.plurals.viewers,
                        count,
                        TwitchApiHelper.formatCount(
                            count,
                            context.prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true),
                        ),
                    )
                }.orEmpty()
                viewers.visibility = if (viewers.text.isNullOrBlank()) View.GONE else View.VISIBLE

                val uptimeText = if (context.prefs().getBoolean(C.UI_UPTIME, true)) {
                    item.createdAt?.let { value ->
                        Instant.parseOrNull(value)?.let { createdAt ->
                            val uptime = Clock.System.now() - createdAt
                            if (uptime.isPositive()) DateUtils.formatElapsedTime(uptime.inWholeSeconds) else null
                        }
                    }
                } else null
                uptime.text = uptimeText.orEmpty()
                uptime.visibility = if (uptimeText.isNullOrBlank()) View.GONE else View.VISIBLE

                title.text = item.title?.trim().orEmpty()
                title.visibility = if (title.text.isNullOrBlank()) View.GONE else View.VISIBLE
                if (item.channelName != null) {
                    channel.visibility = View.VISIBLE
                    channel.text = if (item.channelLogin != null && !item.channelLogin.equals(item.channelName, true)) {
                        when (context.prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                            "0" -> "${item.channelName}(${item.channelLogin})"
                            "1" -> item.channelName
                            else -> item.channelLogin
                        }
                    } else {
                        item.channelName
                    }
                } else {
                    channel.text = null
                    channel.visibility = View.GONE
                }
                category.text = item.gameName?.trim().orEmpty()
                category.visibility = if (category.text.isNullOrBlank()) View.GONE else View.VISIBLE

                val channelListener = View.OnClickListener {
                    fragment.findNavController().navigate(
                        ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                            channelId = item.channelId,
                            channelLogin = item.channelLogin,
                            channelName = item.channelName,
                            channelImage = item.channelImage,
                            streamId = item.id,
                        )
                    )
                }
                if (item.channelImage != null) {
                    avatar.visibility = View.VISIBLE
                    loadStreamProfileImage(context, avatar, item)
                    avatar.setOnClickListener(channelListener)
                } else {
                    avatar.visibility = View.INVISIBLE
                    avatar.setImageDrawable(null)
                    avatar.tag = null
                    avatar.setOnClickListener(null)
                }
                channel.setOnClickListener(channelListener)

                category.setOnClickListener(if (item.gameName.isNullOrBlank()) null else View.OnClickListener {
                    fragment.findNavController().navigate(
                        GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                            gameId = item.gameId,
                            gameSlug = item.gameSlug,
                            gameName = item.gameName,
                        )
                    )
                })

                multiview.visibility = if (item.channelLogin.isNullOrBlank()) View.GONE else View.VISIBLE
                multiview.setOnClickListener {
                    (fragment.activity as? MainActivity)?.let { activity ->
                        if (activity.playerFragment != null) activity.closePlayer()
                    }
                    fragment.findNavController().navigate(R.id.multiviewFragment, MultiviewFragment.arguments(item))
                }

                val tags = if (context.prefs().getBoolean(C.UI_TAGS, true)) item.tags.orEmpty().take(2) else emptyList()
                tagOne.text = tags.getOrNull(0).orEmpty()
                tagOne.visibility = if (tags.isNotEmpty()) View.VISIBLE else View.GONE
                tagOne.setOnClickListener(tags.getOrNull(0)?.let { tag -> View.OnClickListener { selectTag(tag) } })
                tagTwo.text = tags.getOrNull(1).orEmpty()
                tagTwo.visibility = if (tags.size > 1) View.VISIBLE else View.GONE
                tagTwo.setOnClickListener(tags.getOrNull(1)?.let { tag -> View.OnClickListener { selectTag(tag) } })
            }
        }

        private fun reset() {
            with(binding) {
                root.setOnClickListener(null)
                avatar.setOnClickListener(null)
                channel.setOnClickListener(null)
                category.setOnClickListener(null)
                multiview.setOnClickListener(null)
                tagOne.setOnClickListener(null)
                tagTwo.setOnClickListener(null)
                avatar.visibility = View.INVISIBLE
                avatar.setImageDrawable(null)
                avatar.tag = null
                thumbnail.setImageDrawable(null)
                thumbnail.tag = null
                title.text = null
                title.visibility = View.GONE
                channel.text = null
                channel.visibility = View.GONE
                category.text = null
                category.visibility = View.GONE
                tagOne.text = null
                tagOne.visibility = View.GONE
                tagTwo.text = null
                tagTwo.visibility = View.GONE
                viewers.text = null
                viewers.visibility = View.GONE
                uptime.text = null
                uptime.visibility = View.GONE
                liveBadge.visibility = View.GONE
                multiview.visibility = View.GONE
            }
        }
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Stream>() {
            override fun areItemsTheSame(oldItem: Stream, newItem: Stream): Boolean =
                oldItem.streamIdentity() == newItem.streamIdentity()

            override fun areContentsTheSame(oldItem: Stream, newItem: Stream): Boolean =
                streamContentsSame(oldItem, newItem)
        }
    }
}
