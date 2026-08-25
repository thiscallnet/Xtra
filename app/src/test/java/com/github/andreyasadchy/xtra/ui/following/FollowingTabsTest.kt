package com.github.andreyasadchy.xtra.ui.following

import com.github.andreyasadchy.xtra.ui.following.overview.FollowingOverviewSections
import com.github.andreyasadchy.xtra.util.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowingTabsTest {

    @Test
    fun legacyOverviewIsRemovedAndChannelsAreRetainedDuringMigration() {
        val migrated = requireNotNull(
            FollowingTabs.migrateStoredPreference("4:1:1,1:1:1,0:0:1,2:0:1,3:0:1"),
        )

        assertTrue(migrated.contains("1:1:1"))
        assertTrue(!migrated.contains("4:"))
        assertTrue(migrated.contains("3:0:1"))
    }

    @Test
    fun channelsAreVisibleInTheDefaultFollowingTabs() {
        val visibleKeys = FollowingTabs.visibleKeys(C.DEFAULT_FOLLOWING_TABS, showVideos = true)

        assertTrue(visibleKeys.contains("3"))
    }

    @Test
    fun requestedFollowingTabOverridesConfiguredDefault() {
        val entries = FollowingTabs.resolve(C.DEFAULT_FOLLOWING_TABS)
        val visibleKeys = FollowingTabs.visibleKeys(entries, showVideos = true)

        assertEquals("2", FollowingTabs.preferredTabKey(entries, visibleKeys, requestedKey = "2"))
        assertEquals("1", FollowingTabs.preferredTabKey(entries, visibleKeys, requestedKey = "unknown"))
    }

    @Test
    fun unavailableConfiguredVideosFallsBackToLiveBeforeCategories() {
        val entries = listOf("0:0:1", "2:1:1", "1:0:1")
        val visibleKeys = FollowingTabs.visibleKeys(entries, showVideos = false)

        assertEquals("1", FollowingTabs.preferredTabKey(entries, visibleKeys, requestedKey = null))
    }

    @Test
    fun overviewSectionsRouteToTheirFollowingTabs() {
        assertEquals("1", FollowingOverviewSections.followingTabKey(FollowingOverviewSections.LIVE))
        assertEquals("2", FollowingOverviewSections.followingTabKey(FollowingOverviewSections.CONTINUE))
    }
}
