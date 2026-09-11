package com.github.andreyasadchy.xtra.ui.chat

import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository

internal class FavoritePickerAdapter(
    fragment: Fragment,
    assets: ChatAssetRepository,
    private val emoteClickListener: (Emote) -> Unit,
    private val emoteFavoriteToggleListener: (Emote) -> Unit,
    private val emojiClickListener: (EmojiPickerItem) -> Unit,
    private val emojiFavoriteToggleListener: (EmojiPickerItem) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val emoteAdapter = EmotesAdapter(
        fragment = fragment,
        clickListener = emoteClickListener,
        emoteQuality = "4",
        imageLibrary = "0",
        favoriteToggleListener = emoteFavoriteToggleListener,
        reorderable = true,
        reorderContentDescriptionRes = R.string.reorder_favorite_item,
        moveBeforeDescriptionRes = R.string.move_favorite_item_before,
        moveAfterDescriptionRes = R.string.move_favorite_item_after,
    )
    private val emojiAdapter = EmojiAdapter(
        fragment = fragment,
        assets = assets,
        clickListener = emojiClickListener,
        favoriteToggleListener = emojiFavoriteToggleListener,
        reorderContentDescriptionRes = R.string.reorder_favorite_item,
        moveBeforeDescriptionRes = R.string.move_favorite_item_before,
        moveAfterDescriptionRes = R.string.move_favorite_item_after,
    )
    private var items = emptyList<FavoritePickerItem>()
    private var reorderMode = false

    var itemTouchHelper: ItemTouchHelper? = null
        set(value) {
            field = value
            emoteAdapter.itemTouchHelper = value
            emojiAdapter.itemTouchHelper = value
        }
    var accessibilityMoveListener: ((Int, Int) -> Boolean)? = null
        set(value) {
            field = value
            emoteAdapter.accessibilityMoveListener = value
            emojiAdapter.accessibilityMoveListener = value
        }

    fun submitList(newItems: List<FavoritePickerItem>) {
        if (items.hasSameFavoritePickerBinding(newItems)) return
        items = newItems.toList()
        emoteAdapter.prefetch(
            items.asSequence()
                .mapNotNull { (it as? FavoritePickerItem.EmoteItem)?.emote }
                .take(EmotePickerImageLoader.INITIAL_PREFETCH_LIMIT)
                .asIterable(),
        )
        emojiAdapter.prefetch(
            items.asSequence()
                .mapNotNull { (it as? FavoritePickerItem.EmojiItem)?.emoji }
                .take(EmotePickerImageLoader.INITIAL_PREFETCH_LIMIT)
                .asIterable(),
        )
        notifyDataSetChanged()
    }

    fun currentItems(): List<FavoritePickerItem> = items

    fun moveItem(from: Int, to: Int): Boolean {
        if (!reorderMode || from !in items.indices || to !in items.indices || from == to) return false
        val mutableItems = items.toMutableList()
        val item = mutableItems.removeAt(from)
        mutableItems.add(to, item)
        items = mutableItems
        notifyItemMoved(from, to)
        return true
    }

    fun setFavoriteKeys(keys: Set<com.github.andreyasadchy.xtra.model.chat.FavoriteEmoteKey>) {
        emoteAdapter.setFavoriteKeys(keys)
    }

    fun setFavoriteValues(values: Set<String>) {
        emojiAdapter.setFavoriteValues(values)
    }

    fun setCompactPickerVisualSizeDp(compactEnabled: Boolean, sizeDp: Float) {
        emoteAdapter.setCompactPickerVisualSizeDp(compactEnabled, sizeDp)
        emojiAdapter.setCompactPickerVisualSizeDp(compactEnabled, sizeDp)
        if (itemCount > 0) notifyDataSetChanged()
    }

    fun setReorderMode(enabled: Boolean) {
        if (reorderMode == enabled) return
        reorderMode = enabled
        emoteAdapter.setReorderMode(enabled)
        emojiAdapter.setReorderMode(enabled)
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    fun setDragging(viewHolder: RecyclerView.ViewHolder, dragging: Boolean) {
        when (viewHolder) {
            is EmotesAdapter.ViewHolder -> emoteAdapter.setDragging(viewHolder, dragging)
            is EmojiAdapter.ViewHolder -> emojiAdapter.setDragging(viewHolder, dragging)
        }
    }

    fun dispose() {
        emojiAdapter.dispose()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is FavoritePickerItem.EmoteItem -> VIEW_TYPE_EMOTE
        is FavoritePickerItem.EmojiItem -> VIEW_TYPE_EMOJI
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
        VIEW_TYPE_EMOTE -> emoteAdapter.onCreateViewHolder(parent, viewType)
        else -> emojiAdapter.onCreateViewHolder(parent, viewType)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is FavoritePickerItem.EmoteItem -> {
                (holder as EmotesAdapter.ViewHolder).bind(item.emote)
                emoteAdapter.prefetch(
                    items.asSequence()
                        .drop(position + 1)
                        .mapNotNull { (it as? FavoritePickerItem.EmoteItem)?.emote }
                        .take(EmotePickerImageLoader.LOOKAHEAD_PREFETCH_LIMIT)
                        .asIterable(),
                )
            }
            is FavoritePickerItem.EmojiItem -> {
                (holder as EmojiAdapter.ViewHolder).bind(item.emoji)
                emojiAdapter.prefetch(
                    items.asSequence()
                        .drop(position + 1)
                        .mapNotNull { (it as? FavoritePickerItem.EmojiItem)?.emoji }
                        .take(EmotePickerImageLoader.LOOKAHEAD_PREFETCH_LIMIT)
                        .asIterable(),
                )
            }
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is EmotesAdapter.ViewHolder -> emoteAdapter.onViewAttachedToWindow(holder)
            is EmojiAdapter.ViewHolder -> emojiAdapter.onViewAttachedToWindow(holder)
        }
        super.onViewAttachedToWindow(holder)
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is EmotesAdapter.ViewHolder -> emoteAdapter.onViewDetachedFromWindow(holder)
            is EmojiAdapter.ViewHolder -> emojiAdapter.onViewDetachedFromWindow(holder)
        }
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is EmotesAdapter.ViewHolder -> emoteAdapter.onViewRecycled(holder)
            is EmojiAdapter.ViewHolder -> emojiAdapter.onViewRecycled(holder)
        }
        super.onViewRecycled(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        emoteAdapter.onDetachedFromRecyclerView(recyclerView)
        emojiAdapter.onDetachedFromRecyclerView(recyclerView)
        super.onDetachedFromRecyclerView(recyclerView)
    }

    private companion object {
        const val VIEW_TYPE_EMOTE = 0
        const val VIEW_TYPE_EMOJI = 1
    }
}
