package com.github.andreyasadchy.xtra.ui.chat.v2.preview

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class ChatClipPreview(
    val title: String?,
    val broadcasterName: String?,
    val creatorName: String?,
    val thumbnailUrl: String?,
)

data class ChatClipPreviewLink(
    val slug: String,
    val url: String,
)

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
