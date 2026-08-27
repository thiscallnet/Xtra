package com.github.andreyasadchy.xtra.ui.following.channels

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
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentFollowedChannelsListItemBinding
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.common.FeedImageRequestBag
import com.github.andreyasadchy.xtra.ui.common.FeedImageRequestOwner
import com.github.andreyasadchy.xtra.ui.common.FeedUiPreferencesStore
import com.github.andreyasadchy.xtra.ui.common.StreamThumbnailIdleScheduler
import com.github.andreyasadchy.xtra.ui.common.restoreDecodedMemoryImage
import com.github.andreyasadchy.xtra.ui.common.ChannelCardPresentationCache

class FollowedChannelsAdapter(
    private val fragment: Fragment,
) : PagingDataAdapter<User, FollowedChannelsAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<User>() {
        override fun areItemsTheSame(oldItem: User, newItem: User): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: User, newItem: User): Boolean =
            oldItem.id == newItem.id &&
                oldItem.login == newItem.login &&
                oldItem.name == newItem.name &&
                oldItem.profileImageURL == newItem.profileImageURL &&
                oldItem.lastBroadcast == newItem.lastBroadcast &&
                oldItem.followedAt == newItem.followedAt &&
                oldItem.accountFollow == newItem.accountFollow &&
                oldItem.localFollow == newItem.localFollow
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
        val binding = FragmentFollowedChannelsListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PagingViewHolder(binding, fragment)
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
        private val binding: FragmentFollowedChannelsListItemBinding,
        private val fragment: Fragment,
    ) : RecyclerView.ViewHolder(binding.root), FeedImageRequestOwner {
        private val imageRequests = FeedImageRequestBag()
        private var boundUserId: String? = null
        private var boundUser: User? = null

        init {
            binding.root.setOnClickListener {
                boundUser?.let { user ->
                    fragment.findNavController().navigate(
                        ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                            channelId = user.id,
                            channelLogin = user.login,
                            channelName = user.name,
                            channelImage = user.profileImage,
                        )
                    )
                }
            }
        }

        fun beginImageBind(item: User?) {
            imageLoadScheduler.clear(this)
            imageRequests.cancel()
            boundUserId = item?.id
        }

        override fun cancelImageRequests() {
            imageRequests.cancel()
        }

        override fun pauseImageRequests() {
            imageRequests.cancel(preserveRegistrations = true)
        }

        fun cancelImageWork() {
            cancelImageRequests()
            boundUserId = null
            boundUser = null
        }

        fun bind(item: User?) {
            boundUser = item
            with(binding) {
                if (item != null) {
                    val context = fragment.requireContext()
                    val uiPreferences = FeedUiPreferencesStore.current(context)
                    val presentation = ChannelCardPresentationCache.get(item, uiPreferences)
                    if (presentation == null) {
                        ChannelCardPresentationCache.request(context, item, uiPreferences) {
                            if (boundUser === item && binding.root.isAttachedToWindow) applyPresentation(it)
                        }
                    }
                    if (item.profileImage != null) {
                        userImage.visibility = View.VISIBLE
                        userImage.contentDescription = item.name?.let {
                            context.getString(R.string.player_open_channel, it)
                        }
                        val userId = item.id
                        val imageKey = "channel:$userId|${item.profileImage}|round=${uiPreferences.roundUserImage}"
                        if (userImage.tag != imageKey) {
                            userImage.setImageDrawable(null)
                            userImage.tag = imageKey
                        }
                        restoreDecodedMemoryImage(imageKey, userImage)
                        imageLoadScheduler.runOrDefer(this@PagingViewHolder, userImage) {
                            if (!binding.root.isAttachedToWindow || boundUserId != userId) return@runOrDefer
                            imageRequests.replace(
                                userImage,
                                context.imageLoader.enqueue(
                                    ImageRequest.Builder(context).apply {
                                        data(item.profileImage)
                                        memoryCacheKey(imageKey)
                                        if (uiPreferences.roundUserImage) {
                                            transformations(CircleCropTransformation())
                                        }
                                        crossfade(false)
                                        target(userImage)
                                    }.build()
                                ),
                            )
                        }
                    } else {
                        userImage.visibility = View.GONE
                        userImage.contentDescription = null
                        userImage.setImageDrawable(null)
                        userImage.tag = null
                    }
                    if (item.name != null) {
                        username.visibility = View.VISIBLE
                        username.text = presentation?.username ?: item.name
                    } else {
                        username.visibility = View.GONE
                    }
                    if (item.lastBroadcast != null) {
                        val text = presentation?.lastBroadcast
                        if (text != null) {
                            userStream.visibility = View.VISIBLE
                            userStream.text = text
                        } else {
                            userStream.visibility = View.GONE
                        }
                    } else {
                        userStream.visibility = View.GONE
                    }
                    if (item.followedAt != null) {
                        val text = presentation?.followedAt
                        if (text != null) {
                            userFollowed.visibility = View.VISIBLE
                            userFollowed.text = text
                        } else {
                            userFollowed.visibility = View.GONE
                        }
                    } else {
                        userFollowed.visibility = View.GONE
                    }
                    if (item.accountFollow) {
                        accountText.visibility = View.VISIBLE
                    } else {
                        accountText.visibility = View.GONE
                    }
                    if (item.localFollow) {
                        localText.visibility = View.VISIBLE
                    } else {
                        localText.visibility = View.GONE
                    }
                }
            }
        }

        private fun applyPresentation(presentation: com.github.andreyasadchy.xtra.ui.common.ChannelCardPresentation) {
            if (boundUser == null) return
            with(binding) {
                if (presentation.username != null) {
                    username.visibility = View.VISIBLE
                    username.text = presentation.username
                } else {
                    username.visibility = View.GONE
                }
                if (presentation.lastBroadcast != null) {
                    userStream.visibility = View.VISIBLE
                    userStream.text = presentation.lastBroadcast
                } else {
                    userStream.visibility = View.GONE
                }
                if (presentation.followedAt != null) {
                    userFollowed.visibility = View.VISIBLE
                    userFollowed.text = presentation.followedAt
                } else {
                    userFollowed.visibility = View.GONE
                }
            }
        }
    }
}
