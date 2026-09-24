package com.github.andreyasadchy.xtra.util.watch

import com.github.andreyasadchy.xtra.model.stats.ViewingPlaybackMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimaryPlaybackWatchStateStoreTest {
    @Test
    fun pauseAndMetadataRefreshPreserveThePlaybackGeneration() {
        val store = PrimaryPlaybackWatchStateStore()
        val owner = store.newOwnerId()
        val generation = store.begin(owner, metadata(), liveEligible = true, isPlaying = true, isBuffering = false)

        val updated = store.update(
            ownerId = owner,
            generation = generation,
            metadata = metadata().copy(categoryName = "New game", title = "Updated title"),
            isPlaying = false,
            isBuffering = false,
            liveEligible = true,
        )

        assertEquals(generation, updated)
        assertFalse(store.state.value!!.isPlaying)
        assertEquals("New game", store.state.value!!.metadata.categoryName)
    }

    @Test
    fun aDifferentBroadcastGetsANewGenerationAndOldOwnerCannotReleaseIt() {
        val store = PrimaryPlaybackWatchStateStore()
        val owner = store.newOwnerId()
        val oldGeneration = store.begin(owner, metadata(), liveEligible = true)
        val newGeneration = store.update(
            ownerId = owner,
            generation = oldGeneration,
            metadata = metadata().copy(contentId = "broadcast-2"),
            isPlaying = true,
            isBuffering = false,
            liveEligible = true,
        )!!

        assertTrue(newGeneration > oldGeneration)
        store.release(owner, oldGeneration)
        assertEquals(newGeneration, store.state.value?.generation)
    }

    @Test
    fun lateUpdateFromOldGenerationCannotChangeReopenedPlayback() {
        val store = PrimaryPlaybackWatchStateStore()
        val owner = store.newOwnerId()
        val oldGeneration = store.begin(owner, metadata(), liveEligible = true)
        val currentGeneration = store.begin(owner, metadata("broadcast-2"), liveEligible = true)

        val lateUpdate = store.update(
            ownerId = owner,
            generation = oldGeneration,
            metadata = metadata(),
            isPlaying = false,
            isBuffering = false,
            liveEligible = true,
        )

        assertNull(lateUpdate)
        assertEquals(currentGeneration, store.state.value?.generation)
        assertEquals("broadcast-2", store.state.value?.metadata?.contentId)
    }

    @Test
    fun releaseOnlyClearsTheCurrentOwner() {
        val store = PrimaryPlaybackWatchStateStore()
        val oldOwner = store.newOwnerId()
        val currentOwner = store.newOwnerId()
        val oldGeneration = store.begin(oldOwner, metadata(), liveEligible = true)
        val currentGeneration = store.begin(currentOwner, metadata("broadcast-2"), liveEligible = true)

        store.release(oldOwner, oldGeneration)
        assertEquals(currentGeneration, store.state.value?.generation)

        store.release(currentOwner, currentGeneration)
        assertNull(store.state.value)
    }

    private fun metadata(contentId: String = "broadcast-1") = ViewingPlaybackMetadata(
        channelId = "channel-1",
        channelLogin = "channel",
        channelName = "Channel",
        channelImage = null,
        contentType = ViewingPlaybackMetadata.CONTENT_TYPE_LIVE,
        contentId = contentId,
    )
}
