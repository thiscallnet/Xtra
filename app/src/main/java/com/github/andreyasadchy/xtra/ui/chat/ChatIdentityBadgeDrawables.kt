package com.github.andreyasadchy.xtra.ui.chat

import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import coil3.Image
import coil3.request.error
import coil3.request.placeholder
import coil3.target.ImageViewTarget
import kotlinx.coroutines.flow.onStart

internal val chatIdentityBadgeDrawableCache = object : LruCache<String, Drawable.ConstantState>(32) {}

internal fun restoreChatIdentityBadgeDrawable(cacheKey: String, imageView: ImageView): Boolean {
    val state = synchronized(chatIdentityBadgeDrawableCache) {
        chatIdentityBadgeDrawableCache.get(cacheKey)
    } ?: return false
    imageView.setImageDrawable(state.newDrawable(imageView.resources))
    return true
}

internal class ChatIdentityBadgeImageTarget(
    imageView: ImageView,
    private val cacheKey: String,
    private val isCurrent: () -> Boolean,
) : ImageViewTarget(imageView) {
    override fun onStart(placeholder: Image?) {
        if (isCurrent() && view.drawable != null) return
        super.onStart(placeholder)
    }

    override fun onSuccess(result: Image) {
        if (!isCurrent()) return
        super.onSuccess(result)
        view.drawable?.constantState?.let { state ->
            synchronized(chatIdentityBadgeDrawableCache) {
                chatIdentityBadgeDrawableCache.put(cacheKey, state)
            }
        }
    }

    override fun onError(error: Image?) {
        // Preserve the cached badge or the neutral trigger icon on image failure.
    }
}
