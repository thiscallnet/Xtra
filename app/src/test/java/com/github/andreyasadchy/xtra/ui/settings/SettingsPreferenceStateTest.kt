package com.github.andreyasadchy.xtra.ui.settings

import com.github.andreyasadchy.xtra.model.ui.SettingsDragListItem
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsPreferenceStateTest {

    @Test
    fun `speed option serialization preserves order and disabled entries`() {
        val items = listOf(
            SettingsDragListItem("2.0", "2.0×", default = false, enabled = false),
            SettingsDragListItem("0.5", "0.5×", default = false, enabled = true),
            SettingsDragListItem("1.25", "1.25×", default = false, enabled = true),
            SettingsDragListItem("1.5", "1.5×", default = false, enabled = true),
        )

        assertEquals("2.0:0,0.5:1,1.25:1,1.5:1", serializeSpeedOptions(items))
    }
}
