package com.github.andreyasadchy.xtra.ui.chat.v2.preview

import android.os.SystemClock
import com.github.andreyasadchy.xtra.util.ChatRenderDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.LinkedHashMap

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

sealed interface ChatClipPreviewState {
    data object Missing : ChatClipPreviewState
    data object Loading : ChatClipPreviewState
    data class Ready(val preview: ChatClipPreview?) : ChatClipPreviewState
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
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val successTtlMs: Long = DEFAULT_SUCCESS_TTL_MS,
    private val negativeTtlMs: Long = DEFAULT_NEGATIVE_TTL_MS,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    /** Source-compatible overload for the original `(scope) { loader }` call shape. */
    constructor(
        scope: CoroutineScope,
        loader: suspend (String) -> ChatClipPreview?,
    ) : this(scope, loader, DEFAULT_MAX_ENTRIES, DEFAULT_SUCCESS_TTL_MS, DEFAULT_NEGATIVE_TTL_MS)

    private data class CacheEntry(
        val preview: ChatClipPreview?,
        val cachedAtMs: Long,
        val retryAfterMs: Long? = null,
    ) {
        fun isStale(nowMs: Long, successTtlMs: Long, negativeTtlMs: Long): Boolean =
            nowMs - cachedAtMs >= if (preview == null) negativeTtlMs else successTtlMs

        fun refreshDue(nowMs: Long, successTtlMs: Long, negativeTtlMs: Long): Boolean =
            isStale(nowMs, successTtlMs, negativeTtlMs) &&
                (retryAfterMs == null || nowMs >= retryAfterMs)

        fun shouldPrune(nowMs: Long, successTtlMs: Long, negativeTtlMs: Long): Boolean =
            if (preview == null) {
                isStale(nowMs, successTtlMs, negativeTtlMs)
            } else {
                nowMs - cachedAtMs >= successTtlMs.coerceAtLeast(1L) * 2
            }
    }

    /** Visible rows are normally a small fraction of the 600-message timeline. */
    private val cache = object : LinkedHashMap<String, CacheEntry>(maxEntries.coerceAtLeast(1), .75f, true) {}
    private val listeners = HashMap<String, MutableSet<() -> Unit>>()
    private val loadJobs = HashMap<String, Job>()

    @Synchronized
    fun peek(slug: String): ChatClipPreview? = cache[slug]?.preview

    @Synchronized
    fun peekState(slug: String): ChatClipPreviewState = when {
        cache.containsKey(slug) -> ChatClipPreviewState.Ready(cache[slug]?.preview)
        loadJobs[slug]?.isActive == true -> ChatClipPreviewState.Loading
        else -> ChatClipPreviewState.Missing
    }

    @Synchronized internal fun cacheSize(): Int = cache.size
    @Synchronized internal fun listenerCount(slug: String): Int = listeners[slug]?.size ?: 0
    @Synchronized internal fun inFlightCount(): Int = loadJobs.count { it.value.isActive }

    fun observe(slug: String, listener: () -> Unit) {
        var notifyCached = false
        var refresh = false
        var jobToStart: Job? = null
        synchronized(this) {
            pruneExpiredUnobservedLocked(nowMs())
            val slugListeners = listeners.getOrPut(slug) { LinkedHashSet() }
            slugListeners.add(listener)
            val entry = cache[slug]
            if (entry != null) {
                val now = nowMs()
                val stale = entry.isStale(now, successTtlMs, negativeTtlMs)
                val negative = entry.preview == null
                ChatRenderDiagnostics.recordClipCacheHit(stale, negative)
                notifyCached = true
                refresh = entry.refreshDue(now, successTtlMs, negativeTtlMs)
            } else {
                ChatRenderDiagnostics.recordClipCacheMiss()
                refresh = true
            }
            if (refresh && loadJobs[slug] == null) {
                jobToStart = createLoadJobLocked(slug, cache.containsKey(slug))
            }
        }
        if (notifyCached) listener()
        jobToStart?.let { job ->
            if (!job.start()) {
                synchronized(this) {
                    if (loadJobs[slug] === job) loadJobs.remove(slug)
                }
            }
        }
    }

    @Synchronized
    fun removeObserver(slug: String, listener: () -> Unit) {
        listeners[slug]?.remove(listener)
        if (listeners[slug].isNullOrEmpty()) listeners.remove(slug)
        trimCacheLocked()
    }

    private fun createLoadJobLocked(slug: String, refreshing: Boolean): Job =
        scope.launch(start = CoroutineStart.LAZY) {
            val currentJob = coroutineContext[Job]
            try {
                if (refreshing) ChatRenderDiagnostics.recordClipRefresh()
                val preview = try {
                    loader(slug)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    null
                }
                val callbacks = synchronized(this@ChatClipPreviewRepository) {
                    if (loadJobs[slug] !== currentJob) {
                        emptyList()
                    } else {
                        loadJobs.remove(slug)
                        val previous = cache[slug]
                        val now = nowMs()
                        val next = if (refreshing && previous?.preview != null && preview == null) {
                            // A failed refresh must not blank a preview that was already visible.
                            previous.copy(retryAfterMs = now + negativeTtlMs)
                        } else {
                            CacheEntry(preview, now)
                        }
                        val changed = previous == null || previous.preview != next.preview
                        cache[slug] = next
                        trimCacheLocked()
                        if (changed) {
                            if (refreshing) ChatRenderDiagnostics.recordClipRefreshChanged()
                            listeners[slug]?.toList().orEmpty()
                        } else {
                            if (refreshing) ChatRenderDiagnostics.recordClipRefreshUnchanged()
                            emptyList()
                        }
                    }
                }
                callbacks.forEach { it() }
            } finally {
                synchronized(this@ChatClipPreviewRepository) {
                    if (loadJobs[slug] === currentJob) loadJobs.remove(slug)
                }
            }
        }.also { loadJobs[slug] = it }

    private fun trimCacheLocked() {
        pruneExpiredUnobservedLocked(nowMs())
        val limit = maxEntries.coerceAtLeast(1)
        while (cache.size > limit) {
            val eldest = cache.entries.iterator().next()
            cache.remove(eldest.key)
            ChatRenderDiagnostics.recordClipEviction()
        }
    }

    private fun pruneExpiredUnobservedLocked(nowMs: Long) {
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (listeners[entry.key].isNullOrEmpty() && loadJobs[entry.key] == null &&
                entry.value.shouldPrune(nowMs, successTtlMs, negativeTtlMs)
            ) {
                iterator.remove()
                ChatRenderDiagnostics.recordClipEviction()
            }
        }
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 192
        const val DEFAULT_SUCCESS_TTL_MS = 6 * 60 * 60 * 1_000L
        const val DEFAULT_NEGATIVE_TTL_MS = 30_000L
    }
}
