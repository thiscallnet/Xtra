package com.github.andreyasadchy.xtra.ui.chat.v2.assets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.LruCache
import coil3.Image
import coil3.ImageLoader
import coil3.asDrawable
import coil3.imageLoader
import coil3.network.HttpException
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.ui.chat.Twemoji
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetDimensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** One Coil-backed loader for v2 chat assets. */
class CoilChatAssetLoader(
    context: Context,
    private val imageLoader: ImageLoader = context.imageLoader,
) : ChatAssetLoader {
    private val context = context.applicationContext
    private val bundledBitmaps = LruCache<String, Bitmap>(BUNDLED_CACHE_ENTRIES)
    private val bundledDispatcher = Dispatchers.IO.limitedParallelism(BUNDLED_DECODE_THREADS)

    override suspend fun load(key: ChatAssetKey): ChatImageHandle? {
        val url = key.value.takeIf { isHttpUrl(it) || isBundledAsset(it) } ?: throw ChatAssetLoadException(
            statusCode = 400,
            cause = IllegalArgumentException("Not an image URL"),
        )
        if (isBundledAsset(url)) {
            loadBundled(url)?.let { return it }
        }
        var result = execute(url)
        if (result is ErrorResult) {
            Twemoji.cdnFallbackUrl(url)?.let { result = execute(it) }
        }
        return when (val result = result) {
            is SuccessResult -> {
                val image = result.image
                val dimensions = image.asDrawable(context.resources).let { drawable ->
                    val width = drawable.intrinsicWidth
                    val height = drawable.intrinsicHeight
                    if (width > 0 && height > 0) ChatAssetDimensions(width, height) else null
                }
                if (image.shareable) {
                    // A Ready repository entry can outlive Coil's memory-cache entry, especially
                    // while a large emote-spam row is being laid out. Keep the decoded Image as
                    // the handle's fallback instead of turning a cache eviction into a blank
                    // ReplacementSpan. Each bound TextView still creates its own drawable.
                    object : ChatImageHandle {
                        override fun newDrawable() = newIndependentDrawable(image)

                        override fun intrinsicDimensions() = dimensions
                        override fun holdsDecodedImage() = true
                    }
                } else {
                    // Coil deliberately does not put non-shareable animated DrawableImages in
                    // memory cache. Retain this one decoded handle as the current animated
                    // fallback, matching the old behavior, and clone through ConstantState when
                    // the platform drawable supports it.
                    object : ChatImageHandle {
                        override fun newDrawable() = newIndependentDrawable(image)
                        override fun intrinsicDimensions() = dimensions
                        override fun holdsDecodedImage() = true
                    }
                }
            }
            is ErrorResult -> throw ChatAssetLoadException(result.throwable.httpStatusCode(), result.throwable)
        }
    }

    /**
     * Bundled emoji skip Coil on purpose: its fetch pool is shared with network requests, so a
     * burst of slow or failing emote downloads would stall artwork that is already on disk.
     */
    private suspend fun loadBundled(url: String): ChatImageHandle? {
        val bitmap = bundledBitmaps.get(url) ?: withContext(bundledDispatcher) {
            try {
                context.assets.open(url.removePrefix(ASSET_URI_PREFIX)).use(BitmapFactory::decodeStream)
            } catch (_: IOException) {
                null
            }
        }?.also { bundledBitmaps.put(url, it) } ?: return null
        val dimensions = ChatAssetDimensions(bitmap.width, bitmap.height)
        return object : ChatImageHandle {
            override fun newDrawable() = BitmapDrawable(context.resources, bitmap)
            override fun intrinsicDimensions() = dimensions
            override fun holdsDecodedImage() = true
        }
    }

    private suspend fun execute(url: String) = imageLoader.execute(
        ImageRequest.Builder(context)
            .data(url)
            .memoryCacheKey(memoryCacheKey(url))
            .diskCacheKey(url)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .apply {
                if (isThirdPartyUrl(url)) {
                    httpHeaders(NetworkHeaders.Builder()
                        .add("User-Agent", "Xtra/${BuildConfig.VERSION_NAME}")
                        .build())
                }
            }
            .build(),
    )

    private fun memoryCacheKey(url: String): String = "xtra:chat-v2:$url"

    private fun newIndependentDrawable(image: Image): android.graphics.drawable.Drawable? {
        val drawable = image.asDrawable(context.resources)
        return drawable.constantState?.newDrawable(context.resources)?.mutate() ?: drawable.mutate()
    }

    private fun isHttpUrl(value: String): Boolean {
        val uri = Uri.parse(value)
        return (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
    }

    private fun isBundledAsset(value: String): Boolean = value.startsWith(ASSET_URI_PREFIX)

    private fun isThirdPartyUrl(value: String): Boolean {
        val host = Uri.parse(value).host?.lowercase() ?: return false
        return host == "7tv.app" || host.endsWith(".7tv.app") ||
            host == "betterttv.net" || host.endsWith(".betterttv.net") ||
            host == "frankerfacez.com" || host.endsWith(".frankerfacez.com")
    }

    private fun Throwable.httpStatusCode(): Int? {
        var current: Throwable? = this
        repeat(8) {
            val value = current ?: return null
            if (value is HttpException) return value.response.code
            current = value.cause
        }
        return null
    }

    private companion object {
        const val ASSET_URI_PREFIX = "file:///android_asset/"
        const val BUNDLED_CACHE_ENTRIES = 384
        const val BUNDLED_DECODE_THREADS = 3
    }
}
