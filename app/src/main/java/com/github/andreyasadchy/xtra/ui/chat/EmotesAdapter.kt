package com.github.andreyasadchy.xtra.ui.chat

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityViewCommand
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
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

class EmotesAdapter(
    private val fragment: Fragment,
    private val clickListener: (Emote) -> Unit,
    private val emoteQuality: String,
    private val imageLibrary: String?,
    private val favoriteToggleListener: ((Emote) -> Unit)? = null,
    private val consumeLongPress: Boolean = false,
) : ListAdapter<Emote, EmotesAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<Emote>() {
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
) {

    private var favoriteKeys: Set<FavoriteEmoteKey> = emptySet()

    fun setFavoriteKeys(keys: Set<FavoriteEmoteKey>) {
        if (favoriteKeys != keys) {
            favoriteKeys = keys
            if (itemCount > 0) {
                notifyItemRangeChanged(0, itemCount)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = FragmentEmotesListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: FragmentEmotesListItemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var favoriteAccessibilityActionId: Int? = null

        fun bind(item: Emote?) {
            with(binding) {
                favoriteAccessibilityActionId?.let {
                    ViewCompat.removeAccessibilityAction(root, it)
                    favoriteAccessibilityActionId = null
                }
                root.setOnClickListener(null)
                root.setOnLongClickListener(null)
                if (item != null) {
                    root.contentDescription = fragment.getString(R.string.use_emote, item.name)
                    root.isFocusable = true
                    if (imageLibrary == "0" || (imageLibrary == "1" && !item.format.equals("webp", true))) {
                        fragment.requireContext().imageLoader.enqueue(
                            ImageRequest.Builder(fragment.requireContext()).apply {
                                data(
                                    when (emoteQuality) {
                                        "4" -> item.url4x ?: item.url3x ?: item.url2x ?: item.url1x
                                        "3" -> item.url3x ?: item.url2x ?: item.url1x
                                        "2" -> item.url2x ?: item.url1x
                                        else -> item.url1x
                                    }
                                )
                                if (item.thirdParty) {
                                    httpHeaders(NetworkHeaders.Builder().apply {
                                        add("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                                    }.build())
                                }
                                crossfade(true)
                                target(root)
                            }.build()
                        )
                    } else {
                        Glide.with(fragment)
                            .load(
                                when (emoteQuality) {
                                    "4" -> item.url4x ?: item.url3x ?: item.url2x ?: item.url1x
                                    "3" -> item.url3x ?: item.url2x ?: item.url1x
                                    "2" -> item.url2x ?: item.url1x
                                    else -> item.url1x
                                }.let {
                                    if (item.thirdParty) {
                                        GlideUrl(it) { mapOf("User-Agent" to "Xtra/" + BuildConfig.VERSION_NAME) }
                                    } else it
                                }
                            )
                            .diskCacheStrategy(DiskCacheStrategy.DATA)
                            .transition(DrawableTransitionOptions.withCrossFade())
                            .into(root)
                    }
                    root.setOnClickListener { clickListener(item) }
                    val key = item.favoriteKey()
                    if (favoriteToggleListener != null && key != null) {
                        val isFavorite = key in favoriteKeys
                        root.setOnLongClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            favoriteToggleListener.invoke(item)
                            true
                        }
                        favoriteAccessibilityActionId = ViewCompat.addAccessibilityAction(
                            root,
                            fragment.getString(
                                if (isFavorite) R.string.remove_emote_from_favorites else R.string.add_emote_to_favorites,
                            ),
                            AccessibilityViewCommand { _, _ ->
                                favoriteToggleListener.invoke(item)
                                true
                            },
                        )
                    } else if (consumeLongPress) {
                        root.setOnLongClickListener { true }
                    }
                }
            }
        }
    }
}
