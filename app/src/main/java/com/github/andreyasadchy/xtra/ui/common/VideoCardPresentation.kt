package com.github.andreyasadchy.xtra.ui.common

import android.content.Context
import android.text.format.DateUtils
import android.util.LruCache
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Instant

internal data class VideoCardPresentation(
    val key: VideoCardPresentationKey,
    val channelImage: String?,
    val username: String?,
    val title: String?,
    val gameName: String?,
    val date: String?,
    val viewsLabel: String?,
    val duration: String?,
    val type: String?,
)

internal data class VideoCardPresentationKey(
    val id: String?,
    val channelId: String?,
    val channelLogin: String?,
    val channelName: String?,
    val title: String?,
    val gameName: String?,
    val createdAt: String?,
    val viewCount: Int?,
    val durationSeconds: Int?,
    val type: String?,
    val preferences: FeedUiPreferences,
)

/** Prepares immutable video-card labels away from RecyclerView binding. */
internal object VideoCardPresentationCache {
    private const val MAX_ENTRIES = 512
    private const val PREWARM_LIMIT = 32

    private val scope = FeedPresentationDispatcher.scope
    private val lock = Any()
    private val cache = object : LruCache<VideoCardPresentationKey, VideoCardPresentation>(MAX_ENTRIES) {}
    private val pending = HashMap<VideoCardPresentationKey, MutableList<(VideoCardPresentation) -> Unit>>()

    fun key(video: Video, preferences: FeedUiPreferences): VideoCardPresentationKey =
        VideoCardPresentationKey(
            id = video.id,
            channelId = video.channelId,
            channelLogin = video.channelLogin,
            channelName = video.channelName,
            title = video.title,
            gameName = video.gameName,
            createdAt = video.createdAt,
            viewCount = video.viewCount,
            durationSeconds = video.durationSeconds,
            type = video.type,
            preferences = preferences,
        )

    fun get(video: Video, preferences: FeedUiPreferences): VideoCardPresentation? =
        synchronized(lock) { cache.get(key(video, preferences)) }

    fun prewarm(context: Context, videos: List<Video>, preferences: FeedUiPreferences) {
        videos.take(PREWARM_LIMIT).forEach { video ->
            request(context, video, preferences)
        }
    }

    internal fun request(
        context: Context,
        video: Video,
        preferences: FeedUiPreferences,
        callback: ((VideoCardPresentation) -> Unit)? = null,
    ): VideoCardPresentation? {
        val key = key(video, preferences)
        synchronized(lock) {
            cache.get(key)?.let { return it }
            pending[key]?.let {
                callback?.let(it::add)
                return null
            }
            pending[key] = callback?.let(::mutableListOf) ?: mutableListOf()
        }
        val applicationContext = context.applicationContext
        scope.launch {
            val presentation = build(applicationContext, video, key)
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
        context: Context,
        video: Video,
        key: VideoCardPresentationKey,
    ): VideoCardPresentation {
        val date = video.createdAt?.let { value ->
            Instant.parseOrNull(value)?.toEpochMilliseconds()?.takeIf { it > 0 }?.let {
                TwitchApiHelper.formatDate(context, it)
            }
        }
        val viewsLabel = video.viewCount?.let { count ->
            context.resources.getQuantityString(
                R.plurals.views,
                count,
                TwitchApiHelper.formatCount(count, key.preferences.truncateViewCount),
            )
        }
        val username = video.channelName?.let { channelName ->
            if (video.channelLogin != null && !video.channelLogin.equals(channelName, true)) {
                when (key.preferences.nameDisplay) {
                    "0" -> "$channelName(${video.channelLogin})"
                    "1" -> channelName
                    else -> video.channelLogin
                }
            } else {
                channelName
            }
        }
        return VideoCardPresentation(
            key = key,
            channelImage = video.channelImage,
            username = username,
            title = video.title?.takeIf { it.isNotBlank() }?.trim(),
            gameName = video.gameName?.trim(),
            date = date,
            viewsLabel = viewsLabel,
            duration = video.durationSeconds?.let { DateUtils.formatElapsedTime(it.toLong()) },
            type = video.type?.let { TwitchApiHelper.getType(context, it) },
        )
    }
}
