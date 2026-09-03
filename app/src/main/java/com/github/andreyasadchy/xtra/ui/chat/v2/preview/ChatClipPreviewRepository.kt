package com.github.andreyasadchy.xtra.ui.chat.v2.preview

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class ChatClipPreview(
    val title: String?,
    val broadcasterName: String?,
    val creatorName: String?,
    val thumbnailUrl: String?,
    val gameName: String?,
    val durationSeconds: Int?,
    /** Raw `createdAt` timestamp as returned by the API (ISO-8601), if known. */
    val createdAt: String?,
)

data class ChatClipPreviewLink(
    val slug: String,
    val url: String,
) {
    companion object {
        private val TWITCH_CLIP_LINK = Regex(
            "(?i)(https?://)?(?:www\\.)?twitch\\.tv/(?:[^/\\s]+/)?clip/([A-Za-z0-9_-]+)|" +
                "(?i)(https?://)?clips\\.twitch\\.tv/([A-Za-z0-9_-]+)",
        )

        /** Extracts every Twitch clip link in [body], deduplicated by slug. */
        fun parse(body: String): List<ChatClipPreviewLink> =
            TWITCH_CLIP_LINK.findAll(body).mapNotNull { match ->
                val slug = match.groups[2]?.value ?: match.groups[4]?.value ?: return@mapNotNull null
                val matchedUrl = match.value.trimEnd('.', ',', '!', '?', ':', ';', ')', ']', '}')
                val url = if (matchedUrl.startsWith("http://", ignoreCase = true) ||
                    matchedUrl.startsWith("https://", ignoreCase = true)
                ) matchedUrl else "https://$matchedUrl"
                ChatClipPreviewLink(slug, url)
            }.distinctBy { it.slug.lowercase() }.toList()

        fun isClipUrl(url: String): Boolean = TWITCH_CLIP_LINK.containsMatchIn(url)
    }
}

/** Formats a clip duration the way Twitch does (`m:ss`, `h:mm:ss` past an hour). */
fun formatClipDuration(durationSeconds: Int?): String? {
    if (durationSeconds == null || durationSeconds < 0) return null
    val hours = durationSeconds / 3600
    val minutes = (durationSeconds % 3600) / 60
    val seconds = durationSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

/** Parses the API `createdAt` timestamp to epoch millis, or null when unknown. */
fun parseClipTimestamp(createdAt: String?): Long? {
    val epochMs = createdAt?.let { kotlin.time.Instant.parseOrNull(it)?.toEpochMilliseconds() } ?: return null
    return epochMs.takeIf { it > 0 }
}

/** Shares clip metadata between recycled chat rows and between chat surfaces. */
class ChatClipPreviewRepository(
    private val scope: CoroutineScope,
    private val loader: suspend (String) -> ChatClipPreview?,
) {
    private val cache = HashMap<String, ChatClipPreview>()
    private val listeners = HashMap<String, MutableSet<() -> Unit>>()
    private val loading = HashSet<String>()

    @Synchronized
    fun peek(slug: String): ChatClipPreview? = cache[slug]

    fun observe(slug: String, listener: () -> Unit) {
        val shouldLoad: Boolean
        val cached: Boolean
        synchronized(this) {
            listeners.getOrPut(slug) { LinkedHashSet() }.add(listener)
            cached = cache.containsKey(slug)
            shouldLoad = !cached && loading.add(slug)
        }
        if (cached) listener()
        if (shouldLoad) {
            scope.launch {
                val preview = runCatching { loader(slug) }.getOrNull()
                val callbacks = synchronized(this@ChatClipPreviewRepository) {
                    // A failed request must remain retryable when the same clip is seen again.
                    if (preview != null) cache[slug] = preview
                    loading.remove(slug)
                    listeners[slug]?.toList().orEmpty()
                }
                callbacks.forEach { it() }
            }
        }
    }

    @Synchronized
    fun removeObserver(slug: String, listener: () -> Unit) {
        listeners[slug]?.remove(listener)
        if (listeners[slug].isNullOrEmpty()) listeners.remove(slug)
    }
}
