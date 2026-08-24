package com.github.andreyasadchy.xtra.repository.preload

import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamMedia3RuntimeLifecycleTest {
    @Test
    fun backgroundCleanupPreservesManagerOwnedByPrimaryPlayback() {
        assertFalse(shouldResetPreloadManager(hasPrimaryPlaybackPlayer = true))
    }

    @Test
    fun browsingCleanupCanResetManagerWithoutPrimaryPlayback() {
        assertTrue(shouldResetPreloadManager(hasPrimaryPlaybackPlayer = false))
    }

    @Test
    fun currentBrowsingGenerationSurvivesPlaybackServiceRelease() {
        assertFalse(
            shouldReleasePreloadGeneration(
                isCurrentGeneration = true,
                wasPrimaryPlaybackGeneration = false,
            ),
        )
        assertTrue(
            shouldReleasePreloadGeneration(
                isCurrentGeneration = true,
                wasPrimaryPlaybackGeneration = true,
            ),
        )
        assertTrue(
            shouldReleasePreloadGeneration(
                isCurrentGeneration = false,
                wasPrimaryPlaybackGeneration = false,
            ),
        )
    }

    @Test
    fun primaryPlaybackOwnershipFollowsTheInstalledMediaItem() {
        val ownership = StreamMedia3PlaybackOwnership()
        val first = MediaItem.Builder().setMediaId("first").build()
        val second = MediaItem.Builder().setMediaId("second").build()

        assertTrue(ownership.setPrimaryMediaItem(first))

        assertTrue(ownership.protects(first))
        assertFalse(ownership.protects(second))

        assertFalse(ownership.setPrimaryMediaItem(first))
        assertTrue(ownership.setPrimaryMediaItem(second))

        assertFalse(ownership.protects(first))
        assertTrue(ownership.protects(second))

        ownership.setPrimaryMediaItem(null)

        assertFalse(ownership.protects(second))
    }

    @Test
    fun protectedReplacementWaitsUntilTheOldPrimarySourceIsReleased() {
        val ownership = StreamMedia3PlaybackOwnership()
        val oldSource = MediaItem.Builder().setMediaId("old").build()
        val replacement = MediaItem.Builder().setMediaId("replacement").build()
        val entries = StreamMedia3PreloadEntries<MediaItem>().apply {
            this["channel"] = oldSource
        }
        val plan = StreamMediaPreloadPlan.reconcile(
            existing = listOf(MediaPreloadPlanEntry("channel", "old-url", rank = 0)),
            candidates = listOf(MediaPreloadPlanEntry("channel", "new-url", rank = 1)),
            nowMs = 100L,
            staleAfterMs = 4_500L,
        )

        assertEquals(listOf("old-url"), plan.removed.map { it.url })
        assertEquals(listOf("new-url"), plan.added.map { it.url })

        ownership.setPrimaryMediaItem(oldSource)

        assertFalse(
            entries.replaceUnlessProtected("channel", replacement) {
                shouldDeferProtectedPreloadReplacement(it, ownership)
            }
        )
        assertTrue(entries["channel"] === oldSource)

        ownership.setPrimaryMediaItem(null)

        assertTrue(
            entries.replaceUnlessProtected("channel", replacement) {
                shouldDeferProtectedPreloadReplacement(it, ownership)
            }
        )
        assertTrue(entries["channel"] === replacement)
    }

    @Test
    fun switchingBetweenPreloadedSourcesMakesThePreviousSourceEvictable() {
        val ownership = StreamMedia3PlaybackOwnership()
        val first = MediaItem.Builder().setMediaId("first").build()
        val second = MediaItem.Builder().setMediaId("second").build()

        ownership.setPrimaryMediaItem(first)
        ownership.setPrimaryMediaItem(second)

        assertFalse(ownership.protects(first))
        assertTrue(ownership.protects(second))
    }
}
