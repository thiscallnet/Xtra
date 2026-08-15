package com.github.andreyasadchy.xtra.ui.common

import android.content.Context
import android.widget.ImageView
import coil3.Image
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.request.error
import coil3.request.fallback
import coil3.request.placeholder
import coil3.request.target
import coil3.request.transformations
import coil3.target.ImageViewTarget
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

internal fun ImageRequest.Builder.thumbnailState(): ImageRequest.Builder = apply {
    placeholder(R.drawable.bg_thumbnail_placeholder)
    error(R.drawable.ic_thumbnail_error)
    fallback(R.drawable.ic_thumbnail_error)
}

internal fun Stream.streamIdentity(): String {
    return channelId?.takeIf { it.isNotBlank() }?.let { "channel:$it" }
        ?: id?.takeIf { it.isNotBlank() }?.let { "stream:$it" }
        ?: channelLogin?.takeIf { it.isNotBlank() }?.let { "login:${it.lowercase()}" }
        ?: "unknown:${channelName.orEmpty()}"
}

internal fun streamContentsSame(oldItem: Stream, newItem: Stream): Boolean {
    return oldItem.id == newItem.id &&
            oldItem.channelId == newItem.channelId &&
            oldItem.channelLogin == newItem.channelLogin &&
            oldItem.channelName == newItem.channelName &&
            oldItem.channelImageURL == newItem.channelImageURL &&
            oldItem.gameId == newItem.gameId &&
            oldItem.gameSlug == newItem.gameSlug &&
            oldItem.gameName == newItem.gameName &&
            oldItem.title == newItem.title &&
            oldItem.thumbnailURL == newItem.thumbnailURL &&
            oldItem.createdAt == newItem.createdAt &&
            oldItem.viewerCount == newItem.viewerCount &&
            oldItem.tags == newItem.tags
}

/**
 * Coil normally sends a null onStart value to an ImageViewTarget when a new
 * request has no placeholder. Keep the existing successful image in that
 * case, and guard every callback against a recycled ViewHolder identity.
 */
private class StreamImageTarget(
    imageView: ImageView,
    private val identity: String,
    private val preserveCurrentImage: Boolean,
) : ImageViewTarget(imageView) {

    override fun onStart(placeholder: Image?) {
        if (preserveCurrentImage && view.tag == identity && view.drawable != null) return
        super.onStart(placeholder)
    }

    override fun onSuccess(result: Image) {
        if (view.tag == identity) {
            super.onSuccess(result)
        }
    }

    override fun onError(error: Image?) {
        if (view.tag != identity) return
        if (preserveCurrentImage && view.drawable != null) return
        super.onError(error)
    }
}

private class StreamThumbnailCacheTarget(
    imageView: ImageView,
    private val identity: String,
    private val onCacheSettled: () -> Unit,
) : ImageViewTarget(imageView) {

    private var settled = false

    override fun onStart(placeholder: Image?) {
        // The cache-only stage is intentionally silent. It must never replace
        // a valid image with a template while it looks for the stale disk row.
    }

    override fun onSuccess(result: Image) {
        if (view.tag == identity) {
            super.onSuccess(result)
            settle()
        }
    }

    override fun onError(error: Image?) {
        if (view.tag == identity) {
            // Preserve whatever was already displayed, including a previous
            // cached image, and continue to the network stage.
            settle()
        }
    }

    private fun settle() {
        if (!settled) {
            settled = true
            onCacheSettled()
        }
    }
}

internal data class StreamThumbnailRequestPlan(
    val diskCacheKey: String,
    val networkUrl: String,
)

internal fun streamThumbnailRequestPlan(stream: Stream, bucket: Long): StreamThumbnailRequestPlan? {
    if (stream.thumbnailURL.isNullOrBlank()) return null
    val thumbnail = stream.thumbnail ?: return null
    val identity = stream.streamIdentity()
    val separator = if ('?' in thumbnail) '&' else '?'
    return StreamThumbnailRequestPlan(
        diskCacheKey = "xtra:stream-thumbnail:$identity",
        networkUrl = "$thumbnail${separator}xtra_preview_bucket=$bucket",
    )
}

internal fun loadStreamProfileImage(context: Context, imageView: ImageView, stream: Stream) {
    val identity = stream.streamIdentity()
    val sameIdentity = imageView.tag == identity
    if (!sameIdentity) {
        imageView.setImageDrawable(null)
    }
    imageView.tag = identity
    val url = stream.channelImage
    if (url.isNullOrBlank()) {
        imageView.setImageDrawable(null)
        return
    }
    ImageRequest.Builder(context).apply {
        data(url)
        diskCachePolicy(CachePolicy.ENABLED)
        if (context.prefs().getBoolean(C.UI_ROUND_USER_IMAGE, true)) {
            transformations(CircleCropTransformation())
        }
        // Keep an already displayed image in place while a changed profile URL loads.
        crossfade(false)
        target(StreamImageTarget(imageView, identity, preserveCurrentImage = sameIdentity))
    }.build().let(context.imageLoader::enqueue)
}

internal fun loadStreamThumbnail(context: Context, imageView: ImageView, stream: Stream) {
    val identity = stream.streamIdentity()
    if (imageView.tag != identity) {
        imageView.setImageDrawable(null)
    }
    imageView.tag = identity
    val bucket = System.currentTimeMillis() / (5 * 60_000L)
    val plan = streamThumbnailRequestPlan(stream, bucket)
    if (plan == null) {
        imageView.setImageDrawable(null)
        return
    }

    fun enqueueFreshRequest() {
        val preserveCurrentImage = imageView.tag == identity && imageView.drawable != null
        ImageRequest.Builder(context).apply {
            data(plan.networkUrl)
            // WRITE_ONLY deliberately bypasses the stable stale image while
            // retaining the new response under that same stable disk key.
            diskCachePolicy(CachePolicy.WRITE_ONLY)
            diskCacheKey(plan.diskCacheKey)
            networkCachePolicy(CachePolicy.ENABLED)
            crossfade(false)
            if (!preserveCurrentImage) {
                thumbnailState()
            }
            target(StreamImageTarget(imageView, identity, preserveCurrentImage))
        }.build().let(context.imageLoader::enqueue)
    }

    // Stage one renders the stable last-known image without touching the
    // network. Stage two then revalidates the preview and atomically replaces
    // the displayed image and the same stable disk entry if successful.
    ImageRequest.Builder(context).apply {
        data(plan.networkUrl)
        diskCachePolicy(CachePolicy.READ_ONLY)
        diskCacheKey(plan.diskCacheKey)
        networkCachePolicy(CachePolicy.DISABLED)
        target(StreamThumbnailCacheTarget(imageView, identity, ::enqueueFreshRequest))
    }.build().let(context.imageLoader::enqueue)
}
