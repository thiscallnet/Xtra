package com.github.andreyasadchy.xtra.ui.chat.v2.ui

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEmoteInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatGifInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel

class ChatTimelineAdapter(
    private val assets: ChatAssetRepository,
    private var textSizeSp: Float,
    private var animateGifs: Boolean,
    private val onMessageLongClick: ((ChatMessageId) -> Unit)? = null,
    private val onEmoteClick: ((ChatEmoteInteraction) -> Unit)? = null,
    private val onGifClick: ((ChatGifInteraction) -> Unit)? = null,
    private val onMessageClick: ((ChatMessageId) -> Unit)? = null,
) : ListAdapter<ChatRowUiModel, ChatTimelineAdapter.Holder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ChatMessageTextView(parent.context, assets).also {
            it.setMessageTextSizeSp(textSizeSp)
            it.setAnimateGifs(animateGifs)
            it.setInteractionCallbacks(onMessageLongClick, onEmoteClick, onGifClick)
            it.setMessageClickCallback(onMessageClick)
            it.layoutParams = ViewGroup.LayoutParams(-1, -2)
        },
    )
    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.view.setMessageTextSizeSp(textSizeSp)
        holder.view.setAnimateGifs(animateGifs)
        holder.bind(getItem(position))
    }
    fun setMessageTextSizeSp(value: Float) {
        textSizeSp = value
        for (index in 0 until itemCount) notifyItemChanged(index)
    }
    fun setAnimateGifs(value: Boolean) {
        animateGifs = value
        for (index in 0 until itemCount) notifyItemChanged(index)
    }
    override fun onViewRecycled(holder: Holder) { holder.view.recycle(); super.onViewRecycled(holder) }

    class Holder(val view: ChatMessageTextView) : RecyclerView.ViewHolder(view) {
        fun bind(row: ChatRowUiModel) = view.bind(row)
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ChatRowUiModel>() {
            override fun areItemsTheSame(a: ChatRowUiModel, b: ChatRowUiModel) = a.id == b.id
            override fun areContentsTheSame(a: ChatRowUiModel, b: ChatRowUiModel) = a == b
        }
    }
}
