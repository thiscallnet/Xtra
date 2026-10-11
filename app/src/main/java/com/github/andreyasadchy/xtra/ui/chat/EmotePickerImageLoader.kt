package com.github.andreyasadchy.xtra.ui.chat

import android.content.Context
import android.net.ConnectivityManager
import android.util.TypedValue
import coil3.decode.BitmapFactoryDecoder
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.ImageRequest.Listener
import coil3.request.SuccessResult
import coil3.request.ErrorResult
import coil3.request.crossfade
import coil3.request.target
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import java.util.LinkedHashMap
import kotlin.math.roundToInt

/** Shared, bounded image requests for the emote picker and its adapters. */
internal object EmotePickerImageLoader {
    const val INITIAL_PREFETCH_LIMIT = 64
    const val LOOKAHEAD_PREFETCH_LIMIT = 24
    private const val PREFETCH_TRACKER_LIMIT = 512
    /** Prefetches on the wire at once; the rest of the connections stay free for visible cells. */
    private const val PREFETCH_CONCURRENCY = 4
    private const val PREFETCH_PENDING_LIMIT = 48
    /** Background warming only ever fills idle connections and is capped so it cannot flood the disk cache. */
    private const val WARM_PENDING_LIMIT = 320
    private const val WARM_CONCURRENCY = 2
    private val RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L)
    private const val PREFETCH_TRACKER_TTL_MS = 15_000L
    private val prefetchedAt = LinkedHashMap<String, Long>(PREFETCH_TRACKER_LIMIT, .75f, true)
    private val lock = Any()
    private val pending = LinkedHashMap<String, ImageRequest>()
    private val warming = LinkedHashMap<String, ImageRequest>()
    private val running = HashMap<String, Disposable?>()

    /**
     * Picker cells are 48dp, so the user's chat quality (often 4x animated GIFs) is far more
     * than needed. Downloading and decoding those is what makes a fast scroll crawl.
     */
    fun urlFor(context: Context, item: Emote, quality: String): String? =
        urlFor(item, minOf(quality.toIntOrNull() ?: 1, pickerQualityCap(context)).toString())
            ?: urlFor(item, quality)

    private fun pickerQualityCap(context: Context): Int =
        (pickerRequestSizePx(context) / 42f).roundToInt().coerceIn(1, 4)

    private fun urlFor(item: Emote, quality: String): String? = when (quality) {
        "4" -> item.url4x ?: item.url3x ?: item.url2x ?: item.url1x
        "3" -> item.url3x ?: item.url2x ?: item.url1x
        "2" -> item.url2x ?: item.url1x
        else -> item.url1x
    }

    private fun animated(context: Context): Boolean =
        context.prefs().getBoolean(C.CHAT_ANIMATE_PICKER_EMOTES, true)

    fun request(context: Context, item: Emote, quality: String): ImageRequest.Builder? {
        val url = urlFor(context, item, quality) ?: return null
        val sizePx = pickerRequestSizePx(context)
        val animated = animated(context)
        return ImageRequest.Builder(context)
            .data(url)
            .size(sizePx, sizePx)
            // A still picker decodes only the first frame, which is much cheaper than animating.
            .apply { if (!animated) decoderFactory(BitmapFactoryDecoder.Factory()) }
            .memoryCacheKey(memoryCacheKey(url, sizePx, animated))
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

    fun loadInto(context: Context, item: Emote, quality: String, target: android.widget.ImageView) =
        loadInto(context, item, quality, target, attempt = 0)

    private fun loadInto(context: Context, item: Emote, quality: String, target: android.widget.ImageView, attempt: Int) {
        val url = urlFor(context, item, quality) ?: return
        prefetchKey(context, item, quality)?.let(::cancelPrefetch)
        request(context, item, quality)
            ?.target(target)
            ?.listener(object : Listener {
                override fun onError(request: ImageRequest, result: ErrorResult) {
                    if (attempt >= RETRY_DELAYS_MS.size) return
                    // Bad network: try again shortly, but only if this cell still wants this emote.
                    target.postDelayed({
                        if (target.isAttachedToWindow && target.drawable == null && target.getTag(R.id.emote_image_url_key) == url) {
                            loadInto(context, item, quality, target, attempt + 1)
                        }
                    }, RETRY_DELAYS_MS[attempt])
                }
            })
            ?.build()
            ?.let(context.imageLoader::enqueue)
    }

    /**
     * Prefetches go through a small queue instead of straight to the network so they can never
     * occupy every connection a visible cell needs. The newest batch (nearest the scroll position)
     * runs first, and entries the user has scrolled away from fall off the back.
     */
    fun prefetch(context: Context, items: Iterable<Emote>, quality: String) {
        val appContext = context.applicationContext
        val batch = prefetchBatch(appContext, items, quality, PREFETCH_PENDING_LIMIT)
        if (batch.isEmpty()) return
        val dropped = ArrayList<String>()
        synchronized(lock) {
            val older = LinkedHashMap(pending)
            pending.clear()
            batch.forEach { (key, request) ->
                warming.remove(key)
                pending[key] = request
            }
            older.forEach { (key, request) -> if (!pending.containsKey(key)) pending[key] = request }
            while (pending.size > PREFETCH_PENDING_LIMIT) {
                val last = pending.keys.last()
                pending.remove(last)
                dropped += last
            }
        }
        dropped.forEach(::forgetPrefetch)
        pump(appContext)
    }

    /**
     * Quietly fills the disk cache with a channel's picker emotes so the picker opens already
     * populated. Runs only on unmetered networks and only on connections nothing else wants.
     */
    fun warm(context: Context, items: Iterable<Emote>, quality: String) {
        val appContext = context.applicationContext
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (connectivity == null || connectivity.isActiveNetworkMetered) return
        val batch = prefetchBatch(appContext, items, quality, WARM_PENDING_LIMIT)
        if (batch.isEmpty()) return
        val dropped = ArrayList<String>()
        synchronized(lock) {
            batch.forEach { (key, request) ->
                if (!pending.containsKey(key) && !running.containsKey(key)) warming[key] = request
            }
            while (warming.size > WARM_PENDING_LIMIT) {
                val first = warming.keys.first()
                warming.remove(first)
                dropped += first
            }
        }
        dropped.forEach(::forgetPrefetch)
        pump(appContext)
    }

    private fun prefetchBatch(
        appContext: Context,
        items: Iterable<Emote>,
        quality: String,
        limit: Int,
    ): List<Pair<String, ImageRequest>> {
        return items.asSequence()
            .distinctBy { item -> urlFor(appContext, item, quality) }
            .mapNotNull { item ->
                val key = prefetchKey(appContext, item, quality) ?: return@mapNotNull null
                if (!markForPrefetch(key)) return@mapNotNull null
                val request = request(appContext, item, quality)
                    ?.listener(object : Listener {
                        override fun onCancel(request: ImageRequest) = finishPrefetch(appContext, key, retry = true)
                        override fun onError(request: ImageRequest, result: ErrorResult) = finishPrefetch(appContext, key, retry = true)
                        override fun onSuccess(request: ImageRequest, result: SuccessResult) = finishPrefetch(appContext, key, retry = false)
                    })
                    ?.build() ?: return@mapNotNull null
                key to request
            }
            .take(limit)
            .toList()
    }

    private fun pump(context: Context) {
        while (true) {
            val next = synchronized(lock) {
                // Visible-adjacent prefetches go first; warming only takes what is left over.
                val queue = if (pending.isNotEmpty()) pending else warming
                val limit = if (queue === pending) PREFETCH_CONCURRENCY else WARM_CONCURRENCY
                if (running.size >= limit) return
                val entry = queue.entries.firstOrNull() ?: return
                queue.remove(entry.key)
                running[entry.key] = null
                entry.key to entry.value
            }
            val disposable = context.imageLoader.enqueue(next.second)
            synchronized(lock) {
                // A memory-cache hit may already have finished the request.
                if (running.containsKey(next.first)) running[next.first] = disposable
            }
        }
    }

    private fun finishPrefetch(context: Context, key: String, retry: Boolean) {
        synchronized(lock) { running.remove(key) }
        if (retry) forgetPrefetch(key)
        pump(context)
    }

    /** A dropped prefetch never loaded, so it must be allowed to come back when scrolled to. */
    private fun forgetPrefetch(key: String) {
        synchronized(prefetchedAt) { prefetchedAt.remove(key) }
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
        urlFor(context, item, quality)?.let { "$it@${pickerRequestSizePx(context)}${if (animated(context)) "" else ":still"}" }

    private fun cancelPrefetch(key: String) {
        val wasQueued: Boolean
        val disposable: Disposable?
        synchronized(lock) {
            wasQueued = (pending.remove(key) ?: warming.remove(key)) != null
            disposable = running.remove(key)
        }
        if (wasQueued || disposable != null) forgetPrefetch(key)
        disposable?.dispose()
    }

    private fun pickerRequestSizePx(context: Context): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        48f,
        context.resources.displayMetrics,
    ).toInt().coerceAtLeast(1)

    private fun memoryCacheKey(url: String, sizePx: Int, animated: Boolean): String =
        "xtra:emote-picker:$url:${sizePx}x$sizePx" + if (animated) "" else ":still"
}
