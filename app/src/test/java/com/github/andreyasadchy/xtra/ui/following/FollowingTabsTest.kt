package com.github.andreyasadchy.xtra.ui.following

import org.junit.Assert.assertTrue
import org.junit.Test

class FollowingTabsTest {

    @Test
    fun customLandingTabSurvivesOverviewMigration() {
        val migrated = requireNotNull(
            FollowingTabs.migrateStoredPreference("1:1:1,0:0:1,2:0:1,3:0:1"),
        )

        assertTrue(migrated.contains("4:0:1"))
        assertTrue(migrated.contains("1:1:1"))
    }
}
