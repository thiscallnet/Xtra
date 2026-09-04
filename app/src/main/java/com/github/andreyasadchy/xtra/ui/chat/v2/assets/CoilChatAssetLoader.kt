package com.github.andreyasadchy.xtra.ui.chat.v2.assets

import android.content.Context
import android.net.Uri
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
                .memoryCacheKey("xtra:chat-v2:$url")
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
                // Keep the decoded image handle, not a mutable Drawable. Every bound TextView
                // asks Coil for its own Drawable instance, which is required for animated assets.
                ChatImageHandle {
                    result.image.asDrawable(context.resources).mutate()
                }
            }
            is ErrorResult -> throw ChatAssetLoadException(result.throwable.httpStatusCode(), result.throwable)
        }
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
