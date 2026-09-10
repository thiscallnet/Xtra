package com.github.andreyasadchy.xtra.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil3.Image
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.target.ImageViewTarget
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentEmojiPickerListItemBinding

internal class EmojiAdapter(
    private val fragment: Fragment,
    private val clickListener: (EmojiPickerItem) -> Unit,
) : RecyclerView.Adapter<EmojiAdapter.ViewHolder>() {
    private val differ = AsyncListDiffer(this, DIFF_CALLBACK)

    fun submitList(items: List<EmojiPickerItem>) = differ.submitList(items.toList())

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
        private var imageRequest: coil3.request.Disposable? = null

        fun bind(item: EmojiPickerItem) {
            imageRequest?.dispose()
            binding.emoji.setImageDrawable(null)
            binding.emojiFallback.text = item.value
            binding.emojiFallback.isVisible = true
            binding.root.contentDescription = fragment.getString(R.string.use_emoji, item.alias)
            binding.root.setOnClickListener { clickListener(item) }
            imageRequest = binding.root.context.imageLoader.enqueue(
                ImageRequest.Builder(binding.root.context)
                    .data(Twemoji.url(item.value))
                    .target(object : ImageViewTarget(binding.emoji) {
                        override fun onSuccess(result: Image) {
                            super.onSuccess(result)
                            binding.emojiFallback.isVisible = false
                        }

                        override fun onError(error: Image?) {
                            binding.emojiFallback.isVisible = true
                        }
                    })
                    .build(),
            )
        }

        fun unbind() {
            imageRequest?.dispose()
            imageRequest = null
            binding.emoji.setImageDrawable(null)
            binding.emojiFallback.text = null
            binding.root.setOnClickListener(null)
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
