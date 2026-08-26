package com.github.andreyasadchy.xtra.ui.whispers

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.error
import coil3.request.fallback
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
) : RecyclerView.Adapter<WhisperMessagesAdapter.ViewHolder>() {
    private var items: List<WhisperMessage> = emptyList()
    fun submitList(value: List<WhisperMessage>) { items = value; notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemWhisperMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    inner class ViewHolder(private val binding: ItemWhisperMessageBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: WhisperMessage) = with(binding) {
            val gravity = if (item.isMine) Gravity.END else Gravity.START
            messageRow.gravity = gravity or Gravity.CENTER_VERTICAL
            messageColumn.gravity = gravity
            val columnParams = messageColumn.layoutParams as LinearLayout.LayoutParams
            columnParams.marginStart = if (item.isMine) 0 else dp(8)
            columnParams.marginEnd = if (item.isMine) dp(8) else 0
            messageColumn.layoutParams = columnParams
            messageRow.removeAllViews()
            if (item.isMine) {
                messageRow.addView(messageColumn)
                messageRow.addView(avatar)
            } else {
                messageRow.addView(avatar)
                messageRow.addView(messageColumn)
            }
            avatar.setImageResource(R.drawable.baseline_person_black_24)
            avatar.isClickable = !item.isMine
            avatar.isFocusable = !item.isMine
            avatar.contentDescription = if (item.isMine) null else avatar.context.getString(R.string.view_profile)
            avatar.setOnClickListener { if (!item.isMine) onPeerClick(peer) }
            val sender = if (item.isMine) currentUser else peer
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
}
