package com.github.andreyasadchy.xtra.ui.whispers

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ItemWhisperMessageBinding
import com.github.andreyasadchy.xtra.model.twitchinbox.LocalSendState
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchUserSummary
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperMessage

class WhisperMessagesAdapter(
    private val peer: TwitchUserSummary,
    private val currentUser: TwitchUserSummary?,
    private val onRetry: (WhisperMessage) -> Unit,
    private val onPeerClick: (TwitchUserSummary) -> Unit,
) : ListAdapter<WhisperMessage, WhisperMessagesAdapter.ViewHolder>(DIFF) {
    override fun getItemViewType(position: Int) = if (getItem(position).isMine) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemWhisperMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        isMine = viewType == 1,
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemWhisperMessageBinding, isMine: Boolean) : RecyclerView.ViewHolder(binding.root) {
        init {
            with(binding) {
                messageColumn.gravity = if (isMine) Gravity.END else Gravity.START
                val columnParams = messageColumn.layoutParams as ConstraintLayout.LayoutParams
                val avatarParams = avatar.layoutParams as ConstraintLayout.LayoutParams
                if (isMine) {
                    avatarParams.startToStart = ConstraintLayout.LayoutParams.UNSET
                    avatarParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    columnParams.startToEnd = ConstraintLayout.LayoutParams.UNSET
                    columnParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    columnParams.endToEnd = ConstraintLayout.LayoutParams.UNSET
                    columnParams.endToStart = avatar.id
                    columnParams.horizontalBias = 1f
                    columnParams.marginStart = 0
                    columnParams.marginEnd = dp(8)
                }
                messageColumn.layoutParams = columnParams
                avatar.layoutParams = avatarParams
                avatar.isClickable = !isMine
                avatar.isFocusable = !isMine
                avatar.contentDescription = if (isMine) null else avatar.context.getString(R.string.view_profile)
                avatar.setOnClickListener { if (!isMine) onPeerClick(peer) }
                val sender = if (isMine) currentUser else peer
                avatar.context.imageLoader.enqueue(
                    ImageRequest.Builder(avatar.context)
                        .data(sender?.profileImageUrl?.takeIf { it.isNotBlank() })
                        .placeholder(R.drawable.baseline_person_black_24)
                        .error(R.drawable.baseline_person_black_24)
                        .crossfade(sender?.profileImageUrl?.isNotBlank() == true)
                        .transformations(CircleCropTransformation())
                        .target(avatar)
                        .build(),
                )
            }
        }

        fun bind(item: WhisperMessage) = with(binding) {
            message.text = item.text.ifBlank { message.context.getString(R.string.message_unavailable) }
            if (item.localState == LocalSendState.FAILED) {
                val debug = item.sendError?.takeIf { it.isNotBlank() }
                messageState.text = if (debug == null) {
                    message.context.getString(R.string.message_failed_tap_to_retry)
                } else {
                    message.context.getString(R.string.message_failed_tap_to_retry) + "\n" +
                        message.context.getString(R.string.message_send_debug, debug)
                }
                messageState.visibility = View.VISIBLE
            } else {
                messageState.text = null
                messageState.visibility = View.GONE
            }
            message.alpha = if (item.localState == LocalSendState.SENDING) 0.65f else 1f
            message.setBackgroundResource(if (item.isMine) R.drawable.bg_whisper_outgoing else R.drawable.bg_whisper_incoming)
            message.setOnClickListener { if (item.localState == LocalSendState.FAILED) onRetry(item) }
            messageState.setOnClickListener { if (item.localState == LocalSendState.FAILED) onRetry(item) }
            message.contentDescription = item.text.ifBlank { message.context.getString(R.string.message_unavailable) }
        }

        private fun dp(value: Int): Int = (value * binding.root.resources.displayMetrics.density).toInt()
    }
    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<WhisperMessage>() {
            override fun areItemsTheSame(oldItem: WhisperMessage, newItem: WhisperMessage) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: WhisperMessage, newItem: WhisperMessage) = oldItem == newItem
        }
    }
}
