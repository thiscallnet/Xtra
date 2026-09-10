package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.ImageView
import android.widget.TextView
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.Chatter
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.ui.chat.EmojiPickerItem
import com.github.andreyasadchy.xtra.ui.chat.Twemoji
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetState
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.regex.Pattern

class AutoCompleteAdapter<T>(
    context: Context,
    resource: Int,
    textViewResourceId: Int,
    private val originalValues: MutableList<T?>,
    private val chatAssets: ChatAssetRepository,
): ArrayAdapter<T?>(context, resource, textViewResourceId) {

    private var objects: List<T?> = originalValues
    private val emojiSubscriptions = LinkedHashSet<EmojiAssetSubscription>()
    private val activeImageViews = Collections.newSetFromMap(WeakHashMap<ImageView, Boolean>())
    private val imageLibrary = "0"
    private val emoteQuality = "4"

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        val item = getItem(position)
        view.findViewById<ImageView>(R.id.image)?.let {
            activeImageViews += it
            clearImageRequest(it)
        }
        when (item) {
            is Emote -> {
                view.findViewById<TextView>(R.id.emoji)?.visibility = View.GONE
                view.findViewById<ImageView>(R.id.image)?.let {
                    clearEmojiSubscription(it)
                    it.visibility = View.VISIBLE
                    // Dropdown rows are recycled; clear the previous emote before
                    // the async load completes so a stale image never flashes.
                    it.setImageDrawable(null)
                    if (imageLibrary == "0" || (imageLibrary == "1" && !item.format.equals("webp", true))) {
                        val request = context.imageLoader.enqueue(
                            ImageRequest.Builder(context).apply {
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
                                target(it)
                            }.build()
                        )
                        it.setTag(R.id.autocomplete_image_request, request)
                    } else {
                        Glide.with(context)
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
                            .into(it)
                    }
                }
                view.findViewById<TextView>(R.id.name)?.text = item.name
            }
            is Chatter -> {
                view.findViewById<TextView>(R.id.emoji)?.visibility = View.GONE
                // A recycled emote row keeps its image; chatter rows have none.
                view.findViewById<ImageView>(R.id.image)?.let {
                    clearEmojiSubscription(it)
                    it.visibility = View.GONE
                    it.setImageDrawable(null)
                }
                view.findViewById<TextView>(R.id.name)?.text = item.name
            }
            is EmojiPickerItem -> {
                view.findViewById<ImageView>(R.id.image)?.let {
                    clearEmojiSubscription(it)
                    val fallback = view.findViewById<TextView>(R.id.emoji)
                    val spec = Twemoji.asset(item.value)
                    it.visibility = View.VISIBLE
                    it.setImageDrawable(null)
                    fallback?.apply {
                        visibility = View.VISIBLE
                        text = item.value
                    }
                    lateinit var subscription: EmojiAssetSubscription
                    val updateImage: () -> Unit = {
                        it.post {
                            if (it.tag !== subscription) return@post
                            when (val state = chatAssets.peek(spec.key)) {
                                is ChatAssetState.Ready -> {
                                    val drawable = state.image.newDrawable()
                                    if (drawable == null) {
                                        chatAssets.retryIfDrawableUnavailable(spec.key)
                                        it.visibility = View.GONE
                                        fallback?.visibility = View.VISIBLE
                                    } else {
                                        it.setImageDrawable(drawable)
                                        it.visibility = View.VISIBLE
                                        fallback?.visibility = View.GONE
                                    }
                                }
                                else -> {
                                    it.visibility = View.GONE
                                    fallback?.visibility = View.VISIBLE
                                }
                            }
                        }
                    }
                    subscription = EmojiAssetSubscription(chatAssets, spec.key, updateImage)
                    emojiSubscriptions += subscription
                    it.tag = subscription
                    chatAssets.observe(spec.key, updateImage)
                    updateImage()
                }
                view.findViewById<TextView>(R.id.name)?.text = item.alias
            }
        }
        return view
    }

    override fun getFilter(): Filter = filter

    fun dispose() {
        activeImageViews.toList().forEach(::clearImageRequest)
        activeImageViews.clear()
        emojiSubscriptions.toList().forEach { subscription ->
            subscription.repository.removeObserver(subscription.key, subscription.callback)
        }
        emojiSubscriptions.clear()
    }

    private val filter: Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults? {
            return if (constraint.isNullOrBlank()) {
                FilterResults()
            } else {
                val list = synchronized(originalValues) { originalValues.toList() }
                val regex = constraint.map {
                    "${Pattern.quote(it.lowercase(Locale.ROOT))}\\S*?"
                }.joinToString("").toRegex()
                val results = list.mapNotNull { item ->
                    when (item) {
                        is EmojiPickerItem -> matchingEmojiAlias(item, regex)?.let { alias ->
                            @Suppress("UNCHECKED_CAST")
                            item.copy(matchedAlias = alias) as T?
                        }
                        null -> null
                        else -> item.takeIf { regex.matches(it.toString().lowercase(Locale.ROOT)) }
                    }
                }
                FilterResults().apply {
                    values = results
                    count = results.size
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            objects = (results?.values as? List<T?>).orEmpty()
            if (results != null && results.count > 0) {
                notifyDataSetChanged()
            } else {
                notifyDataSetInvalidated()
            }
        }
    }

    override fun getCount(): Int = objects.size

    override fun getItem(position: Int): T? = objects[position]

    private fun matchingEmojiAlias(item: EmojiPickerItem, regex: Regex): String? =
        item.aliases.firstOrNull { alias -> regex.matches(":$alias:".lowercase(Locale.ROOT)) }

    private fun clearEmojiSubscription(image: ImageView) {
        (image.tag as? EmojiAssetSubscription)?.let { subscription ->
            subscription.repository.removeObserver(subscription.key, subscription.callback)
            emojiSubscriptions.remove(subscription)
        }
        image.tag = null
    }

    private fun clearImageRequest(image: ImageView) {
        (image.getTag(R.id.autocomplete_image_request) as? Disposable)?.dispose()
        image.setTag(R.id.autocomplete_image_request, null)
        Glide.with(image.context).clear(image)
    }

    private data class EmojiAssetSubscription(
        val repository: ChatAssetRepository,
        val key: ChatAssetKey,
        val callback: () -> Unit,
    )
}
