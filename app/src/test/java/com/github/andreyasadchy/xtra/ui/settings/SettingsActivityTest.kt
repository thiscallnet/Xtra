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
}
