package com.github.andreyasadchy.xtra.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.TwitchEmoteGroup
import com.github.andreyasadchy.xtra.model.chat.FavoriteEmoteKey

/**
 * The Twitch tab keeps the same emote cells as the existing picker. When enabled, it inserts
 * lightweight full-width dividers before each non-empty availability group.
 */
internal class TwitchEmotesAdapter(
    fragment: Fragment,
    clickListener: (Emote) -> Unit,
    emoteQuality: String,
    imageLibrary: String?,
    favoriteToggleListener: ((Emote) -> Unit)?,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val emoteAdapter = EmotesAdapter(
        fragment = fragment,
        clickListener = clickListener,
        emoteQuality = emoteQuality,
        imageLibrary = imageLibrary,
        favoriteToggleListener = favoriteToggleListener,
    )
    private var compactEnabled = false
    private var emotes = emptyList<Emote>()
    private var items: List<Item> = emptyList()

    fun submitList(newItems: List<Emote>) {
        emotes = newItems.toList()
        rebuildItems()
        notifyDataSetChanged()
    }

    fun setCompactEnabled(enabled: Boolean) {
        if (compactEnabled == enabled) return
        compactEnabled = enabled
        rebuildItems()
        notifyDataSetChanged()
    }

    fun setFavoriteKeys(keys: Set<FavoriteEmoteKey>) {
        emoteAdapter.setFavoriteKeys(keys)
        if (itemCount > 0) notifyDataSetChanged()
    }

    fun setPickerVisualSizeDp(sizeDp: Float) {
        emoteAdapter.setPickerVisualSizeDp(sizeDp)
        if (itemCount > 0) notifyDataSetChanged()
    }

    fun isHeader(position: Int): Boolean = items.getOrNull(position) is Item.Header

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Item.Header -> VIEW_TYPE_HEADER
        is Item.EmoteItem -> VIEW_TYPE_EMOTE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
        VIEW_TYPE_HEADER -> SectionHeaderViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.fragment_emotes_section_header, parent, false),
        )
        else -> emoteAdapter.onCreateViewHolder(parent, viewType)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Header -> (holder as SectionHeaderViewHolder).title.text =
                holder.itemView.context.getString(item.group.titleRes)
            is Item.EmoteItem -> (holder as EmotesAdapter.ViewHolder).bind(item.emote)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is EmotesAdapter.ViewHolder) emoteAdapter.onViewRecycled(holder)
        super.onViewRecycled(holder)
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is EmotesAdapter.ViewHolder) emoteAdapter.onViewAttachedToWindow(holder)
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        if (holder is EmotesAdapter.ViewHolder) emoteAdapter.onViewDetachedFromWindow(holder)
        super.onViewDetachedFromWindow(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        emoteAdapter.onDetachedFromRecyclerView(recyclerView)
        super.onDetachedFromRecyclerView(recyclerView)
    }

    private fun rebuildItems() {
        if (!compactEnabled) {
            items = emotes.map(Item::EmoteItem)
            return
        }
        val grouped = emotes
            .filter { !it.name.isNullOrBlank() }
            .groupBy { it.twitchGroup ?: TwitchEmoteGroup.GLOBAL }
        items = TwitchEmoteGroup.entries.flatMap { group ->
            val groupEmotes = grouped[group].orEmpty()
            if (groupEmotes.isEmpty()) emptyList()
            else listOf(Item.Header(group)) + groupEmotes.map(Item::EmoteItem)
        }
    }

    private sealed interface Item {
        data class Header(val group: TwitchEmoteGroup) : Item
        data class EmoteItem(val emote: Emote) : Item
    }

    private class SectionHeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
    }

    private companion object {
        const val VIEW_TYPE_HEADER = 1
        const val VIEW_TYPE_EMOTE = 2

        val TwitchEmoteGroup.titleRes: Int
            get() = when (this) {
                TwitchEmoteGroup.UNLOCKED -> R.string.twitch_emote_group_unlocked
                TwitchEmoteGroup.HYPE_TRAIN -> R.string.twitch_emote_group_hype_train
                TwitchEmoteGroup.SUBSCRIBER -> R.string.twitch_emote_group_subscriber
                TwitchEmoteGroup.GLOBAL -> R.string.twitch_emote_group_global
            }
    }
}
