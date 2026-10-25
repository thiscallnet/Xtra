package com.github.andreyasadchy.xtra.util.coil

import coil3.annotation.ExperimentalCoilApi
import coil3.network.CacheStrategy
import coil3.network.CacheStrategy.ReadResult
import coil3.network.CacheStrategy.WriteResult
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.request.Options

/**
 * Emote URLs are keyed by emote id, so the image behind a URL practically never changes (a new
 * look is a new URL, which arrives through the emote catalogs). Servers still send short
 * lifetimes for some of them (Twitch 4h, FFZ 1d), which forces a revalidation round trip per
 * emote on every visit and makes the picker crawl on bad networks. Cached emotes are served
 * straight from disk for [maxAgeMs]; after that the normal conditional request (ETag)
 * checks whether the image really changed.
 */
@OptIn(ExperimentalCoilApi::class)
class EmoteCacheStrategy(
    private val delegate: CacheStrategy = CacheControlCacheStrategy(),
    private val now: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) : CacheStrategy {

    override suspend fun read(
        cacheResponse: NetworkResponse,
        networkRequest: NetworkRequest,
        options: Options,
    ): ReadResult {
        if (cacheResponse.code == 200 &&
            EmoteHosts.isEmoteUrl(networkRequest.url) &&
            networkRequest.headers["If-None-Match"] == null &&
            networkRequest.headers["If-Modified-Since"] == null &&
            now() - cacheResponse.responseMillis in 0 until maxAgeMs
        ) {
            return ReadResult(cacheResponse)
        }
        return delegate.read(cacheResponse, networkRequest, options)
    }

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options,
    ): WriteResult = delegate.write(cacheResponse, networkRequest, networkResponse, options)

    private companion object {
        const val DEFAULT_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }
}

/** The CDNs that serve emote images; their URLs are keyed by emote id. */
internal object EmoteHosts {
    private val HOSTS = setOf(
        "cdn.7tv.app",
        "cdn.betterttv.net",
        "static-cdn.jtvnw.net",
        "cdn.frankerfacez.com",
    )

    fun isEmoteUrl(url: String): Boolean =
        url.substringAfter("://").substringBefore('/').substringBefore(':') in HOSTS
}
