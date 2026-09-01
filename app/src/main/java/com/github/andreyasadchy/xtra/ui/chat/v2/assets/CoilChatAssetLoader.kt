package com.github.andreyasadchy.xtra.ui.chat.v2.assets

import android.content.Context
import coil3.ImageLoader
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey

/** One Coil-backed loader for v2 chat assets. */
class CoilChatAssetLoader(
    context: Context,
    private val imageLoader: ImageLoader = context.imageLoader,
) : ChatAssetLoader {
    private val context = context.applicationContext

    override suspend fun load(key: ChatAssetKey): ChatImageHandle? {
        val result = imageLoader.execute(
            ImageRequest.Builder(context)
                .data(key.value)
                .crossfade(false)
                .build(),
        ) as? SuccessResult ?: return null
        // Keep the decoded image handle, not a mutable Drawable. Every bound TextView asks
        // Coil for its own Drawable instance, which is required for animated assets.
        return ChatImageHandle {
            result.image.asDrawable(context.resources).mutate()
        }
    }
}
