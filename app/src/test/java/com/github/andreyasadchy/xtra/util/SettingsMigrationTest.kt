package com.github.andreyasadchy.xtra.util

import android.content.SharedPreferences
import com.github.andreyasadchy.xtra.ui.following.FollowingTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMigrationTest {
    @Test
    fun `always starts on following`() {
        assertEquals("2", SettingsMigration.legacyStartupNavigationTab("0"))
    }

    @Test
    fun `when logged in starts on following`() {
        assertEquals("2", SettingsMigration.legacyStartupNavigationTab("1"))
    }

    @Test
    fun `never preserves existing destination`() {
        assertNull(SettingsMigration.legacyStartupNavigationTab("2"))
    }

    @Test
    fun `background playback migration never re-enables an explicit legacy opt-out`() {
        val possibleValues = listOf<Boolean?>(null, false, true)
        possibleValues.forEach { normal ->
            possibleValues.forEach { locked ->
                possibleValues.forEach { pipClosed ->
                    possibleValues.forEach { pipLocked ->
                        val expected = normal != false &&
                            locked != false &&
                            pipClosed != false &&
                            pipLocked != false
                        assertEquals(
                            "normal=$normal locked=$locked pipClosed=$pipClosed pipLocked=$pipLocked",
                            expected,
                            SettingsMigration.migratedBackgroundPlayback(
                                normal = normal,
                                locked = locked,
                                pipClosed = pipClosed,
                                pipLocked = pipLocked,
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `missing update preference preserves upgrade default and uses fresh install default`() {
        assertFalse(SettingsMigration.migratedUpdateCheckEnabled(existing = null, freshInstall = false))
        assertTrue(SettingsMigration.migratedUpdateCheckEnabled(existing = null, freshInstall = true))
        assertFalse(SettingsMigration.migratedUpdateCheckEnabled(existing = false, freshInstall = true))
        assertTrue(SettingsMigration.migratedUpdateCheckEnabled(existing = true, freshInstall = false))
    }

    @Test
    fun `missing update preference is disabled for an upgrading preference set`() {
        val preferences = MemoryPreferences(mutableMapOf(C.SETTINGS_VERSION to 19))

        SettingsMigration.migratePreferences(preferences, freshInstall = false)

        assertFalse(preferences.getBoolean(C.UPDATE_CHECK_ENABLED, true))
    }

    @Test
    fun `missing update preference is enabled only for an explicit fresh install`() {
        val preferences = MemoryPreferences()

        SettingsMigration.migratePreferences(preferences, freshInstall = true)

        assertTrue(preferences.getBoolean(C.UPDATE_CHECK_ENABLED, false))
    }

    @Test
    fun `a preference set without a schema marker is still treated as an upgrade`() {
        val preferences = MemoryPreferences(mutableMapOf(C.UI_LANGUAGE to "en"))

        SettingsMigration.migratePreferences(preferences)

        assertFalse(preferences.getBoolean(C.UPDATE_CHECK_ENABLED, true))
    }

    @Test
    fun `schema 25 preferences migrate following tabs moved out of following`() {
        val preferences = MemoryPreferences(
            mutableMapOf(
                C.SETTINGS_VERSION to 25,
                C.UI_FOLLOWING_TABS to "4:1:1,1:0:1,2:0:1,0:0:1,3:1:1",
            ),
        )

        SettingsMigration.migratePreferences(preferences, freshInstall = false)

        assertEquals(C.SETTINGS_SCHEMA_VERSION, preferences.getInt(C.SETTINGS_VERSION, 0))
        assertEquals("1:0:1,2:0:1,0:0:1", preferences.getString(C.UI_FOLLOWING_TABS, null))
    }

    @Test
    fun `schema 25 migration keeps moved following content reachable`() {
        val preferences = MemoryPreferences(
            mutableMapOf(
                C.SETTINGS_VERSION to 25,
                C.UI_FOLLOWING_TABS to "4:1:1,1:0:1,2:0:1,0:0:1,3:1:1",
                C.UI_NAVIGATION_TAB_LIST to "0:0:0,1:1:0,2:1:1,3:0:1",
            ),
        )

        SettingsMigration.migratePreferences(preferences, freshInstall = false)

        val navigation = preferences.getString(C.UI_NAVIGATION_TAB_LIST, null).orEmpty()
        fun navigationEnabled(key: String): Boolean = navigation
            .split(',')
            .first { it.substringBefore(':') == key }
            .split(':')[2] != "0"

        assertTrue(navigationEnabled("0"))
        assertTrue(navigationEnabled("1"))
        assertTrue(navigationEnabled("2"))

        val followingTabs = preferences.getString(C.UI_FOLLOWING_TABS, null)
        assertTrue(FollowingTabs.visibleKeys(followingTabs, showVideos = false).contains("1"))
    }

    @Test
    fun `schema 25 migration supplies live when the default navigation keeps following enabled`() {
        val preferences = MemoryPreferences(
            mutableMapOf(
                C.SETTINGS_VERSION to 25,
                C.UI_FOLLOWING_TABS to "4:0:0,1:0:0,2:0:0,0:0:0,3:0:0",
            ),
        )

        SettingsMigration.migratePreferences(preferences, freshInstall = false)

        val followingTabs = preferences.getString(C.UI_FOLLOWING_TABS, null)
        assertTrue(FollowingTabs.visibleKeys(followingTabs, showVideos = false).contains("1"))
    }

    @Test
    fun `schema 25 migration uses the old following default when the preference is absent`() {
        val preferences = MemoryPreferences(
            mutableMapOf(
                C.SETTINGS_VERSION to 25,
                C.UI_NAVIGATION_TAB_LIST to "0:0:1,1:0:0,2:0:1,3:0:1",
            ),
        )

        SettingsMigration.migratePreferences(preferences, freshInstall = false)

        val navigation = preferences.getString(C.UI_NAVIGATION_TAB_LIST, null).orEmpty()
        assertTrue(navigation.split(',').first { it.substringBefore(':') == "1" }.split(':')[2] != "0")
    }

    @Test
    fun `redesigned target values remain stable when migration is applied again`() {
        val density = SettingsMigration.migratedDensity(null, reducedPadding = true, compactText = false)
        val profile = SettingsMigration.migratedProfilePictureStyle(null, roundUserImage = false)
        val timestamp = SettingsMigration.migratedTimestampFormat("4", alreadyRedesigned = false)

        assertEquals("compact", SettingsMigration.migratedDensity(density, false, false))
        assertEquals("rounded_square", SettingsMigration.migratedProfilePictureStyle(profile, true))
        assertEquals(timestamp, SettingsMigration.migratedTimestampFormat(timestamp, alreadyRedesigned = true))
    }

    @Test
    fun `current timestamp values are not remapped by a stale migration`() {
        assertEquals("0", SettingsMigration.migratedTimestampFormat("0", alreadyRedesigned = true))
        assertEquals("1", SettingsMigration.migratedTimestampFormat("1", alreadyRedesigned = true))
        assertEquals("2", SettingsMigration.migratedTimestampFormat("2", alreadyRedesigned = true))
        assertEquals("3", SettingsMigration.migratedTimestampFormat("3", alreadyRedesigned = true))
    }

    @Test
    fun `timestamp marker keeps current format values stable with a stale global schema`() {
        val preferences = MemoryPreferences(
            mutableMapOf(
                C.SETTINGS_VERSION to 19,
                C.CHAT_TIMESTAMP_FORMAT to "3",
                C.SETTINGS_TIMESTAMP_FORMAT_VERSION to 1,
            ),
        )

        SettingsMigration.migratePreferences(preferences, freshInstall = false)

        assertEquals("3", preferences.getString(C.CHAT_TIMESTAMP_FORMAT, null))
        assertEquals(1, preferences.getInt(C.SETTINGS_TIMESTAMP_FORMAT_VERSION, 0))
    }

    @Test
    fun `migration is idempotent for legacy preferences`() {
        val preferences = MemoryPreferences(
            mutableMapOf(
                C.SETTINGS_VERSION to 19,
                C.UI_THEME_REDUCED_PADDING to true,
                C.UI_ROUND_USER_IMAGE to false,
                C.CHAT_TIMESTAMP_FORMAT to "4",
                C.PLAYER_BACKGROUND_AUDIO to false,
                C.PLAYER_BACKGROUND_AUDIO_LOCKED to true,
            ),
        )

        SettingsMigration.migratePreferences(preferences, freshInstall = false)
        val firstState = preferences.getAll()
        SettingsMigration.migratePreferences(preferences, freshInstall = false)

        assertEquals(firstState, preferences.getAll())
        assertEquals(false, preferences.getBoolean(C.SETTINGS_BACKGROUND_PLAYBACK, true))
        assertEquals(false, preferences.getBoolean(C.UPDATE_CHECK_ENABLED, true))
        assertEquals("2", preferences.getString(C.CHAT_TIMESTAMP_FORMAT, null))
    }

    @Test
    fun `stale schema preserves already current redesigned values`() {
        val preferences = MemoryPreferences(
            mutableMapOf(
                C.SETTINGS_VERSION to 19,
                C.SETTINGS_THEME_MODE to "dark",
                C.SETTINGS_DENSITY to "comfortable",
                C.SETTINGS_PROFILE_PICTURE_STYLE to "rounded_square",
                C.SETTINGS_CHAT_ENABLED to true,
                C.SETTINGS_BACKGROUND_PLAYBACK to true,
                C.SETTINGS_PLAYER_CONTROL_LAYOUT to SettingsMigration.defaultControlLayout(),
                C.SETTINGS_PLAYER_SPEED_OPTIONS to "0.5:1,1.0:0,2.0:1",
                C.CHAT_TIMESTAMP_FORMAT to "3",
                C.SETTINGS_TIMESTAMP_FORMAT_VERSION to 1,
            ),
        )

        SettingsMigration.migratePreferences(preferences, freshInstall = false)

        assertEquals("comfortable", preferences.getString(C.SETTINGS_DENSITY, null))
        assertEquals("rounded_square", preferences.getString(C.SETTINGS_PROFILE_PICTURE_STYLE, null))
        assertEquals("3", preferences.getString(C.CHAT_TIMESTAMP_FORMAT, null))
        assertEquals(1, preferences.getInt(C.SETTINGS_TIMESTAMP_FORMAT_VERSION, 0))
    }

    @Test
    fun `fresh control layout preserves production visibility defaults`() {
        val layout = SettingsMigration.defaultControlLayout()

        assertEquals("quick", layout.substringAfter("minimize:").substringBefore(','))
        assertEquals("menu", layout.substringAfter("download:").substringBefore(','))
        assertEquals("quick", layout.substringAfter("quality:").substringBefore(','))
        assertEquals("hidden", layout.substringAfter("follow:").substringBefore(','))
    }

    @Test
    fun `legacy control layout restores the clip action when it was added later`() {
        val preferences = MemoryPreferences(
            mutableMapOf(
                C.SETTINGS_VERSION to C.SETTINGS_SCHEMA_VERSION - 1,
                C.SETTINGS_PLAYER_CONTROL_LAYOUT to "minimize:quick,download:menu",
            ),
        )

        SettingsMigration.migratePreferences(preferences, freshInstall = false)

        val layout = preferences.getString(C.SETTINGS_PLAYER_CONTROL_LAYOUT, null).orEmpty()
        assertTrue(layout.split(',').contains("clip:quick"))
        assertTrue(preferences.getBoolean(C.PLAYER_CLIP_BUTTON, false))
    }

    @Test
    fun `control group prefers quick controls when both legacy locations are enabled`() {
        assertEquals("quick", SettingsMigration.controlGroup(quickEnabled = true, menuEnabled = true))
        assertEquals("menu", SettingsMigration.controlGroup(quickEnabled = false, menuEnabled = true))
        assertEquals("hidden", SettingsMigration.controlGroup(quickEnabled = false, menuEnabled = false))
    }

    @Test
    fun `empty migrated control layout is repaired to production defaults`() {
        assertEquals(
            SettingsMigration.defaultControlLayout(),
            SettingsMigration.migratedControlLayout(
                existing = "",
                legacyControlsAllDisabled = true,
                legacyLayout = "minimize:hidden",
            ),
        )
    }

    @Test
    fun `an existing all-hidden control layout is not overwritten`() {
        val allHidden = "minimize:hidden,download:hidden,clip:hidden"

        assertEquals(
            allHidden,
            SettingsMigration.migratedControlLayout(
                existing = allHidden,
                legacyControlsAllDisabled = true,
                legacyLayout = SettingsMigration.defaultControlLayout(),
            ),
        )
    }

    @Test
    fun `reset allowlist excludes account credentials and app data`() {
        assertTrue(SettingsMigration.RESETTABLE_PREFERENCE_KEYS.contains(C.SETTINGS_THEME_MODE))
        assertTrue(SettingsMigration.RESETTABLE_PREFERENCE_KEYS.contains(C.SETTINGS_DEVELOPER_ENABLED))
        assertTrue(
            SettingsMigration.RESETTABLE_PREFERENCE_KEYS.contains(
                C.UPDATE_NOTIFICATION_PERMISSION_PROMPT_SHOWN,
            ),
        )
        assertFalse(SettingsMigration.RESETTABLE_PREFERENCE_KEYS.contains(C.TOKEN))
        assertFalse(SettingsMigration.RESETTABLE_PREFERENCE_KEYS.contains(C.GQL_HEADERS))
        assertFalse(SettingsMigration.RESETTABLE_PREFERENCE_KEYS.contains(C.GQL_TOKEN2))
        assertFalse(SettingsMigration.RESETTABLE_PREFERENCE_KEYS.contains(C.USER_ID))
        assertFalse(SettingsMigration.RESETTABLE_PREFERENCE_KEYS.contains(C.USERNAME))
    }
}

private class MemoryPreferences(
    initialValues: MutableMap<String, Any> = mutableMapOf(),
) : SharedPreferences {
    private val values = initialValues.toMutableMap()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            put(key, values?.toMutableSet())

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) changes[key] = null
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() = applyChanges()

        private fun <T> put(key: String?, value: T): SharedPreferences.Editor {
            if (key != null) changes[key] = value
            return this
        }

        private fun applyChanges() {
            if (clear) values.clear()
            changes.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
        }
    }
}
