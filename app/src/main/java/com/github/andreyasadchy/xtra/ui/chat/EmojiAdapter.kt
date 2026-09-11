package com.github.andreyasadchy.xtra.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.HapticFeedbackConstants
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityViewCommand
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetState
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.databinding.FragmentEmojiPickerListItemBinding

internal class EmojiAdapter(
    private val fragment: Fragment,
    private val assets: ChatAssetRepository,
    private val clickListener: (EmojiPickerItem) -> Unit,
    private val favoriteToggleListener: ((EmojiPickerItem) -> Unit)? = null,
) : RecyclerView.Adapter<EmojiAdapter.ViewHolder>() {
    private val differ = AsyncListDiffer(this, DIFF_CALLBACK)
    private val activeHolders = LinkedHashSet<ViewHolder>()
    private var favoriteValues: Set<String> = emptySet()

    fun submitList(items: List<EmojiPickerItem>) = differ.submitList(items.toList())

    fun setFavoriteValues(values: Set<String>) {
        if (favoriteValues == values) return
        favoriteValues = values
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
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
    }

    inner class ViewHolder(
        private val binding: FragmentEmojiPickerListItemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var observedKey: ChatAssetKey? = null
        private var observer: (() -> Unit)? = null
        private var favoriteAccessibilityActionId: Int? = null

        fun bind(item: EmojiPickerItem) {
            unbind()
            activeHolders += this
            val key = Twemoji.asset(item.value).key
            observedKey = key
            binding.root.tag = key
            binding.emoji.setImageDrawable(null)
            binding.emojiFallback.text = item.value
            binding.emojiFallback.isVisible = true
            binding.emojiFavorite.isVisible = item.name in favoriteValues
            binding.root.contentDescription = fragment.getString(R.string.use_emoji, item.alias)
            binding.root.setOnClickListener { clickListener(item) }
            if (favoriteToggleListener != null) {
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
                                binding.emojiFallback.isVisible = true
                            } else {
                                binding.emoji.setImageDrawable(drawable)
                                binding.emojiFallback.isVisible = false
                            }
                        }
                        else -> {
                            binding.emoji.setImageDrawable(null)
                            binding.emojiFallback.isVisible = true
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
            favoriteAccessibilityActionId?.let {
                ViewCompat.removeAccessibilityAction(binding.root, it)
                favoriteAccessibilityActionId = null
            }
            observer?.let { callback -> observedKey?.let { assets.removeObserver(it, callback) } }
            observer = null
            observedKey = null
            binding.root.tag = null
            binding.emoji.setImageDrawable(null)
            binding.emojiFallback.text = null
            binding.emojiFavorite.isVisible = false
            binding.root.setOnClickListener(null)
            binding.root.setOnLongClickListener(null)
            binding.root.contentDescription = null
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.unbind()
        super.onViewRecycled(holder)
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<EmojiPickerItem>() {
            override fun areItemsTheSame(oldItem: EmojiPickerItem, newItem: EmojiPickerItem): Boolean =
                oldItem.name == newItem.name

            override fun areContentsTheSame(oldItem: EmojiPickerItem, newItem: EmojiPickerItem): Boolean =
                oldItem == newItem
        }
    }
}
