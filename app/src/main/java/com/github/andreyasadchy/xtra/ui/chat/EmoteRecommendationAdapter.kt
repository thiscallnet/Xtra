package com.github.andreyasadchy.xtra.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ItemEmoteRecommendationBinding
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetState
import com.github.andreyasadchy.xtra.ui.chat.v2.recommendations.EmoteRecommendation

class EmoteRecommendationAdapter(
    private val assets: ChatAssetRepository,
    private val clickListener: (EmoteRecommendation) -> Unit,
) : ListAdapter<EmoteRecommendation, EmoteRecommendationAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ItemEmoteRecommendationBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.unbind()
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(
        private val binding: ItemEmoteRecommendationBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var observedKey: com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey? = null
        private var observer: (() -> Unit)? = null

        fun bind(item: EmoteRecommendation) {
            unbind()
            val emoji = item.emoji
            if (emoji != null) {
                binding.image.isVisible = false
                binding.emoji.isVisible = true
                binding.emoji.text = emoji.value
                binding.name.text = emoji.alias
                binding.root.contentDescription = binding.root.context.getString(R.string.use_emoji, emoji.alias)
                binding.root.setOnClickListener { clickListener(item) }
                val key = Twemoji.asset(emoji.value).key
                observedKey = key
                binding.image.tag = key
                val updateImage: () -> Unit = {
                    binding.image.post {
                        if (binding.image.tag != key) return@post
                        when (val state = assets.peek(key)) {
                            is ChatAssetState.Ready -> {
                                val drawable = state.image.newDrawable()
                                if (drawable == null) {
                                    assets.retryIfDrawableUnavailable(key)
                                    binding.image.setImageDrawable(null)
                                    binding.image.isVisible = false
                                    binding.emoji.isVisible = true
                                } else {
                                    binding.image.setImageDrawable(drawable)
                                    binding.image.isVisible = true
                                    binding.emoji.isVisible = false
                                }
                            }
                            else -> {
                                binding.image.setImageDrawable(null)
                                binding.image.isVisible = false
                                binding.emoji.isVisible = true
                            }
                        }
                    }
                }
                observer = updateImage
                assets.observe(key, updateImage)
                updateImage()
                return
            }
            val key = item.emote.asset.key
            observedKey = key
            binding.emoji.isVisible = false
            binding.name.text = item.emote.name
            binding.root.contentDescription = binding.root.context.getString(R.string.use_emote, item.emote.name)
            binding.root.setOnClickListener { clickListener(item) }
            binding.image.setImageDrawable(null)
            binding.image.tag = key
            val updateImage: () -> Unit = {
                binding.image.post {
                    if (binding.image.tag != key) return@post
                    when (val state = assets.peek(key)) {
                        is ChatAssetState.Ready -> {
                            val drawable = state.image.newDrawable()
                            if (drawable == null) {
                                assets.retryIfDrawableUnavailable(key)
                                binding.image.setImageDrawable(null)
                                binding.image.isVisible = false
                            } else {
                                binding.image.setImageDrawable(drawable)
                                binding.image.isVisible = true
                            }
                        }
                        else -> {
                            binding.image.setImageDrawable(null)
                            binding.image.isVisible = false
                        }
                    }
                }
            }
            observer = updateImage
            assets.observe(key, updateImage)
            updateImage()
        }

        fun unbind() {
            observer?.let { callback -> observedKey?.let { assets.removeObserver(it, callback) } }
            observer = null
            observedKey = null
            binding.image.tag = null
            binding.image.setImageDrawable(null)
            binding.image.isVisible = false
            binding.emoji.isVisible = false
            binding.emoji.text = null
            binding.root.setOnClickListener(null)
        }
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<EmoteRecommendation>() {
            override fun areItemsTheSame(oldItem: EmoteRecommendation, newItem: EmoteRecommendation): Boolean =
                if (oldItem.emoji != null || newItem.emoji != null) {
                    oldItem.emoji?.alias == newItem.emoji?.alias
                } else {
                    oldItem.emote.provider == newItem.emote.provider &&
                            oldItem.emote.id == newItem.emote.id &&
                            oldItem.emote.scope == newItem.emote.scope
                }

            override fun areContentsTheSame(oldItem: EmoteRecommendation, newItem: EmoteRecommendation): Boolean =
                oldItem == newItem
        }
    }
}
