package com.github.andreyasadchy.xtra.repository.preload

/** Keeps a protected manager entry addressable until primary playback releases it. */
internal class StreamMedia3PreloadEntries<T> {
    private val entries = linkedMapOf<String, T>()

    val values: Collection<T>
        get() = entries.values

    operator fun get(channelLogin: String): T? = entries[channelLogin]

    operator fun set(channelLogin: String, entry: T) {
        entries[channelLogin] = entry
    }

    fun remove(channelLogin: String): T? = entries.remove(channelLogin)

    fun clear() {
        entries.clear()
    }

    fun replaceUnlessProtected(
        channelLogin: String,
        replacement: T,
        isProtected: (T) -> Boolean,
    ): Boolean {
        if (entries[channelLogin]?.let(isProtected) == true) return false
        entries[channelLogin] = replacement
        return true
    }
}
