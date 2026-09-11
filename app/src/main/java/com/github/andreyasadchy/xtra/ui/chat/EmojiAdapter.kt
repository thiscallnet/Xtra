package com.github.andreyasadchy.xtra.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.util.TypedValue
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityViewCommand
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetState
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.databinding.FragmentEmojiPickerListItemBinding
import kotlin.math.roundToInt

internal class EmojiAdapter(
    private val fragment: Fragment,
    private val assets: ChatAssetRepository,
    private val clickListener: (EmojiPickerItem) -> Unit,
    private val favoriteToggleListener: ((EmojiPickerItem) -> Unit)? = null,
    private val reorderContentDescriptionRes: Int = R.string.reorder_favorite_item,
    private val moveBeforeDescriptionRes: Int = R.string.move_favorite_item_before,
    private val moveAfterDescriptionRes: Int = R.string.move_favorite_item_after,
) : RecyclerView.Adapter<EmojiAdapter.ViewHolder>() {
    private val differ = AsyncListDiffer(this, DIFF_CALLBACK)
    private val activeHolders = LinkedHashSet<ViewHolder>()
    private var favoriteValues: Set<String> = emptySet()
    private var pickerVisualSizeDp = DEFAULT_PICKER_VISUAL_SIZE_DP
    private var reorderMode = false
    var itemTouchHelper: ItemTouchHelper? = null
    var accessibilityMoveListener: ((Int, Int) -> Boolean)? = null

    fun submitList(items: List<EmojiPickerItem>) {
        val copy = items.toList()
        prefetch(copy)
        differ.submitList(copy)
    }

    fun prefetch(items: Iterable<EmojiPickerItem>) {
        assets.prefetch(
            items.asSequence()
                .map { Twemoji.asset(it.value).key }
                .distinct()
                .take(EmotePickerImageLoader.INITIAL_PREFETCH_LIMIT)
                .asIterable(),
        )
    }

    fun setFavoriteValues(values: Set<String>) {
        if (favoriteValues == values) return
        favoriteValues = values
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    /** Changes only the artwork bounds. The 48dp cell and its touch target stay unchanged. */
    fun setPickerVisualSizeDp(sizeDp: Float) {
        val normalized = sizeDp.coerceIn(MIN_PICKER_VISUAL_SIZE_DP, PICKER_CELL_SIZE_DP)
        if (pickerVisualSizeDp == normalized) return
        pickerVisualSizeDp = normalized
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    fun setCompactPickerVisualSizeDp(compactEnabled: Boolean, sizeDp: Float) {
        setPickerVisualSizeDp(if (compactEnabled) sizeDp else DEFAULT_PICKER_VISUAL_SIZE_DP)
    }

    fun setReorderMode(enabled: Boolean) {
        if (reorderMode == enabled) return
        reorderMode = enabled
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    fun setDragging(viewHolder: RecyclerView.ViewHolder, dragging: Boolean) {
        (viewHolder as? ViewHolder)?.setDragging(dragging)
    }

    fun dispose() {
        activeHolders.toList().forEach { it.unbind() }
        activeHolders.clear()
    }

    override fun getItemCount(): Int = differ.currentList.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            FragmentEmojiPickerListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(differ.currentList[position])
        prefetch(
            differ.currentList.asSequence()
                .drop(position + 1)
                .take(EmotePickerImageLoader.LOOKAHEAD_PREFETCH_LIMIT)
                .asIterable(),
        )
    }

    inner class ViewHolder(
        private val binding: FragmentEmojiPickerListItemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var observedKey: ChatAssetKey? = null
        private var observer: (() -> Unit)? = null
        private var favoriteAccessibilityActionId: Int? = null
        private val reorderAccessibilityActionIds = mutableListOf<Int>()
        private var isDragging = false

        fun bind(item: EmojiPickerItem) {
            unbind()
            activeHolders += this
            val canReorder = reorderMode
            val contentSizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                pickerVisualSizeDp,
                itemView.resources.displayMetrics,
            ).roundToInt()
            (binding.emoji.layoutParams as FrameLayout.LayoutParams).apply {
                width = contentSizePx
                height = contentSizePx
                gravity = Gravity.CENTER
            }.also { binding.emoji.layoutParams = it }
            (binding.emojiFallback.layoutParams as FrameLayout.LayoutParams).apply {
                width = contentSizePx
                height = contentSizePx
                gravity = Gravity.CENTER
            }.also { binding.emojiFallback.layoutParams = it }
            binding.emojiFallback.textSize = 26f * pickerVisualSizeDp / PICKER_CELL_SIZE_DP
            val key = Twemoji.asset(item.value).key
            observedKey = key
            binding.root.tag = key
            binding.emoji.setImageDrawable(null)
            binding.emojiFallback.text = item.value
            // Do not briefly show the device's Unicode glyph while Twemoji is loading. The
            // glyph can have a different design and would make a recycled row appear to change
            // as it is scrolled out and back into the asset cache.
            binding.emojiFallback.isVisible = false
            binding.emojiFavorite.isVisible = item.name in favoriteValues
            binding.root.isClickable = !canReorder
            binding.root.contentDescription = fragment.getString(
                if (canReorder) reorderContentDescriptionRes else R.string.use_emoji,
                item.alias,
            )
            if (canReorder) {
                binding.root.setOnClickListener(null)
            } else {
                binding.root.setOnClickListener { clickListener(item) }
            }
            binding.dragHandle.isVisible = canReorder
            binding.dragHandle.setOnTouchListener(if (canReorder) {
                { _, event ->
                    if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                        itemTouchHelper?.startDrag(this@ViewHolder)
                    }
                    true
                }
            } else null)
            if (!canReorder && favoriteToggleListener != null) {
                val isFavorite = item.name in favoriteValues
                binding.root.setOnLongClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    favoriteToggleListener.invoke(item)
                    true
                }
                favoriteAccessibilityActionId = ViewCompat.addAccessibilityAction(
                    binding.root,
                    fragment.getString(
                        if (isFavorite) R.string.remove_emoji_from_favorites else R.string.add_emoji_to_favorites,
                    ),
                    AccessibilityViewCommand { _, _ ->
                        favoriteToggleListener.invoke(item)
                        true
                    },
                )
            } else if (canReorder) {
                reorderAccessibilityActionIds += ViewCompat.addAccessibilityAction(
                    binding.root,
                    fragment.getString(moveBeforeDescriptionRes),
                    AccessibilityViewCommand { _, _ ->
                        val position = bindingAdapterPosition
                        accessibilityMoveListener?.invoke(position, position - 1) == true
                    },
                )
                reorderAccessibilityActionIds += ViewCompat.addAccessibilityAction(
                    binding.root,
                    fragment.getString(moveAfterDescriptionRes),
                    AccessibilityViewCommand { _, _ ->
                        val position = bindingAdapterPosition
                        accessibilityMoveListener?.invoke(position, position + 1) == true
                    },
                )
            }
            val updateImage: () -> Unit = {
                binding.root.post {
                    if (binding.root.tag != key) return@post
                    when (val state = assets.peek(key)) {
                        is ChatAssetState.Ready -> {
                            val drawable = state.image.newDrawable()
                            if (drawable == null) {
                                assets.retryIfDrawableUnavailable(key)
                                binding.emoji.setImageDrawable(null)
                                binding.emojiFallback.isVisible = false
                            } else {
                                binding.emoji.setImageDrawable(drawable)
                                binding.emojiFallback.isVisible = false
                            }
                        }
                        is ChatAssetState.Failed -> {
                            binding.emoji.setImageDrawable(null)
                            binding.emojiFallback.isVisible = state.isPresentationTerminal
                        }
                        else -> {
                            binding.emoji.setImageDrawable(null)
                            binding.emojiFallback.isVisible = false
                        }
                    }
                }
            }
            observer = updateImage
            assets.observe(key, updateImage)
            updateImage()
        }

        fun unbind() {
            activeHolders -= this
            isDragging = false
            binding.root.animate().cancel()
            binding.root.scaleX = 1f
            binding.root.scaleY = 1f
            favoriteAccessibilityActionId?.let {
                ViewCompat.removeAccessibilityAction(binding.root, it)
                favoriteAccessibilityActionId = null
            }
            reorderAccessibilityActionIds.forEach { actionId ->
                ViewCompat.removeAccessibilityAction(binding.root, actionId)
            }
            reorderAccessibilityActionIds.clear()
            observer?.let { callback -> observedKey?.let { assets.removeObserver(it, callback) } }
            observer = null
            observedKey = null
            binding.root.tag = null
            binding.root.isClickable = true
            binding.emoji.setImageDrawable(null)
            binding.emojiFallback.text = null
            binding.emojiFavorite.isVisible = false
            binding.dragHandle.isVisible = false
            binding.dragHandle.setOnTouchListener(null)
            binding.root.setOnClickListener(null)
            binding.root.setOnLongClickListener(null)
            binding.root.contentDescription = null
        }

        fun setDragging(dragging: Boolean) {
            isDragging = dragging
            binding.root.animate()
                .scaleX(if (dragging) 1.05f else 1f)
                .scaleY(if (dragging) 1.05f else 1f)
                .setDuration(100L)
                .start()
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.unbind()
        super.onViewRecycled(holder)
    }

    private companion object {
        const val PICKER_CELL_SIZE_DP = 48f
        const val DEFAULT_PICKER_VISUAL_SIZE_DP = 48f
        const val MIN_PICKER_VISUAL_SIZE_DP = 24f
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<EmojiPickerItem>() {
            override fun areItemsTheSame(oldItem: EmojiPickerItem, newItem: EmojiPickerItem): Boolean =
                oldItem.name == newItem.name

            override fun areContentsTheSame(oldItem: EmojiPickerItem, newItem: EmojiPickerItem): Boolean =
                oldItem == newItem
        }
    }
}
