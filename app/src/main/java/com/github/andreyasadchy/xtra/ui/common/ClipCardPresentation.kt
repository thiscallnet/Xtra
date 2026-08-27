package com.github.andreyasadchy.xtra.ui.common

import android.content.Context
import android.text.format.DateUtils
import android.util.LruCache
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Instant

internal data class ClipCardPresentation(
    val key: ClipCardPresentationKey,
    val thumbnail: String?,
    val channelImage: String?,
    val username: String?,
    val title: String?,
    val gameName: String?,
    val date: String?,
    val viewsLabel: String?,
    val duration: String?,
)

internal data class ClipCardPresentationKey(
    val id: String?,
    val channelLogin: String?,
    val channelName: String?,
    val channelImageURL: String?,
    val gameName: String?,
    val title: String?,
    val thumbnailURL: String?,
    val createdAt: String?,
    val viewCount: Int?,
    val durationSeconds: Int?,
    val preferences: FeedUiPreferences,
)

/** Prepares immutable clip-card labels away from RecyclerView binding. */
internal object ClipCardPresentationCache {
    private const val MAX_ENTRIES = 512
    private const val PREWARM_LIMIT = 32

    private val scope = FeedPresentationDispatcher.scope
    private val lock = Any()
    private val cache = object : LruCache<ClipCardPresentationKey, ClipCardPresentation>(MAX_ENTRIES) {}
    private val pending = HashMap<ClipCardPresentationKey, MutableList<(ClipCardPresentation) -> Unit>>()

    fun key(clip: Clip, preferences: FeedUiPreferences): ClipCardPresentationKey =
        ClipCardPresentationKey(
            id = clip.id,
            channelLogin = clip.channelLogin,
            channelName = clip.channelName,
            channelImageURL = clip.channelImageURL,
            gameName = clip.gameName,
            title = clip.title,
            thumbnailURL = clip.thumbnailURL,
            createdAt = clip.createdAt,
            viewCount = clip.viewCount,
            durationSeconds = clip.durationSeconds,
            preferences = preferences,
        )

    fun get(clip: Clip, preferences: FeedUiPreferences): ClipCardPresentation? =
        synchronized(lock) { cache.get(key(clip, preferences)) }

    fun prewarm(context: Context, clips: List<Clip>, preferences: FeedUiPreferences) {
        clips.take(PREWARM_LIMIT).forEach { clip -> request(context, clip, preferences) }
    }

    internal fun request(
        context: Context,
        clip: Clip,
        preferences: FeedUiPreferences,
        callback: ((ClipCardPresentation) -> Unit)? = null,
    ): ClipCardPresentation? {
        val key = key(clip, preferences)
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
            val presentation = build(applicationContext, clip, key)
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
        clip: Clip,
        key: ClipCardPresentationKey,
    ): ClipCardPresentation {
        val viewsLabel = clip.viewCount?.let { count ->
            context.resources.getQuantityString(
                R.plurals.views,
                count,
                TwitchApiHelper.formatCount(count, key.preferences.truncateViewCount),
            )
        }
        val username = clip.channelName?.let { channelName ->
            if (clip.channelLogin != null && !clip.channelLogin.equals(channelName, true)) {
                when (key.preferences.nameDisplay) {
                    "0" -> "$channelName(${clip.channelLogin})"
                    "1" -> channelName
                    else -> clip.channelLogin
                }
            } else channelName
        }
        return ClipCardPresentation(
            key = key,
            thumbnail = clip.thumbnail,
            channelImage = clip.channelImage,
            username = username,
            title = clip.title?.takeIf { it.isNotBlank() }?.trim(),
            gameName = clip.gameName?.trim(),
            date = clip.createdAt?.let { value ->
                Instant.parseOrNull(value)?.toEpochMilliseconds()?.takeIf { it > 0 }?.let {
                    TwitchApiHelper.formatDate(context, it)
                }
            },
            viewsLabel = viewsLabel,
            duration = clip.durationSeconds?.let { DateUtils.formatElapsedTime(it.toLong()) },
        )
    }
}
