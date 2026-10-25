package com.github.andreyasadchy.xtra.util.coil

import coil3.disk.DiskCache
import okio.FileSystem
import okio.Path

/**
 * Keeps emote images in their own disk cache so thumbnails and avatars can never push them out.
 * Emote entries are recognised by their CDN host in the cache key (the URL).
 */
class EmoteRoutingDiskCache(
    private val emotes: DiskCache,
    private val other: DiskCache,
) : DiskCache {
    private fun route(key: String): DiskCache = if (EmoteHosts.isEmoteUrl(key)) emotes else other

    override val size: Long get() = emotes.size + other.size
    override val maxSize: Long get() = emotes.maxSize + other.maxSize
    override val directory: Path get() = emotes.directory
    override val fileSystem: FileSystem get() = emotes.fileSystem

    override fun openSnapshot(key: String): DiskCache.Snapshot? = route(key).openSnapshot(key)
    override fun openEditor(key: String): DiskCache.Editor? = route(key).openEditor(key)
    override fun remove(key: String): Boolean = route(key).remove(key)

    override fun clear() {
        emotes.clear()
        other.clear()
    }

    /** Coil trims over-limit entries after writes; clear old entries eagerly after a cap change. */
    internal fun clearOversizedCaches() {
        if (emotes.size > emotes.maxSize) emotes.clear()
        if (other.size > other.maxSize) other.clear()
    }

    override fun shutdown() {
        emotes.shutdown()
        other.shutdown()
    }
}
