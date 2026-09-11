package com.github.andreyasadchy.xtra.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository

/** Renders every emoji category as one vertically scrolling, sectioned picker. */
internal class CompactEmojiAdapter(
    fragment: Fragment,
    assets: ChatAssetRepository,
    clickListener: (EmojiPickerItem) -> Unit,
    favoriteToggleListener: ((EmojiPickerItem) -> Unit)?,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val emojiAdapter = EmojiAdapter(
        fragment = fragment,
        assets = assets,
        clickListener = clickListener,
        favoriteToggleListener = favoriteToggleListener,
    )
    private var items: List<Item> = emptyList()

    fun submitSections(sections: List<Pair<EmojiPickerCategory, List<EmojiPickerItem>>>) {
        items = sections.flatMap { (category, emojis) ->
            if (emojis.isEmpty()) emptyList()
            else listOf(Item.Header(category)) + emojis.map(Item::EmojiItem)
        }
        notifyDataSetChanged()
    }

    fun setFavoriteValues(values: Set<String>) {
        emojiAdapter.setFavoriteValues(values)
        if (itemCount > 0) notifyDataSetChanged()
    }

    fun setPickerVisualSizeDp(sizeDp: Float) {
        emojiAdapter.setPickerVisualSizeDp(sizeDp)
        if (itemCount > 0) notifyDataSetChanged()
    }

    fun dispose() {
        emojiAdapter.dispose()
    }

    fun isHeader(position: Int): Boolean = items.getOrNull(position) is Item.Header

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Item.Header -> VIEW_TYPE_HEADER
        is Item.EmojiItem -> VIEW_TYPE_EMOJI
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
        VIEW_TYPE_HEADER -> SectionHeaderViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.fragment_emotes_section_header, parent, false),
        )
        else -> emojiAdapter.onCreateViewHolder(parent, viewType)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Header -> (holder as SectionHeaderViewHolder).title.text =
                holder.itemView.context.getString(item.category.titleRes)
            is Item.EmojiItem -> (holder as EmojiAdapter.ViewHolder).bind(item.emoji)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is EmojiAdapter.ViewHolder) emojiAdapter.onViewRecycled(holder)
        super.onViewRecycled(holder)
    }

    private sealed interface Item {
        data class Header(val category: EmojiPickerCategory) : Item
        data class EmojiItem(val emoji: EmojiPickerItem) : Item
    }

    private class SectionHeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
    }

    private companion object {
        const val VIEW_TYPE_HEADER = 1
        const val VIEW_TYPE_EMOJI = 2
    }
}
