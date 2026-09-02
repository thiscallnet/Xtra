package com.github.andreyasadchy.xtra.ui.settings

import com.github.andreyasadchy.xtra.model.ui.SettingsDragListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsActivityTest {

    @Test
    fun healthyUpdateNotificationsNeedNoUserAction() {
        assertFalse(
            needsUpdateNotificationUserAction(
                permissionMissing = false,
                notificationsBlocked = false,
                updatesChannelBlocked = false,
            ),
        )
    }

    @Test
    fun eachBlockedUpdateNotificationPrerequisiteNeedsUserAction() {
        assertTrue(needsUpdateNotificationUserAction(true, false, false))
        assertTrue(needsUpdateNotificationUserAction(false, true, false))
        assertTrue(needsUpdateNotificationUserAction(false, false, true))
    }

    @Test
    fun hidingTheDefaultPromotesTheFirstVisibleItem() {
        val items = listOf(
            SettingsDragListItem("first", "First", default = true, enabled = false),
            SettingsDragListItem("second", "Second", default = false, enabled = true),
        )

        promoteDefaultToVisible(items)

        assertFalse(items[0].default)
        assertTrue(items[1].default)
    }

    @Test
    fun settingAHiddenItemAsDefaultMakesItVisible() {
        val items = listOf(
            SettingsDragListItem("first", "First", default = true, enabled = true),
            SettingsDragListItem("second", "Second", default = false, enabled = false),
        )

        setDefaultItem(items, items[1])

        assertSame(items[1], items.first { it.default })
        assertTrue(items[1].enabled)
    }

    @Test
    fun theFinalVisiblePageTabCannotBeDisabled() {
        assertFalse(canDisableVisibleItem(itemEnabled = true, visibleItemCount = 1, minimumVisibleItems = 1))
        assertTrue(canDisableVisibleItem(itemEnabled = true, visibleItemCount = 2, minimumVisibleItems = 1))
    }

    @Test
    fun anEmptyPageTabConfigurationGetsOneVisibleItemBack() {
        val items = listOf(
            SettingsDragListItem("first", "First", default = false, enabled = false),
            SettingsDragListItem("second", "Second", default = false, enabled = false),
        )

        ensureMinimumVisibleItems(items, minimumVisibleItems = 1)

        assertEquals(1, items.count { it.enabled })
    }

    @Test
    fun navigationLimitKeepsConfiguredItemsInOrder() {
        val items = listOf(
            "0:0:1",
            "4:0:1",
            "1:1:1",
            "2:0:1",
            "3:0:1",
            "5:0:1",
            "6:0:1",
        )

        val limited = limitNavigationVisibleItems(items)

        assertEquals(6, limited.count { it.endsWith(":1") })
        assertTrue(limited.first { it.startsWith("4:") }.endsWith(":1"))
        assertTrue(limited.last { it.startsWith("6:") }.endsWith(":0"))
    }

    @Test
    fun navigationResolutionRestoresMissingDropsWithoutChangingSavedTabs() {
        val resolved = resolveNavigationTabList(
            "0:0:1,1:1:1,2:0:1,3:0:1,5:0:1",
            isTelevision = false,
        )

        assertEquals(listOf("0", "1", "2", "3", "5", "4", "6"), resolved.map { it.substringBefore(':') })
        assertEquals("6:0:1", resolved.last())
    }

    @Test
    fun navigationResolutionDropsMalformedAndDuplicateEntries() {
        val resolved = resolveNavigationTabList(
            "6:0:0,6:1:1,bad,0:0:2",
            isTelevision = false,
        )

        assertEquals("6:0:0", resolved.first())
        assertEquals(1, resolved.count { it.startsWith("6:") })
        assertTrue(resolved.none { it.contains("bad") || it.endsWith(":2") })
    }

    @Test
    fun legacyMaxedNavigationLayoutGivesDiscoverSlotToDrops() {
        val resolved = resolveNavigationTabList(
            "0:0:1,4:0:1,1:1:1,2:0:1,3:0:1,5:0:1",
            isTelevision = false,
        )

        assertEquals(6, resolved.count { it.endsWith(":1") })
        assertEquals("4:0:0", resolved.first { it.startsWith("4:") })
        assertEquals("6:0:1", resolved.first { it.startsWith("6:") })
    }

    @Test
    fun legacyMaxedNavigationLayoutMovesDefaultFromDiscoverToDrops() {
        val resolved = resolveNavigationTabList(
            "0:0:1,4:1:1,1:0:1,2:0:1,3:0:1,5:0:1",
            isTelevision = false,
        )

        assertEquals("4:0:0", resolved.first { it.startsWith("4:") })
        assertEquals("6:1:1", resolved.first { it.startsWith("6:") })
        assertEquals(listOf("6"), resolved.filter { it.split(':')[1] == "1" }.map { it.substringBefore(':') })
    }
}
