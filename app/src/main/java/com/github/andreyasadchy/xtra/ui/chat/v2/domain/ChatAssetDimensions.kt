package com.github.andreyasadchy.xtra.ui.chat.v2.domain

import java.util.LinkedHashMap

/** Stable source dimensions used by chat layout and drawing. */
data class ChatAssetDimensions(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0)
    }
}

/**
 * Process-local dimensions learned from decoded assets.
 *
 * Provider metadata is authoritative when available. Decoded dimensions are retained only for
 * assets whose provider did not supply them, and the fallback is deliberately not cached so a
 * later decode can replace it without a second invalidation path.
 */
object ChatAssetDimensionsResolver {
    const val FALLBACK_WIDTH = 56
    const val FALLBACK_HEIGHT = 56

    private const val MAX_ENTRIES = 512
    private data class Entry(val dimensions: ChatAssetDimensions, val authoritative: Boolean)

    private val entries = LinkedHashMap<ChatAssetKey, Entry>(MAX_ENTRIES, .75f, true)

    @Synchronized
    fun resolve(
        key: ChatAssetKey,
        providerWidth: Int? = null,
        providerHeight: Int? = null,
    ): ChatAssetDimensions {
        val provider = dimensionsOrNull(providerWidth, providerHeight)
        if (provider != null) {
            putLocked(key, provider, authoritative = true)
            return provider
        }
        return entries[key]?.dimensions
            ?: ChatAssetDimensions(FALLBACK_WIDTH, FALLBACK_HEIGHT)
    }

    @Synchronized
    fun peek(key: ChatAssetKey): ChatAssetDimensions? = entries[key]?.dimensions

    /** Records a decoded asset unless provider metadata has already won for this key. */
    @Synchronized
    fun recordDecoded(key: ChatAssetKey, width: Int, height: Int) {
        val dimensions = dimensionsOrNull(width, height) ?: return
        if (entries[key]?.authoritative == true) return
        putLocked(key, dimensions, authoritative = false)
    }

    @Synchronized
    internal fun clearForTests() {
        entries.clear()
    }

    private fun putLocked(key: ChatAssetKey, dimensions: ChatAssetDimensions, authoritative: Boolean) {
        entries[key] = Entry(dimensions, authoritative)
        while (entries.size > MAX_ENTRIES) entries.remove(entries.entries.first().key)
    }

    private fun dimensionsOrNull(width: Int?, height: Int?): ChatAssetDimensions? =
        if (width != null && height != null && width > 0 && height > 0) {
            ChatAssetDimensions(width, height)
        } else {
            null
        }
}

internal fun twitchEmoteAssetKey(id: String): ChatAssetKey = ChatAssetKey(
    "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/3.0",
)

internal fun twitchEmoteAssetSpec(id: String, targetHeight: Int = 28): ChatAssetSpec {
    val key = twitchEmoteAssetKey(id)
    val dimensions = ChatAssetDimensionsResolver.resolve(key)
    return ChatAssetSpec(
        key = key,
        sourceWidth = dimensions.width,
        sourceHeight = dimensions.height,
        targetHeight = targetHeight,
        dimensionsAreAuthoritative = false,
    )
}
