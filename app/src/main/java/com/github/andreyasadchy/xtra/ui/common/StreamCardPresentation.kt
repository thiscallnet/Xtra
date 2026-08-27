package com.github.andreyasadchy.xtra.ui.common

import android.content.Context
import android.util.LruCache
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

/** Immutable, bind-ready values for a stream card. No Android views or drawables are retained. */
internal data class StreamCardPresentation(
    val key: StreamCardPresentationKey,
    val channelImage: String?,
    val username: String?,
    val title: String?,
    val gameName: String?,
    val viewerLabel: String?,
    val uptime: String?,
    val tags: List<String>,
)

internal data class StreamCardPresentationKey(
    val streamIdentity: String,
    val channelId: String?,
    val channelLogin: String?,
    val channelName: String?,
    val title: String?,
    val gameName: String?,
    val viewerCount: Int?,
    val createdAt: String?,
    val tags: List<String>?,
    val preferences: FeedUiPreferences,
    val uptimeMinute: Long,
)

internal object FeedPresentationDispatcher {
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(2),
    )
}

/**
 * Prepares stream-card text away from the UI thread and shares the result
 * between all stream feed adapters. The key includes every mutable display
 * input, so viewer and uptime changes cannot reuse stale text.
 */
internal object StreamCardPresentationCache {
    private const val MAX_ENTRIES = 512

    private val scope = FeedPresentationDispatcher.scope
    private val lock = Any()
    private val cache = object : LruCache<StreamCardPresentationKey, StreamCardPresentation>(MAX_ENTRIES) {}
    private val pending = HashMap<StreamCardPresentationKey, MutableList<(StreamCardPresentation) -> Unit>>()

    fun key(stream: Stream, preferences: FeedUiPreferences, nowMs: Long = System.currentTimeMillis()): StreamCardPresentationKey =
        StreamCardPresentationKey(
            streamIdentity = stream.streamIdentity(),
            channelId = stream.channelId,
            channelLogin = stream.channelLogin,
            channelName = stream.channelName,
            title = stream.title,
            gameName = stream.gameName?.trim(),
            viewerCount = stream.viewerCount,
            createdAt = stream.createdAt,
            tags = stream.tags,
            preferences = preferences,
            uptimeMinute = nowMs / 60_000L,
        )

    fun get(stream: Stream, preferences: FeedUiPreferences): StreamCardPresentation? =
        synchronized(lock) { cache.get(key(stream, preferences)) }

    /** Schedule each missing key once; callbacks are delivered on the main thread. */
    fun request(
        context: Context,
        stream: Stream,
        preferences: FeedUiPreferences,
        callback: ((StreamCardPresentation) -> Unit)? = null,
    ): StreamCardPresentation? {
        val key = key(stream, preferences)
        synchronized(lock) {
            cache.get(key)?.let { return it }
            val callbacks = pending[key]
            if (callbacks != null) {
                callback?.let(callbacks::add)
                return null
            }
            pending[key] = callback?.let(::mutableListOf) ?: mutableListOf()
        }
        val applicationContext = context.applicationContext
        scope.launch {
            val presentation = build(applicationContext, stream, key)
            val callbacks = synchronized(lock) {
                cache.put(key, presentation)
                pending.remove(key).orEmpty()
            }
            if (callbacks.isNotEmpty()) {
                withContext(Dispatchers.Main.immediate) {
                    callbacks.forEach { it(presentation) }
                }
            }
        }
        return null
    }

    /** Prepares a bounded visible window before it is handed to RecyclerView. */
    fun prewarm(
        context: Context,
        streams: List<Stream>,
        preferences: FeedUiPreferences,
        callback: (() -> Unit)? = null,
    ) {
        val visibleWindow = streams.take(PREWARM_LIMIT)
        if (visibleWindow.isEmpty()) {
            callback?.invoke()
            return
        }
        if (callback == null) {
            visibleWindow.forEach { stream -> request(context, stream, preferences) }
        } else {
            val remaining = java.util.concurrent.atomic.AtomicInteger(visibleWindow.size)
            visibleWindow.forEach { stream ->
                request(context, stream, preferences) {
                    if (remaining.decrementAndGet() == 0) callback()
                } ?: run {
                    if (remaining.decrementAndGet() == 0) callback()
                }
            }
        }
    }

    private fun build(
        context: Context,
        stream: Stream,
        key: StreamCardPresentationKey,
    ): StreamCardPresentation {
        val viewerLabel = stream.viewerCount?.let { count ->
            context.resources.getQuantityString(
                R.plurals.viewers,
                count,
                TwitchApiHelper.formatCount(count, key.preferences.truncateViewCount),
            )
        }
        val uptime = if (key.preferences.showUptime) {
            stream.createdAt?.let { value ->
                Instant.parseOrNull(value)?.takeIf { it.toEpochMilliseconds() > 0 }?.let { createdAt ->
                    val elapsed = Clock.System.now() - createdAt
                    if (elapsed.isPositive()) {
                        android.text.format.DateUtils.formatElapsedTime(elapsed.inWholeSeconds)
                    } else {
                        null
                    }
                }
            }
        } else {
            null
        }
        val username = stream.channelName?.let { channelName ->
            if (stream.channelLogin != null && !stream.channelLogin.equals(channelName, true)) {
                when (key.preferences.nameDisplay) {
                    "0" -> "$channelName(${stream.channelLogin})"
                    "1" -> channelName
                    else -> stream.channelLogin
                }
            } else {
                channelName
            }
        }
        val tags = if (key.preferences.showTags) {
            stream.tags.orEmpty()
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .take(8)
                .toList()
        } else {
            emptyList()
        }
        return StreamCardPresentation(
            key = key,
            channelImage = stream.channelImage,
            username = username,
            title = stream.title?.takeIf { it.isNotBlank() }?.trim(),
            gameName = stream.gameName,
            viewerLabel = viewerLabel,
            uptime = uptime,
            tags = tags,
        )
    }

    private const val PREWARM_LIMIT = 32
}
