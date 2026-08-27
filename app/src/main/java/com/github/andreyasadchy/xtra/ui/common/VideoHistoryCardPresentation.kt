package com.github.andreyasadchy.xtra.ui.common

import android.text.format.DateUtils
import android.util.LruCache
import com.github.andreyasadchy.xtra.model.VideoHistory
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class VideoHistoryCardPresentation(
    val key: VideoHistoryCardPresentationKey,
    val thumbnailUrl: String?,
    val avatarUrl: String?,
    val duration: String?,
)

internal data class VideoHistoryCardPresentationKey(
    val id: Long,
    val thumbnailURL: String?,
    val channelImageURL: String?,
    val durationSeconds: Int?,
)

/** Prepares the small amount of derived Continue Watching UI off the bind path. */
internal object VideoHistoryCardPresentationCache {
    private const val MAX_ENTRIES = 256
    private const val PREWARM_LIMIT = 32

    private val scope = FeedPresentationDispatcher.scope
    private val lock = Any()
    private val cache = object : LruCache<VideoHistoryCardPresentationKey, VideoHistoryCardPresentation>(MAX_ENTRIES) {}
    private val pending = HashMap<VideoHistoryCardPresentationKey, MutableList<(VideoHistoryCardPresentation) -> Unit>>()

    fun key(item: VideoHistory): VideoHistoryCardPresentationKey = VideoHistoryCardPresentationKey(
        id = item.id,
        thumbnailURL = item.thumbnailURL,
        channelImageURL = item.channelImageURL,
        durationSeconds = item.durationSeconds,
    )

    fun get(item: VideoHistory): VideoHistoryCardPresentation? =
        synchronized(lock) { cache.get(key(item)) }

    fun prewarm(items: List<VideoHistory>) {
        items.take(PREWARM_LIMIT).forEach { item -> request(item) }
    }

    fun request(
        item: VideoHistory,
        callback: ((VideoHistoryCardPresentation) -> Unit)? = null,
    ): VideoHistoryCardPresentation? {
        val key = key(item)
        synchronized(lock) {
            cache.get(key)?.let { return it }
            pending[key]?.let {
                callback?.let(it::add)
                return null
            }
            pending[key] = callback?.let(::mutableListOf) ?: mutableListOf()
        }
        scope.launch {
            val presentation = build(item, key)
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

    private fun build(
        item: VideoHistory,
        key: VideoHistoryCardPresentationKey,
    ): VideoHistoryCardPresentation = VideoHistoryCardPresentation(
        key = key,
        thumbnailUrl = item.thumbnailURL?.let(TwitchApiHelper::getVideoThumbnail),
        avatarUrl = item.channelImageURL?.let(TwitchApiHelper::getProfileImage),
        duration = item.durationSeconds?.let { DateUtils.formatElapsedTime(it.toLong()) },
    )
}
