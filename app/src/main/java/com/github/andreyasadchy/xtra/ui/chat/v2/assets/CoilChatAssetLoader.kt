package com.github.andreyasadchy.xtra.ui.chat.v2.assets

import android.content.Context
import android.net.Uri
import coil3.Image
import coil3.ImageLoader
import coil3.asDrawable
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.network.HttpException
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey

/** One Coil-backed loader for v2 chat assets. */
class CoilChatAssetLoader(
    context: Context,
    private val imageLoader: ImageLoader = context.imageLoader,
) : ChatAssetLoader {
    private val context = context.applicationContext

    override suspend fun load(key: ChatAssetKey): ChatImageHandle? {
        val url = key.value.takeIf(::isHttpUrl) ?: throw ChatAssetLoadException(
            statusCode = 400,
            cause = IllegalArgumentException("Not an image URL"),
        )
        val result = imageLoader.execute(
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
        return when (result) {
            is SuccessResult -> {
                val image = result.image
                if (image.shareable) {
                    // Shareable images are retained by Coil. The repository keeps only this
                    // cache key, and each bound TextView creates its own drawable instance.
                    ChatImageHandle {
                        imageLoader.memoryCache
                            ?.get(MemoryCache.Key(memoryCacheKey(url)))
                            ?.image
                            ?.let(::newIndependentDrawable)
                    }
                } else {
                    // Coil deliberately does not put non-shareable animated DrawableImages in
                    // memory cache. Retain this one decoded handle as the current animated
                    // fallback, matching the old behavior, and clone through ConstantState when
                    // the platform drawable supports it.
                    object : ChatImageHandle {
                        override fun newDrawable() = newIndependentDrawable(image)
                        override fun holdsDecodedImage() = true
                    }
                }
            }
            is ErrorResult -> throw ChatAssetLoadException(result.throwable.httpStatusCode(), result.throwable)
        }
    }

    private fun memoryCacheKey(url: String): String = "xtra:chat-v2:$url"

    private fun newIndependentDrawable(image: Image): android.graphics.drawable.Drawable? {
        val drawable = image.asDrawable(context.resources)
        return drawable.constantState?.newDrawable(context.resources)?.mutate() ?: drawable.mutate()
    }

    private fun isHttpUrl(value: String): Boolean {
        val uri = Uri.parse(value)
        return (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
    }

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
}
