package com.github.andreyasadchy.xtra.ui.chat.v2.ui

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel

class ChatTimelineAdapter(private val assets: ChatAssetRepository) : ListAdapter<ChatRowUiModel, ChatTimelineAdapter.Holder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ChatMessageTextView(parent.context, assets).also { it.layoutParams = ViewGroup.LayoutParams(-1, -2) },
    )
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
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
