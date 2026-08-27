package com.github.andreyasadchy.xtra.ui.common

import com.github.andreyasadchy.xtra.model.ui.Stream
import coil3.decode.DataSource
import coil3.request.CachePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailImageRequestTest {

    @Test
    fun previewBucketsReuseDecodedMemoryAndDiskEntriesButFreshNetworkUrls() {
        val stream = Stream(
            channelId = "channel-42",
            thumbnailURL = "https://static-cdn.jtvnw.net/previews/{width}x{height}.jpg",
        )

        val first = streamThumbnailRequestPlan(stream, bucket = 10L)
        val second = streamThumbnailRequestPlan(stream, bucket = 11L)

        assertEquals(first!!.diskCacheKey, second!!.diskCacheKey)
        assertEquals(first.memoryCacheKey, second.memoryCacheKey)
        assertNotEquals(first.networkUrl, second.networkUrl)
        assertFalse(first.diskCacheKey.contains("https://"))
    }

    @Test
    fun broadcastSessionIdentityDoesNotReusePreviousChannelThumbnail() {
        val first = streamThumbnailRequestPlan(
            Stream(
                id = "broadcast-1",
                channelId = "channel-42",
                createdAt = "2026-08-16T10:00:00Z",
                thumbnailURL = "https://static-cdn.jtvnw.net/previews/{width}x{height}.jpg",
            ),
            bucket = 10L,
        )
        val second = streamThumbnailRequestPlan(
            Stream(
                id = "broadcast-2",
                channelId = "channel-42",
                createdAt = "2026-08-16T11:00:00Z",
                thumbnailURL = "https://static-cdn.jtvnw.net/previews/{width}x{height}.jpg",
            ),
            bucket = 10L,
        )

        assertNotEquals(first!!.diskCacheKey, second!!.diskCacheKey)
    }

    @Test
    fun loginAndCreatedAtProvideSessionIdentityWhenStreamIdIsMissing() {
        val first = streamThumbnailRequestPlan(
            Stream(
                channelId = "channel-42",
                channelLogin = "Streamer",
                createdAt = "2026-08-16T10:00:00Z",
                thumbnailURL = "https://cdn.example/preview.jpg",
            ),
            bucket = 10L,
        )
        val second = streamThumbnailRequestPlan(
            Stream(
                channelId = "channel-42",
                channelLogin = "Streamer",
                createdAt = "2026-08-16T11:00:00Z",
                thumbnailURL = "https://cdn.example/preview.jpg",
            ),
            bucket = 10L,
        )

        assertNotEquals(first!!.diskCacheKey, second!!.diskCacheKey)
    }

    @Test
    fun staleStageCannotPoisonFreshMemoryRead() {
        val stale = thumbnailCachePolicies(fresh = false)
        val fresh = thumbnailCachePolicies(fresh = true)

        assertEquals(CachePolicy.READ_ONLY, stale.memory)
        assertEquals(CachePolicy.READ_ONLY, stale.disk)
        assertEquals(CachePolicy.DISABLED, stale.network)
        assertEquals(CachePolicy.WRITE_ONLY, fresh.memory)
        assertEquals(CachePolicy.WRITE_ONLY, fresh.disk)
        assertEquals(CachePolicy.ENABLED, fresh.network)
    }

    @Test
    fun currentBucketMemoryHitSkipsRepeatedFreshRequestUnlessForced() {
        assertEquals(false, shouldRefreshThumbnailAfterCache(DataSource.MEMORY_CACHE, forceRefresh = false))
        assertEquals(true, shouldRefreshThumbnailAfterCache(DataSource.MEMORY_CACHE, forceRefresh = true))
        assertEquals(true, shouldRefreshThumbnailAfterCache(DataSource.DISK, forceRefresh = false))
        assertEquals(true, shouldRefreshThumbnailAfterCache(null, forceRefresh = false))
    }

    @Test
    fun fetchGateRateLimitsRepeatedBindsWithinOneBucket() {
        val gate = StreamThumbnailFetchGate(retryIntervalMs = 100L)

        assertTrue(gate.shouldFetch("session", bucket = 7L, forceEpoch = 0L, nowMs = 0L))
        gate.markAttempt("session", bucket = 7L, forceEpoch = 0L, nowMs = 0L)
        assertFalse(gate.shouldFetch("session", bucket = 7L, forceEpoch = 0L, nowMs = 99L))
        assertTrue(gate.shouldFetch("session", bucket = 7L, forceEpoch = 0L, nowMs = 100L))

        gate.markSuccess("session", bucket = 7L, forceEpoch = 0L)
        assertFalse(gate.shouldFetch("session", bucket = 7L, forceEpoch = 0L, nowMs = 101L))
    }

    @Test
    fun forceEpochRequestsFreshThumbnailEvenInsideCurrentBucket() {
        val gate = StreamThumbnailFetchGate()
        gate.markSuccess("session", bucket = 7L, forceEpoch = 0L)

        assertFalse(gate.forcePending("session", bucket = 7L, forceEpoch = 0L))
        assertTrue(gate.forcePending("session", bucket = 7L, forceEpoch = 1L))

        gate.markSuccess("session", bucket = 7L, forceEpoch = 1L)
        assertFalse(gate.forcePending("session", bucket = 7L, forceEpoch = 1L))
    }

    @Test
    fun freshFailurePreservesOnlyTheMatchingDisplayedThumbnail() {
        assertTrue(
            shouldPreserveThumbnailOnFreshFailure(
                identityMatches = true,
                preserveCurrentImage = true,
                hasDisplayedImage = true,
            ),
        )
        assertFalse(
            shouldPreserveThumbnailOnFreshFailure(
                identityMatches = true,
                preserveCurrentImage = false,
                hasDisplayedImage = true,
            ),
        )
        assertFalse(
            shouldPreserveThumbnailOnFreshFailure(
                identityMatches = false,
                preserveCurrentImage = true,
                hasDisplayedImage = true,
            ),
        )
    }

    @Test
    fun thumbnailRefreshBucketsAreDeterministicAndNamedByPolicy() {
        assertEquals(0L, StreamThumbnailPolicy.bucket(0L))
        assertEquals(1L, StreamThumbnailPolicy.bucket(StreamThumbnailPolicy.REFRESH_INTERVAL_MS))
        assertEquals(
            1L,
            StreamThumbnailPolicy.bucket(StreamThumbnailPolicy.REFRESH_INTERVAL_MS + 1L),
        )
    }

    @Test
    fun refreshGenerationDoesNotMakeIdenticalStreamMetadataChangedForDiffUtil() {
        val oldItem = Stream(
            id = "broadcast-1",
            channelId = "channel-42",
            title = "Same title",
            thumbnailURL = "https://cdn.example/preview.jpg",
            thumbnailGeneration = 10L,
        )
        val newItem = Stream(
            id = "broadcast-1",
            channelId = "channel-42",
            title = "Same title",
            thumbnailURL = "https://cdn.example/preview.jpg",
            thumbnailGeneration = 11L,
        )

        assertEquals(oldItem.streamIdentity(), newItem.streamIdentity())
        org.junit.Assert.assertTrue(streamContentsSame(oldItem, newItem))
    }

    @Test
    fun missingThumbnailHasNoTwoStageRequest() {
        assertNull(streamThumbnailRequestPlan(Stream(channelId = "channel-42"), bucket = 10L))
    }
}
