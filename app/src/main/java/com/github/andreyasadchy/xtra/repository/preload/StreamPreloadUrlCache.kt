package com.github.andreyasadchy.xtra.repository.preload

import android.os.SystemClock

data class ResolvedStreamPreload(
    val channelLogin: String,
    val uri: String,
    val resolvedAtElapsedMs: Long,
    val configurationFingerprint: String,
)

class StreamPreloadUrlCache(
    private val maxEntries: Int = StreamPreloadPolicy.MAX_URL_CANDIDATES,
    private val ttlMs: Long = StreamPreloadPolicy.URL_TTL_MS,
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val entries = LinkedHashMap<String, ResolvedStreamPreload>(maxEntries, 0.75f, true)
    private var configurationFingerprint: String? = null

    @Synchronized
    fun setConfiguration(fingerprint: String) {
        if (configurationFingerprint != fingerprint) {
            configurationFingerprint = fingerprint
            entries.clear()
        }
    }

    @Synchronized
    fun get(channelLogin: String, fingerprint: String): String? {
        setConfiguration(fingerprint)
        val key = key(channelLogin)
        val entry = entries[key] ?: return null
        if (isExpired(entry) || entry.configurationFingerprint != fingerprint) {
            entries.remove(key)
            return null
        }
        return entry.uri
    }

    @Synchronized
    fun put(channelLogin: String, uri: String, fingerprint: String) {
        setConfiguration(fingerprint)
        val normalizedLogin = key(channelLogin)
        entries[normalizedLogin] = ResolvedStreamPreload(
            channelLogin = normalizedLogin,
            uri = uri,
            resolvedAtElapsedMs = elapsedRealtimeMs(),
            configurationFingerprint = fingerprint,
        )
        while (entries.size > maxEntries) {
            entries.entries.firstOrNull()?.let { entries.remove(it.key) }
        }
    }

    @Synchronized
    fun take(channelLogin: String, fingerprint: String): String? {
        setConfiguration(fingerprint)
        val key = key(channelLogin)
        val entry = entries.remove(key) ?: return null
        if (isExpired(entry) || entry.configurationFingerprint != fingerprint) return null
        return entry.uri
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size

    private fun isExpired(entry: ResolvedStreamPreload): Boolean =
        elapsedRealtimeMs() - entry.resolvedAtElapsedMs > ttlMs

    private fun key(channelLogin: String): String = channelLogin.trim().lowercase()
}
