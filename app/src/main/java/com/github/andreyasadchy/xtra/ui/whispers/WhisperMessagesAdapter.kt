package com.github.andreyasadchy.xtra.ui.whispers

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ItemWhisperMessageBinding
import com.github.andreyasadchy.xtra.model.twitchinbox.LocalSendState
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperMessage

class WhisperMessagesAdapter(private val onRetry: (WhisperMessage) -> Unit) : RecyclerView.Adapter<WhisperMessagesAdapter.ViewHolder>() {
    private var items: List<WhisperMessage> = emptyList()
    fun submitList(value: List<WhisperMessage>) { items = value; notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemWhisperMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    inner class ViewHolder(private val binding: ItemWhisperMessageBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: WhisperMessage) = with(binding) {
            val gravity = if (item.isMine) Gravity.END else Gravity.START
            val messageParams = message.layoutParams as LinearLayout.LayoutParams
            messageParams.gravity = gravity
            message.layoutParams = messageParams
            val stateParams = messageState.layoutParams as LinearLayout.LayoutParams
            stateParams.gravity = gravity
            messageState.layoutParams = stateParams
            message.text = item.text.ifBlank { message.context.getString(R.string.message_unavailable) }
            if (item.localState == LocalSendState.FAILED) {
                messageState.setText(R.string.message_failed_tap_to_retry)
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
    }
}
