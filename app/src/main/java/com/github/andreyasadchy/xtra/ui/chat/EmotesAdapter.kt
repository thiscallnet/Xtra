package com.github.andreyasadchy.xtra.ui.chat

import android.annotation.SuppressLint
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.fragment.app.Fragment
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityViewCommand
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentEmotesListItemBinding
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.FavoriteEmoteKey
import com.github.andreyasadchy.xtra.model.chat.favoriteKey

internal fun <T> moveListItem(items: MutableList<T>, from: Int, to: Int): Boolean {
    if (from !in items.indices || to !in items.indices || from == to) return false
    val item = items.removeAt(from)
    items.add(to, item)
    return true
}

/**
 * Binding-relevant equality for picker cells. [Emote.equals] only compares
 * [Emote.name], but Favorites treats the live instance (URLs included) as the
 * source of truth, so a catalog refresh that keeps the name while changing
 * the asset must still count as a change.
 */
internal fun Emote.hasSamePickerBinding(other: Emote): Boolean =
    favoriteKey() == other.favoriteKey() &&
        name == other.name &&
        url1x == other.url1x &&
        url2x == other.url2x &&
        url3x == other.url3x &&
        url4x == other.url4x &&
        format == other.format &&
        source == other.source

internal fun List<Emote>.hasSamePickerBinding(other: List<Emote>): Boolean =
    size == other.size && indices.all { this[it].hasSamePickerBinding(other[it]) }

class EmotesAdapter(
    private val fragment: Fragment,
    private val clickListener: (Emote) -> Unit,
    private val emoteQuality: String,
    private val imageLibrary: String?,
    private val favoriteToggleListener: ((Emote) -> Unit)? = null,
    private val consumeLongPress: Boolean = false,
    private val reorderable: Boolean = false,
) : RecyclerView.Adapter<EmotesAdapter.ViewHolder>() {

    private val differ = AsyncListDiffer(this, EMOTE_DIFF_CALLBACK)
    private val items = mutableListOf<Emote>()
    private var favoriteKeys: Set<FavoriteEmoteKey> = emptySet()
    private var reorderMode = false
    var itemTouchHelper: ItemTouchHelper? = null
    var accessibilityMoveListener: ((Int, Int) -> Boolean)? = null

    override fun getItemCount(): Int = if (reorderable) items.size else differ.currentList.size

    /**
     * Copies the list so an active drag can update the adapter's data without
     * mutating a list owned by a caller or by RecyclerView.
     */
    fun submitList(newItems: List<Emote>) {
        if (reorderable) {
            // The favorites tab rebinds every item on notifyDataSetChanged,
            // which reloads all visible images. Skip redundant submissions
            // from coincident flows so the grid does not flicker. The check
            // is binding-aware: Emote.equals only compares names, while a
            // catalog refresh may keep the name and change the asset.
            if (items.hasSamePickerBinding(newItems)) return
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        } else {
            differ.submitList(newItems.toList())
        }
    }

    fun setReorderMode(enabled: Boolean) {
        if (!reorderable || reorderMode == enabled) return
        reorderMode = enabled
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount)
        }
    }

    fun moveItem(from: Int, to: Int): Boolean {
        if (!reorderMode) return false
        if (!moveListItem(items, from, to)) return false
        notifyItemMoved(from, to)
        return true
    }

    fun setDragging(viewHolder: RecyclerView.ViewHolder, dragging: Boolean) {
        (viewHolder as? ViewHolder)?.setDragging(dragging)
    }

    fun currentItems(): List<Emote> = if (reorderable) items.toList() else differ.currentList

    fun setFavoriteKeys(keys: Set<FavoriteEmoteKey>) {
        if (favoriteKeys != keys) {
            favoriteKeys = keys
            // The reorderable favorites grid does not render anything from
            // these keys (only the long-press label of other tabs does), so
            // skip the full rebind that would reload every visible image.
            if (!reorderable && itemCount > 0) {
                notifyItemRangeChanged(0, itemCount)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = FragmentEmotesListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val currentItems = if (reorderable) items else differ.currentList
        holder.bind(currentItems.getOrNull(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.resetReorderVisuals()
        super.onViewRecycled(holder)
    }

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)
        holder.restoreReorderVisuals()
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        holder.resetReorderVisuals()
        super.onViewDetachedFromWindow(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        for (index in 0 until recyclerView.childCount) {
            (recyclerView.getChildViewHolder(recyclerView.getChildAt(index)) as? ViewHolder)
                ?.resetReorderVisuals()
        }
        super.onDetachedFromRecyclerView(recyclerView)
    }

    inner class ViewHolder(
        private val binding: FragmentEmotesListItemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var favoriteAccessibilityActionId: Int? = null
        private val reorderAccessibilityActionIds = mutableListOf<Int>()
        private var reorderAnimator: ObjectAnimator? = null
        private var isDragging = false

        @SuppressLint("ClickableViewAccessibility")
        fun bind(item: Emote?) {
            resetReorderVisuals()
            setReorderMode(
                enabled = reorderable && reorderMode && item != null,
                seed = item?.let(::reorderSeed) ?: 0,
            )
            with(binding) {
                favoriteAccessibilityActionId?.let {
                    ViewCompat.removeAccessibilityAction(emote, it)
                    favoriteAccessibilityActionId = null
                }
                reorderAccessibilityActionIds.forEach { actionId ->
                    ViewCompat.removeAccessibilityAction(emote, actionId)
                }
                reorderAccessibilityActionIds.clear()
                emote.setOnClickListener(null)
                emote.setOnLongClickListener(null)
                emote.isClickable = false
                val canReorder = reorderable && reorderMode
                dragHandle.visibility = if (canReorder) View.VISIBLE else View.GONE
                dragHandle.setOnTouchListener(if (canReorder) {
                    { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            itemTouchHelper?.startDrag(this@ViewHolder)
                        }
                        true
                    }
                } else null)
                if (item != null) {
                    emote.contentDescription = fragment.getString(
                        if (canReorder) R.string.reorder_favorite_emote else R.string.use_emote,
                        item.name,
                    )
                    emote.isFocusable = true
                    val imageUrl = when (emoteQuality) {
                        "4" -> item.url4x ?: item.url3x ?: item.url2x ?: item.url1x
                        "3" -> item.url3x ?: item.url2x ?: item.url1x
                        "2" -> item.url2x ?: item.url1x
                        else -> item.url1x
                    }
                    // Rebinds (scroll, list resubmission) must not reload an
                    // image that is already displayed: the crossfade would
                    // blink even when fading from the image onto itself.
                    val alreadyLoaded = emote.getTag(R.id.emote_image_url_key) == imageUrl && emote.drawable != null
                    if (alreadyLoaded) {
                        // Keep the current drawable; fall through to listeners.
                    } else if (imageLibrary == "0" || (imageLibrary == "1" && !item.format.equals("webp", true))) {
                        emote.setTag(R.id.emote_image_url_key, imageUrl)
                        fragment.requireContext().imageLoader.enqueue(
                            ImageRequest.Builder(fragment.requireContext()).apply {
                                data(imageUrl)
                                if (item.thirdParty) {
                                    httpHeaders(NetworkHeaders.Builder().apply {
                                        add("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                                    }.build())
                                }
                                crossfade(true)
                                target(emote)
                            }.build()
                        )
                    } else {
                        emote.setTag(R.id.emote_image_url_key, imageUrl)
                        Glide.with(fragment)
                            .load(
                                imageUrl.let {
                                    if (item.thirdParty) {
                                        GlideUrl(it) { mapOf("User-Agent" to "Xtra/" + BuildConfig.VERSION_NAME) }
                                    } else it
                                }
                            )
                            .diskCacheStrategy(DiskCacheStrategy.DATA)
                            .transition(DrawableTransitionOptions.withCrossFade())
                            .into(emote)
                    }
                    if (!canReorder) {
                        emote.setOnClickListener { clickListener(item) }
                    }
                    val key = item.favoriteKey()
                    if (!canReorder && favoriteToggleListener != null && key != null) {
                        val isFavorite = key in favoriteKeys
                        emote.setOnLongClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            favoriteToggleListener.invoke(item)
                            true
                        }
                        favoriteAccessibilityActionId = ViewCompat.addAccessibilityAction(
                            emote,
                            fragment.getString(
                                if (isFavorite) R.string.remove_emote_from_favorites else R.string.add_emote_to_favorites,
                            ),
                            AccessibilityViewCommand { _, _ ->
                                favoriteToggleListener.invoke(item)
                                true
                            },
                        )
                    } else if (!canReorder && consumeLongPress) {
                        emote.setOnLongClickListener { true }
                    }
                    if (canReorder) {
                        reorderAccessibilityActionIds += ViewCompat.addAccessibilityAction(
                            emote,
                            fragment.getString(R.string.move_favorite_emote_before),
                            AccessibilityViewCommand { _, _ ->
                                val position = bindingAdapterPosition
                                accessibilityMoveListener?.invoke(position, position - 1) == true
                            },
                        )
                        reorderAccessibilityActionIds += ViewCompat.addAccessibilityAction(
                            emote,
                            fragment.getString(R.string.move_favorite_emote_after),
                            AccessibilityViewCommand { _, _ ->
                                val position = bindingAdapterPosition
                                accessibilityMoveListener?.invoke(position, position + 1) == true
                            },
                        )
                    }
                }
            }
        }

        fun resetReorderVisuals() {
            isDragging = false
            reorderAnimator?.cancel()
            reorderAnimator = null
            itemView.animate().cancel()
            itemView.rotation = 0f
            itemView.scaleX = 1f
            itemView.scaleY = 1f
        }

        fun restoreReorderVisuals() {
            if (reorderable && reorderMode) {
                setReorderMode(true, reorderSeedForBoundItem())
            } else {
                resetReorderVisuals()
            }
        }

        fun setDragging(dragging: Boolean) {
            isDragging = dragging
            reorderAnimator?.cancel()
            reorderAnimator = null
            itemView.animate().cancel()
            itemView.rotation = 0f
            itemView.animate()
                .scaleX(if (dragging) DRAG_SCALE else 1f)
                .scaleY(if (dragging) DRAG_SCALE else 1f)
                .setDuration(DRAG_SCALE_DURATION_MS)
                .start()
            if (!dragging && reorderable && reorderMode) {
                startReorderAnimation(reorderSeedForBoundItem())
            }
        }

        private fun setReorderMode(enabled: Boolean, seed: Int) {
            reorderAnimator?.cancel()
            reorderAnimator = null
            itemView.animate().cancel()
            itemView.rotation = 0f

            if (!enabled || isDragging) {
                itemView.scaleX = if (isDragging) DRAG_SCALE else 1f
                itemView.scaleY = if (isDragging) DRAG_SCALE else 1f
                return
            }

            itemView.scaleX = 1f
            itemView.scaleY = 1f
            startReorderAnimation(seed)
        }

        private fun startReorderAnimation(seed: Int) {
            if (!itemView.isAttachedToWindow) return

            val phase = if (seed and 1 == 0) 1f else -1f
            val durationOffset = kotlin.math.abs(seed.toLong()) % REORDER_DURATION_VARIATION_MS
            reorderAnimator = ObjectAnimator.ofFloat(
                itemView,
                View.ROTATION,
                -REORDER_ROTATION_DEGREES * phase,
                REORDER_ROTATION_DEGREES * phase,
            ).apply {
                duration = REORDER_ANIMATION_DURATION_MS + durationOffset
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        }

        private fun reorderSeedForBoundItem(): Int {
            val position = bindingAdapterPosition
            val item = if (position != RecyclerView.NO_POSITION) {
                (if (reorderable) items else differ.currentList).getOrNull(position)
            } else {
                null
            }
            return item?.let(::reorderSeed) ?: 0
        }
    }

    private companion object {
        const val DRAG_SCALE = 1.05f
        const val DRAG_SCALE_DURATION_MS = 100L
        const val REORDER_ANIMATION_DURATION_MS = 130L
        const val REORDER_DURATION_VARIATION_MS = 30L
        const val REORDER_ROTATION_DEGREES = 1.2f

        fun reorderSeed(item: Emote): Int {
            return item.favoriteKey()?.hashCode() ?: item.name.hashCode()
        }

        val EMOTE_DIFF_CALLBACK = object : DiffUtil.ItemCallback<Emote>() {
            override fun areItemsTheSame(oldItem: Emote, newItem: Emote): Boolean {
                val oldKey = oldItem.favoriteKey()
                val newKey = newItem.favoriteKey()
                return if (oldKey != null || newKey != null) {
                    oldKey == newKey
                } else {
                    oldItem.name == newItem.name
                }
            }

            override fun areContentsTheSame(oldItem: Emote, newItem: Emote): Boolean {
                return oldItem.name == newItem.name &&
                        oldItem.url1x == newItem.url1x &&
                        oldItem.url2x == newItem.url2x &&
                        oldItem.url3x == newItem.url3x &&
                        oldItem.url4x == newItem.url4x &&
                        oldItem.format == newItem.format
            }
        }
    }
}
