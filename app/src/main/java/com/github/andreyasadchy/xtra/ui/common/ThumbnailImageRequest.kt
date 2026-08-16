package com.github.andreyasadchy.xtra.ui.common

import android.content.Context
import android.util.Log
import android.widget.ImageView
import coil3.Image
import coil3.decode.DataSource
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.error
import coil3.request.fallback
import coil3.request.placeholder
import coil3.request.target
import coil3.request.transformations
import coil3.target.ImageViewTarget
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamThumbnailRefreshSignal
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import java.util.concurrent.ConcurrentHashMap

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

/**
 * Identity for image bytes, which is intentionally different from the stable
 * channel identity used by RecyclerView rows.
 */
internal fun Stream.thumbnailIdentity(): String {
    val created = createdAt?.trim()?.takeIf { it.isNotEmpty() }
    return id?.trim()?.takeIf { it.isNotEmpty() }?.let { "stream:$it" }
        ?: channelId?.trim()?.takeIf { it.isNotEmpty() }?.let { channel ->
            created?.let { "channel:$channel:created:$it" }
        }
        ?: channelLogin?.trim()?.takeIf { it.isNotEmpty() }?.let { login ->
            created?.let { "login:${login.lowercase()}:created:$it" }
        }
        ?: "fallback:${streamIdentity()}:thumbnail-hash:${thumbnailURL.orEmpty().hashCode().toString(16)}"
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
            oldItem.tags == newItem.tags &&
            oldItem.thumbnailGeneration == newItem.thumbnailGeneration
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
        if (shouldPreserveThumbnailOnFreshFailure(
                identityMatches = true,
                preserveCurrentImage = preserveCurrentImage,
                hasDisplayedImage = view.drawable != null,
            )
        ) return
        super.onError(error)
    }
}

private class StreamThumbnailCacheTarget(
    imageView: ImageView,
    private val identity: String,
) : ImageViewTarget(imageView) {

    override fun onStart(placeholder: Image?) {
        // The cache-only stage is intentionally silent. It must never replace
        // a valid image with a template while it looks for the stale disk row.
    }

    override fun onSuccess(result: Image) {
        if (view.tag == identity) {
            super.onSuccess(result)
        }
    }

    override fun onError(error: Image?) {
        if (view.tag == identity) {
            // Preserve whatever was already displayed, including a previous
            // cached image. The request listener starts the fresh stage after
            // this target callback has completed.
        }
    }
}

internal data class StreamThumbnailRequestPlan(
    val diskCacheKey: String,
    val memoryCacheKey: String,
    val networkUrl: String,
)

internal object StreamThumbnailPolicy {
    const val REFRESH_INTERVAL_MS = 5 * 60_000L
    const val FRESH_RETRY_INTERVAL_MS = 30_000L

    fun bucket(nowMs: Long): Long = nowMs / REFRESH_INTERVAL_MS
}

internal class StreamThumbnailFetchGate(
    private val maxTrackedSessions: Int = 512,
    private val retryIntervalMs: Long = StreamThumbnailPolicy.FRESH_RETRY_INTERVAL_MS,
) {
    private data class State(
        var successfulBucket: Long? = null,
        var successfulForceEpoch: Long = 0L,
        var attemptedBucket: Long? = null,
        var attemptedForceEpoch: Long = 0L,
        var attemptedAtMs: Long = 0L,
    )

    private val states = ConcurrentHashMap<String, State>()

    @Synchronized
    fun forcePending(identity: String, bucket: Long, forceEpoch: Long): Boolean {
        if (forceEpoch == 0L) return false
        val state = states[identity] ?: return true
        return state.successfulBucket != bucket || state.successfulForceEpoch < forceEpoch
    }

    @Synchronized
    fun shouldFetch(identity: String, bucket: Long, forceEpoch: Long, nowMs: Long): Boolean {
        val state = state(identity)
        if (state.successfulBucket == bucket && state.successfulForceEpoch >= forceEpoch) {
            return false
        }
        return state.attemptedBucket != bucket ||
                state.attemptedForceEpoch != forceEpoch ||
                nowMs - state.attemptedAtMs >= retryIntervalMs
    }

    @Synchronized
    fun markAttempt(identity: String, bucket: Long, forceEpoch: Long, nowMs: Long) {
        state(identity).apply {
            attemptedBucket = bucket
            attemptedForceEpoch = forceEpoch
            attemptedAtMs = nowMs
        }
    }

    @Synchronized
    fun markSuccess(identity: String, bucket: Long, forceEpoch: Long) {
        state(identity).apply {
            successfulBucket = bucket
            successfulForceEpoch = forceEpoch
        }
    }

    private fun state(identity: String): State {
        return states[identity] ?: run {
            if (states.size >= maxTrackedSessions) {
                states.keys.firstOrNull()?.let(states::remove)
            }
            State().also { states[identity] = it }
        }
    }
}

private val streamThumbnailFetchGate = StreamThumbnailFetchGate()

internal data class ThumbnailCachePolicies(
    val memory: CachePolicy,
    val disk: CachePolicy,
    val network: CachePolicy,
)

internal fun thumbnailCachePolicies(fresh: Boolean): ThumbnailCachePolicies {
    return if (fresh) {
        ThumbnailCachePolicies(
            memory = CachePolicy.WRITE_ONLY,
            disk = CachePolicy.WRITE_ONLY,
            network = CachePolicy.ENABLED,
        )
    } else {
        ThumbnailCachePolicies(
            memory = CachePolicy.READ_ONLY,
            disk = CachePolicy.READ_ONLY,
            network = CachePolicy.DISABLED,
        )
    }
}

internal fun shouldRefreshThumbnailAfterCache(
    cacheSource: DataSource?,
    forceRefresh: Boolean,
): Boolean = cacheSource != DataSource.MEMORY_CACHE || forceRefresh

internal fun shouldPreserveThumbnailOnFreshFailure(
    identityMatches: Boolean,
    preserveCurrentImage: Boolean,
    hasDisplayedImage: Boolean,
): Boolean = identityMatches && preserveCurrentImage && hasDisplayedImage

private fun ImageRequest.Builder.thumbnailDebugDiagnostics(
    stage: String,
    identity: String,
    bucket: Long,
    onSuccess: (DataSource) -> Unit = {},
    onError: () -> Unit = {},
): ImageRequest.Builder = apply {
    listener(
        onError = { _, _ -> onError() },
        onSuccess = { _, result ->
            onSuccess(result.dataSource)
            if (BuildConfig.DEBUG) {
                Log.d(
                    "StreamThumbnail",
                    "stage=$stage source=${result.dataSource} sessionHash=${identity.hashCode().toString(16)} bucket=$bucket",
                )
            }
        },
    )
}

internal fun streamThumbnailRequestPlan(stream: Stream, bucket: Long): StreamThumbnailRequestPlan? {
    if (stream.thumbnailURL.isNullOrBlank()) return null
    val thumbnail = stream.thumbnail ?: return null
    val identity = stream.thumbnailIdentity()
    val separator = if ('?' in thumbnail) '&' else '?'
    return StreamThumbnailRequestPlan(
        diskCacheKey = "xtra:stream-thumbnail:$identity",
        memoryCacheKey = "xtra:stream-thumbnail-memory:$identity:bucket:$bucket",
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
    val identity = stream.thumbnailIdentity()
    if (imageView.tag != identity) {
        imageView.setImageDrawable(null)
    }
    imageView.tag = identity
    val bucket = StreamThumbnailPolicy.bucket(System.currentTimeMillis())
    val forceEpoch = StreamThumbnailRefreshSignal.currentForceEpoch()
    val plan = streamThumbnailRequestPlan(stream, bucket)
    if (plan == null) {
        imageView.setImageDrawable(null)
        return
    }

    fun enqueueFreshRequest() {
        val nowMs = System.currentTimeMillis()
        if (!streamThumbnailFetchGate.shouldFetch(identity, bucket, forceEpoch, nowMs)) return
        streamThumbnailFetchGate.markAttempt(identity, bucket, forceEpoch, nowMs)
        val preserveCurrentImage = imageView.tag == identity && imageView.drawable != null
        val policies = thumbnailCachePolicies(fresh = true)
        ImageRequest.Builder(context).apply {
            data(plan.networkUrl)
            // The fresh stage must bypass both stale memory and disk reads while
            // retaining the new response in both caches.
            memoryCachePolicy(policies.memory)
            memoryCacheKey(plan.memoryCacheKey)
            diskCachePolicy(policies.disk)
            diskCacheKey(plan.diskCacheKey)
            networkCachePolicy(policies.network)
            crossfade(false)
            if (!preserveCurrentImage) {
                thumbnailState()
            }
            thumbnailDebugDiagnostics(
                stage = "fresh",
                identity = identity,
                bucket = bucket,
                onSuccess = { streamThumbnailFetchGate.markSuccess(identity, bucket, forceEpoch) },
            )
            target(StreamImageTarget(imageView, identity, preserveCurrentImage))
        }.build().let(context.imageLoader::enqueue)
    }

    // Stage one renders the stable last-known image without touching the
    // network. Stage two then revalidates the preview and atomically replaces
    // the displayed image and the same stable disk entry if successful.
    val policies = thumbnailCachePolicies(fresh = false)
    ImageRequest.Builder(context).apply {
        data(plan.networkUrl)
        // READ_ONLY prevents this stale stage from populating the memory entry
        // that the fresh stage deliberately bypasses.
        memoryCachePolicy(policies.memory)
        memoryCacheKey(plan.memoryCacheKey)
        diskCachePolicy(policies.disk)
        diskCacheKey(plan.diskCacheKey)
        networkCachePolicy(policies.network)
        thumbnailDebugDiagnostics(
            stage = "stale",
            identity = identity,
            bucket = bucket,
            onSuccess = { source ->
                val forceRefresh = streamThumbnailFetchGate.forcePending(identity, bucket, forceEpoch)
                val retryFreshRequest = source == DataSource.MEMORY_CACHE &&
                        streamThumbnailFetchGate.shouldFetch(
                            identity = identity,
                            bucket = bucket,
                            forceEpoch = forceEpoch,
                            nowMs = System.currentTimeMillis(),
                        )
                if (shouldRefreshThumbnailAfterCache(
                        cacheSource = source,
                        forceRefresh = forceRefresh || retryFreshRequest,
                    )
                ) {
                    enqueueFreshRequest()
                }
            },
            onError = { enqueueFreshRequest() },
        )
        target(StreamThumbnailCacheTarget(imageView, identity))
    }.build().let(context.imageLoader::enqueue)
}
