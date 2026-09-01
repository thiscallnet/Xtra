package com.github.andreyasadchy.xtra.ui.tv

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvPlayerCommandsTest {
    @Test fun hiddenLeftSeeksBack() = assertEquals(TvPlayerCommand.SeekBack, tvPlayerCommand(KeyEvent.KEYCODE_DPAD_LEFT, false))
    @Test fun hiddenRightSeeksForward() = assertEquals(TvPlayerCommand.SeekForward, tvPlayerCommand(KeyEvent.KEYCODE_DPAD_RIGHT, false))
    @Test fun hiddenCenterShowsControls() = assertEquals(TvPlayerCommand.ShowControls, tvPlayerCommand(KeyEvent.KEYCODE_DPAD_CENTER, false))
    @Test fun visibleCenterIsPassedToFocusedControl() = assertNull(tvPlayerCommand(KeyEvent.KEYCODE_DPAD_CENTER, true))
}
