package com.github.andreyasadchy.xtra.ui.chat

import android.content.Context
import android.util.TypedValue
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.model.chat.Emote
import java.util.LinkedHashMap

/** Shared, bounded image requests for the emote picker and its adapters. */
internal object EmotePickerImageLoader {
    const val INITIAL_PREFETCH_LIMIT = 64
    const val LOOKAHEAD_PREFETCH_LIMIT = 24
    private const val PREFETCH_TRACKER_LIMIT = 512
    private const val PREFETCH_TRACKER_TTL_MS = 15_000L
    private val prefetchedAt = LinkedHashMap<String, Long>(PREFETCH_TRACKER_LIMIT, .75f, true)
    private val prefetchedRequests = LinkedHashMap<String, Disposable>(PREFETCH_TRACKER_LIMIT, .75f, true)

    fun urlFor(item: Emote, quality: String): String? = when (quality) {
        "4" -> item.url4x ?: item.url3x ?: item.url2x ?: item.url1x
        "3" -> item.url3x ?: item.url2x ?: item.url1x
        "2" -> item.url2x ?: item.url1x
        else -> item.url1x
    }

    fun request(context: Context, item: Emote, quality: String): ImageRequest.Builder? {
        val url = urlFor(item, quality) ?: return null
        val sizePx = pickerRequestSizePx(context)
        return ImageRequest.Builder(context)
            .data(url)
            .size(sizePx, sizePx)
            .memoryCacheKey(memoryCacheKey(url, sizePx))
            .diskCacheKey(url)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .apply {
                if (item.thirdParty) {
                    httpHeaders(NetworkHeaders.Builder().apply {
                        add("User-Agent", "Xtra/${BuildConfig.VERSION_NAME}")
                    }.build())
                }
            }
    }

    fun loadInto(context: Context, item: Emote, quality: String, target: android.widget.ImageView) {
        prefetchKey(context, item, quality)?.let(::cancelPrefetch)
        request(context, item, quality)
            ?.target(target)
            ?.build()
            ?.let(context.imageLoader::enqueue)
    }

    fun prefetch(context: Context, items: Iterable<Emote>, quality: String) {
        val appContext = context.applicationContext
        items.asSequence()
            .distinctBy { item -> urlFor(item, quality) }
            .mapNotNull { item ->
                val key = prefetchKey(appContext, item, quality) ?: return@mapNotNull null
                if (!markForPrefetch(key)) return@mapNotNull null
                cancelPrefetch(key)
                request(appContext, item, quality)?.build()
                    ?.also { request ->
                        val disposable = appContext.imageLoader.enqueue(request)
                        synchronized(prefetchedRequests) {
                            prefetchedRequests[key] = disposable
                            while (prefetchedRequests.size > PREFETCH_TRACKER_LIMIT) {
                                prefetchedRequests.remove(prefetchedRequests.entries.first().key)?.dispose()
                            }
                        }
                    }
            }
            .take(INITIAL_PREFETCH_LIMIT)
            .count()
    }

    private fun markForPrefetch(key: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(prefetchedAt) {
            val previous = prefetchedAt[key]
            if (previous != null && now - previous < PREFETCH_TRACKER_TTL_MS) return false
            prefetchedAt[key] = now
            while (prefetchedAt.size > PREFETCH_TRACKER_LIMIT) {
                prefetchedAt.remove(prefetchedAt.entries.first().key)
            }
            return true
        }
    }

    private fun prefetchKey(context: Context, item: Emote, quality: String): String? =
        urlFor(item, quality)?.let { "$it@${pickerRequestSizePx(context)}" }

    private fun cancelPrefetch(key: String) {
        synchronized(prefetchedRequests) {
            prefetchedRequests.remove(key)?.dispose()
        }
    }

    private fun pickerRequestSizePx(context: Context): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        48f,
        context.resources.displayMetrics,
    ).toInt().coerceAtLeast(1)

    private fun memoryCacheKey(url: String, sizePx: Int): String =
        "xtra:emote-picker:$url:${sizePx}x$sizePx"
}
