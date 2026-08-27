package com.github.andreyasadchy.xtra.ui.common

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.LruCache
import android.view.Choreographer
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import coil3.Image
import coil3.asDrawable
import coil3.decode.DataSource
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.Disposable
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
import com.github.andreyasadchy.xtra.util.UiInteractionGovernor
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.IdentityHashMap

internal interface FeedImageRequestOwner {
    /** Cancel optional work without invalidating an image that is already on screen. */
    fun cancelImageRequests()

    /** Pause active work while retaining registrations for a same-bind resume. */
    fun pauseImageRequests()
}

/**
 * Frame-budgeted scheduler for user-visible feed image work.
 *
 * Historical class name retained to avoid adapter churn.
 *
 * Interaction changes the amount of image work started per frame. It never
 * completely stops visible image loading, and beginning a gesture never
 * cancels requests for holders that remain attached.
 */
internal class StreamThumbnailIdleScheduler {
    private var recyclerView: RecyclerView? = null

    private class ScheduledWork(
        var work: () -> Unit,
        var scheduled: Boolean = false,
    )

    /**
     * Keep only the newest request recipe for each currently bound owner/slot.
     * This avoids accumulating work for every row crossed during a fling.
     */
    private val latestWork =
        IdentityHashMap<Any, IdentityHashMap<Any, ScheduledWork>>()
    private val attachedOwners =
        Collections.newSetFromMap(
            IdentityHashMap<Any, Boolean>(),
        )
    private var drainPosted = false
    private var drainFrameCallback: Choreographer.FrameCallback? = null

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            UiInteractionGovernor.setInteracting(
                this@StreamThumbnailIdleScheduler,
                newState != RecyclerView.SCROLL_STATE_IDLE,
            )

            // Interaction affects the shared per-frame budget, but does not
            // block visible image work.
            if (hasScheduledWork()) {
                scheduleDrain(recyclerView)
            }
        }
    }
    private val childAttachListener = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: android.view.View) {
            val owner = recyclerView?.getChildViewHolder(view) as? FeedImageRequestOwner ?: return
            attachedOwners.add(owner)

            // A holder can be bound while RecyclerView is prefetching and
            // only attach later. Start its newest registered image work as
            // soon as it becomes relevant to the viewport, even if a fling
            // is still in progress.
            latestWork[owner]?.values?.forEach { it.scheduled = true }

            recyclerView?.let { rv ->
                if (latestWork[owner]?.values?.any(ScheduledWork::scheduled) == true) {
                    scheduleDrain(rv)
                }
            }
        }

        override fun onChildViewDetachedFromWindow(view: android.view.View) {
            val owner = recyclerView?.getChildViewHolder(view) as? FeedImageRequestOwner
            owner?.cancelImageRequests()
            if (owner != null) {
                attachedOwners.remove(owner)
                // A detached holder may stay in RecyclerView's cache. Keep
                // its latest bind recipe for a future reattach, but never
                // leave executable work scheduled for it.
                latestWork[owner]?.values?.forEach { it.scheduled = false }
            }
        }
    }

    fun attachTo(recyclerView: RecyclerView) {
        if (this.recyclerView === recyclerView) return
        detach()
        this.recyclerView = recyclerView
        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.addOnChildAttachStateChangeListener(childAttachListener)
        UiInteractionGovernor.setInteracting(this, recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE)

        for (index in 0 until recyclerView.childCount) {
            val owner = recyclerView.getChildViewHolder(
                recyclerView.getChildAt(index),
            ) as? FeedImageRequestOwner ?: continue

            attachedOwners.add(owner)
            latestWork[owner]?.values?.forEach { it.scheduled = true }
        }

        if (hasScheduledWork()) {
            scheduleDrain(recyclerView)
        }
    }

    fun detach() {
        recyclerView?.removeOnScrollListener(scrollListener)
        recyclerView?.removeOnChildAttachStateChangeListener(childAttachListener)
        UiInteractionGovernor.setInteracting(this, false)
        recyclerView = null
        latestWork.clear()
        attachedOwners.clear()
        drainFrameCallback?.let(Choreographer.getInstance()::removeFrameCallback)
        drainFrameCallback = null
        drainPosted = false
    }

    /** Drops work registered by an old bind before a holder receives a new item. */
    fun clear(owner: Any) {
        latestWork.remove(owner)
    }

    fun runOrDefer(owner: Any, slot: Any, work: () -> Unit) {
        val recyclerView = recyclerView
        val ownerWork = latestWork.getOrPut(owner) { IdentityHashMap() }
        val scheduledWork = ownerWork[slot]
            ?.also { it.work = work }
            ?: ScheduledWork(work).also { ownerWork[slot] = it }
        // RecyclerView prefetch can bind a holder before it is attached. Keep
        // only the newest recipe and let onChildViewAttachedToWindow activate
        // it.
        if (recyclerView == null || owner !in attachedOwners) {
            return
        }

        // Visible work is frame-budgeted, not idle-gated.
        scheduledWork.scheduled = true
        scheduleDrain(recyclerView)
    }

    private fun scheduleDrain(recyclerView: RecyclerView) {
        if (drainPosted) return
        drainPosted = true
        val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (drainFrameCallback !== this) return
                drainFrameCallback = null
                drainPosted = false
                drain(
                    recyclerView = recyclerView,
                    frameTimeNanos = frameTimeNanos,
                )
            }
        }
        drainFrameCallback = frameCallback
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun drain(
        recyclerView: RecyclerView,
        frameTimeNanos: Long,
    ) {
        if (this.recyclerView !== recyclerView) return

        val ownerIterator = attachedOwners.iterator()
        while (ownerIterator.hasNext()) {
            val owner = ownerIterator.next()
            val slotIterator = latestWork[owner]?.values?.iterator() ?: continue

            while (slotIterator.hasNext()) {
                val scheduledWork = slotIterator.next()
                if (!scheduledWork.scheduled) continue

                // Shared process-wide frame budget. This works during
                // interaction as well. The governor supplies a smaller
                // budget while the user is interacting.
                if (!UiInteractionGovernor.tryAcquireVisibleImageStart(frameTimeNanos)) {
                    scheduleDrain(recyclerView)
                    return
                }

                scheduledWork.scheduled = false
                scheduledWork.work()
            }
        }

        if (hasScheduledWork()) {
            scheduleDrain(recyclerView)
        }
    }

    private fun hasScheduledWork(): Boolean =
        attachedOwners.any { owner ->
            latestWork[owner]
                ?.values
                ?.any(ScheduledWork::scheduled) == true
        }
}

/** Owns optional image work for one recycled feed holder. */
internal class FeedImageRequestBag {
    private val cancellations = IdentityHashMap<Any, () -> Unit>()

    fun replace(slot: Any, disposable: Disposable) {
        cancellations.put(slot, disposable::dispose)?.invoke()
    }

    fun replace(slot: Any, handle: StreamThumbnailRequestHandle) {
        cancellations.put(slot, handle::cancel)?.invoke()
    }

    fun cancel(preserveRegistrations: Boolean = false) {
        cancellations.values.forEach { it() }
        if (!preserveRegistrations) cancellations.clear()
    }
}

/**
 * A thumbnail consists of a cache stage and an optional fresh stage. Keep one
 * cancellation handle for both so a recycled holder can stop either stage,
 * including a fresh request scheduled by the cache-stage callback.
 */
internal class StreamThumbnailRequestHandle(
    private val onCancel: () -> Unit = {},
) {
    private var disposable: Disposable? = null
    private var cancelled = false

    @Synchronized
    fun set(disposable: Disposable) {
        if (cancelled) {
            disposable.dispose()
        } else {
            this.disposable?.dispose()
            this.disposable = disposable
        }
    }

    @Synchronized
    fun cancel() {
        if (cancelled) return
        cancelled = true
        disposable?.dispose()
        disposable = null
        onCancel()
    }

    @Synchronized
    fun rearm() {
        cancelled = false
    }
}

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

internal object StreamThumbnailChangedPayload

private fun streamMetadataSame(oldItem: Stream, newItem: Stream): Boolean {
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

internal fun streamContentsSame(oldItem: Stream, newItem: Stream): Boolean {
    return streamMetadataSame(oldItem, newItem)
}

internal fun streamThumbnailOnlyChanged(oldItem: Stream, newItem: Stream): Boolean {
    return streamMetadataSame(oldItem, newItem) &&
            oldItem.thumbnailGeneration != newItem.thumbnailGeneration
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
    private val requestKey: Any? = null,
    private val thumbnailRequestKey: Any? = null,
    private val thumbnailCacheKey: String? = null,
    private val onSuccessApplied: (() -> Unit)? = null,
) : ImageViewTarget(imageView) {

    private fun isCurrent(): Boolean =
        view.tag == identity &&
            when {
                requestKey != null -> view.getTag(R.id.stream_profile_request_key) == requestKey
                thumbnailRequestKey != null -> view.getTag(R.id.stream_thumbnail_request_key) == thumbnailRequestKey
                else -> true
            }

    override fun onStart(placeholder: Image?) {
        if (preserveCurrentImage && isCurrent() && view.drawable != null) return
        super.onStart(placeholder)
    }

    override fun onSuccess(result: Image) {
        if (isCurrent()) {
            super.onSuccess(result)
            thumbnailCacheKey?.let { rememberWarmStreamThumbnail(it, view) }
            onSuccessApplied?.invoke()
        }
    }

    override fun onError(error: Image?) {
        if (!isCurrent()) return
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
    private val cacheKey: String,
) : ImageViewTarget(imageView) {

    override fun onStart(placeholder: Image?) {
        // The cache-only stage is intentionally silent. It must never replace
        // a valid image with a template while it looks for the stale disk row.
    }

    override fun onSuccess(result: Image) {
        if (view.tag == identity) {
            super.onSuccess(result)
            rememberWarmStreamThumbnail(cacheKey, view)
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

private data class StreamThumbnailViewRequestKey(
    val identity: String,
    val sourceUrl: String,
    val memoryCacheKey: String,
    val freshnessBucket: Long,
    val thumbnailGeneration: Long,
    val forceEpoch: Long,
)

private val warmStreamThumbnailCache = object : LruCache<String, Drawable.ConstantState>(16) {}

private data class WarmThumbnailRequestKey(val memoryCacheKey: String)

private fun rememberWarmStreamThumbnail(cacheKey: String, imageView: ImageView) {
    imageView.drawable?.constantState?.let { state ->
        synchronized(warmStreamThumbnailCache) {
            warmStreamThumbnailCache.put(cacheKey, state)
        }
        if (BuildConfig.DEBUG) Log.d("StreamThumbnail", "warm_store keyHash=${cacheKey.hashCode().toString(16)}")
    }
}

private fun restoreWarmStreamThumbnail(cacheKey: String, imageView: ImageView): Boolean {
    if (imageView.drawable != null) return false
    val state = synchronized(warmStreamThumbnailCache) {
        warmStreamThumbnailCache.get(cacheKey)
    } ?: return false
    imageView.setImageDrawable(state.newDrawable(imageView.resources))
    if (BuildConfig.DEBUG) Log.d("StreamThumbnail", "warm_restore keyHash=${cacheKey.hashCode().toString(16)}")
    return true
}

/**
 * Coil's decoded memory cache is the source of truth for images that were
 * loaded by another holder. Restoring it synchronously avoids showing the
 * layout's grey placeholder while a cache-only request posts its callback.
 * The small ConstantState cache above remains a fallback for images Coil has
 * already evicted from its decoded cache.
 */
internal fun restoreDecodedMemoryImage(
    cacheKey: String,
    imageView: ImageView,
): Boolean {
    val cachedImage = imageView.context.imageLoader.memoryCache
        ?.get(MemoryCache.Key(cacheKey))
        ?.image
        ?: return false

    imageView.setImageDrawable(
        cachedImage.asDrawable(imageView.resources)
    )

    if (BuildConfig.DEBUG) {
        Log.d(
            "StreamThumbnail",
            "memory_restore keyHash=${cacheKey.hashCode().toString(16)}",
        )
    }

    return true
}

private fun restoreDecodedStreamThumbnail(cacheKey: String, imageView: ImageView): Boolean =
    restoreDecodedMemoryImage(cacheKey, imageView)

/**
 * Restore an already-decoded thumbnail during bind. This is memory-only;
 * disk/network work remains behind the idle scheduler.
 */
internal fun restoreWarmStreamThumbnail(stream: Stream, imageView: ImageView): Boolean {
    val plan = streamThumbnailRequestPlan(stream, StreamThumbnailPolicy.bucket(System.currentTimeMillis())) ?: return false
    if (!restoreDecodedStreamThumbnail(plan.memoryCacheKey, imageView) &&
        !restoreWarmStreamThumbnail(plan.memoryCacheKey, imageView)
    ) return false
    imageView.setTag(R.id.stream_thumbnail_request_key, WarmThumbnailRequestKey(plan.memoryCacheKey))
    return true
}

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
    fun clearAttempt(identity: String, bucket: Long, forceEpoch: Long) {
        val state = states[identity] ?: return
        if (state.attemptedBucket == bucket && state.attemptedForceEpoch == forceEpoch) {
            state.attemptedBucket = null
            state.attemptedForceEpoch = 0L
            state.attemptedAtMs = 0L
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
        // Keep one decoded in-process image per stream. The bucket belongs to
        // freshness/revalidation, not to the identity of the image currently
        // displayed. This lets a recreated holder render the last image from
        // memory immediately while the optional fresh request runs later.
        memoryCacheKey = "xtra:stream-thumbnail-memory:$identity",
        networkUrl = "$thumbnail${separator}xtra_preview_bucket=$bucket",
    )
}

internal fun prepareStreamProfileImage(imageView: ImageView, stream: Stream) {
    val identity = stream.streamIdentity()
    if (imageView.tag != identity) {
        imageView.setImageDrawable(null)
        imageView.setTag(R.id.stream_profile_request_key, null)
    }
    imageView.tag = identity
}

internal fun prepareStreamThumbnailImage(imageView: ImageView, stream: Stream) {
    val identity = stream.thumbnailIdentity()
    if (imageView.tag != identity) {
        imageView.setImageDrawable(null)
        imageView.setTag(R.id.stream_thumbnail_request_key, null)
        imageView.setTag(R.id.stream_thumbnail_successful_fresh_key, null)
    }
    imageView.tag = identity
}

internal fun loadStreamProfileImage(
    context: Context,
    imageView: ImageView,
    stream: Stream,
): Disposable? {
    val identity = stream.streamIdentity()
    val sameIdentity = imageView.tag == identity
    if (!sameIdentity) {
        imageView.setImageDrawable(null)
        imageView.setTag(R.id.stream_profile_request_key, null)
    }
    imageView.tag = identity
    val url = stream.channelImage
    if (url.isNullOrBlank()) {
        imageView.setImageDrawable(null)
        imageView.setTag(R.id.stream_profile_request_key, null)
        return null
    }
    val roundUserImage = FeedUiPreferencesStore.current(context).roundUserImage
    val requestKey = "$identity|$url|round=$roundUserImage"
    if (sameIdentity &&
        imageView.drawable != null &&
        imageView.getTag(R.id.stream_profile_request_key) == requestKey
    ) {
        return null
    }
    imageView.setTag(R.id.stream_profile_request_key, requestKey)
    if (restoreDecodedMemoryImage(requestKey, imageView)) {
        return null
    }
    return context.imageLoader.enqueue(ImageRequest.Builder(context).apply {
        data(url)
        memoryCacheKey(requestKey)
        diskCachePolicy(CachePolicy.ENABLED)
        if (roundUserImage) {
            transformations(CircleCropTransformation())
        }
        // Keep an already displayed image in place while a changed profile URL loads.
        crossfade(false)
        target(StreamImageTarget(imageView, identity, preserveCurrentImage = sameIdentity, requestKey = requestKey))
    }.build())
}

/**
 * Restores a decoded profile image synchronously during a warm rebind. The
 * actual disk/network request remains owned by the idle scheduler.
 */
internal fun restoreWarmStreamProfileImage(
    context: Context,
    imageView: ImageView,
    stream: Stream,
): Boolean {
    val url = stream.channelImage ?: return false
    val preferences = FeedUiPreferencesStore.current(context)

    val identity = stream.streamIdentity()
    val requestKey =
        "$identity|$url|round=${preferences.roundUserImage}"

    // Already displaying the exact successfully satisfied profile request.
    if (
        imageView.tag == identity &&
        imageView.getTag(R.id.stream_profile_request_key) == requestKey &&
        imageView.drawable != null
    ) {
        return true
    }

    if (!restoreDecodedMemoryImage(requestKey, imageView)) {
        return false
    }

    // This decoded-memory hit satisfies exactly the same request that
    // loadStreamProfileImage() would mark before starting Coil.
    // Record it so subsequent binds become a true zero-work fast path.
    imageView.setTag(
        R.id.stream_profile_request_key,
        requestKey,
    )

    return true
}

internal fun loadStreamThumbnail(
    context: Context,
    imageView: ImageView,
    stream: Stream,
    scheduleFreshRequest: ((() -> Unit) -> Unit),
): StreamThumbnailRequestHandle? {
    val identity = stream.thumbnailIdentity()
    val sameIdentity = imageView.tag == identity
    if (!sameIdentity) {
        imageView.setImageDrawable(null)
        imageView.setTag(R.id.stream_thumbnail_request_key, null)
        imageView.setTag(R.id.stream_thumbnail_successful_fresh_key, null)
    }
    imageView.tag = identity
    val bucket = StreamThumbnailPolicy.bucket(System.currentTimeMillis())
    val forceEpoch = StreamThumbnailRefreshSignal.currentForceEpoch()
    val plan = streamThumbnailRequestPlan(stream, bucket)
    if (plan == null) {
        imageView.setImageDrawable(null)
        imageView.setTag(R.id.stream_thumbnail_request_key, null)
        imageView.setTag(R.id.stream_thumbnail_successful_fresh_key, null)
        return null
    }

    val requestKey = StreamThumbnailViewRequestKey(
        identity = identity,
        sourceUrl = stream.thumbnailURL.orEmpty(),
        memoryCacheKey = plan.memoryCacheKey,
        freshnessBucket = bucket,
        thumbnailGeneration = stream.thumbnailGeneration,
        forceEpoch = forceEpoch,
    )
    val warmImageRestored = imageView.getTag(R.id.stream_thumbnail_request_key) ==
            WarmThumbnailRequestKey(plan.memoryCacheKey)
    // Paging can rebind an unchanged row while another part of the screen is
    // updating. A cache hit still allocates a request and callback chain, so
    // do not enqueue the same request again when its image is already shown.
    if (sameIdentity &&
        imageView.drawable != null &&
        imageView.getTag(R.id.stream_thumbnail_successful_fresh_key) == requestKey
    ) {
        return null
    }
    imageView.setTag(R.id.stream_thumbnail_request_key, requestKey)
    val requestHandle = StreamThumbnailRequestHandle {
        streamThumbnailFetchGate.clearAttempt(identity, bucket, forceEpoch)
    }

    fun enqueueFreshRequest() {
        val nowMs = System.currentTimeMillis()
        if (!streamThumbnailFetchGate.shouldFetch(identity, bucket, forceEpoch, nowMs)) return
        streamThumbnailFetchGate.markAttempt(identity, bucket, forceEpoch, nowMs)
        requestHandle.rearm()
        val preserveCurrentImage = imageView.tag == identity && imageView.drawable != null
        val policies = thumbnailCachePolicies(fresh = true)
        requestHandle.set(context.imageLoader.enqueue(ImageRequest.Builder(context).apply {
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
                onSuccess = {
                    // The response is valid for this stream even when the
                    // holder was recycled before the target callback. Keep
                    // freshness bookkeeping independent from view ownership
                    // so a successful request is not retried on every bind.
                    streamThumbnailFetchGate.markSuccess(identity, bucket, forceEpoch)
                },
            )
            target(
                StreamImageTarget(
                    imageView = imageView,
                    identity = identity,
                    preserveCurrentImage = preserveCurrentImage,
                    thumbnailRequestKey = requestKey,
                    thumbnailCacheKey = plan.memoryCacheKey,
                    onSuccessApplied = {
                        imageView.setTag(R.id.stream_thumbnail_successful_fresh_key, requestKey)
                    },
                ),
            )
        }.build()))
    }

    // A warm ConstantState is already a decoded stale image. Avoid issuing a
    // second cache-only request that would decode the same bitmap from disk;
    // only revalidate when the fetch gate says the current bucket is due.
    if (warmImageRestored || restoreWarmStreamThumbnail(plan.memoryCacheKey, imageView)) {
        if (streamThumbnailFetchGate.shouldFetch(identity, bucket, forceEpoch, System.currentTimeMillis())) {
            scheduleFreshRequest(::enqueueFreshRequest)
        }
        return requestHandle
    }

    // Stage one renders the stable last-known image without touching the
    // network. Stage two then revalidates the preview and atomically replaces
    // the displayed image and the same stable disk entry if successful.
    val policies = thumbnailCachePolicies(fresh = false)
    requestHandle.set(context.imageLoader.enqueue(ImageRequest.Builder(context).apply {
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
                    scheduleFreshRequest(::enqueueFreshRequest)
                }
            },
            onError = { scheduleFreshRequest(::enqueueFreshRequest) },
        )
        target(StreamThumbnailCacheTarget(imageView, identity, plan.memoryCacheKey))
    }.build()))
    return requestHandle
}
