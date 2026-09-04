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

/** Immutable, bind-ready values for a stream card. No Android views or drawables are retained. */
internal data class StreamCardPresentation(
    val key: StreamCardPresentationKey,
    val channelImage: String?,
    val username: String?,
    val title: String?,
    val gameName: String?,
    val viewerLabel: String?,
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
    val tags: List<String>?,
    val preferences: FeedUiPreferences,
)

internal object FeedPresentationDispatcher {
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(2),
    )
}

/**
 * Prepares stream-card text away from the UI thread and shares the result
 * between all stream feed adapters. The key includes every mutable display
 * input used by the cached presentation.
 */
internal object StreamCardPresentationCache {
    private const val MAX_ENTRIES = 512

    private val scope = FeedPresentationDispatcher.scope
    private val lock = Any()
    private val cache = object : LruCache<StreamCardPresentationKey, StreamCardPresentation>(MAX_ENTRIES) {}
    private val pending = HashMap<StreamCardPresentationKey, MutableList<(StreamCardPresentation) -> Unit>>()

    fun key(stream: Stream, preferences: FeedUiPreferences): StreamCardPresentationKey =
        StreamCardPresentationKey(
            streamIdentity = stream.streamIdentity(),
            channelId = stream.channelId,
            channelLogin = stream.channelLogin,
            channelName = stream.channelName,
            title = stream.title,
            gameName = stream.gameName?.trim(),
            viewerCount = stream.viewerCount,
            tags = stream.tags,
            preferences = preferences,
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
        itemCount: Int,
        itemAt: (Int) -> Stream?,
        preferences: FeedUiPreferences,
    ) {
        val count = minOf(itemCount, PREWARM_LIMIT)
        // Prewarming only populates the process-local cache. It must not hop
        // back to the main thread or cause a second bind for every card.
        for (index in 0 until count) {
            val stream = itemAt(index) ?: continue
            request(context, stream, preferences)
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
            tags = tags,
        )
    }

    private const val PREWARM_LIMIT = 32
}
