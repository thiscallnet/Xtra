package com.github.andreyasadchy.xtra.util

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceUiModeTest {
    @Test fun televisionIsRecognized() = assertTrue(isTelevisionUiMode(Configuration.UI_MODE_TYPE_TELEVISION))
    @Test fun normalIsNotTelevision() = assertFalse(isTelevisionUiMode(Configuration.UI_MODE_TYPE_NORMAL))
    @Test fun carIsNotTelevision() = assertFalse(isTelevisionUiMode(Configuration.UI_MODE_TYPE_CAR))
    @Test fun watchIsNotTelevision() = assertFalse(isTelevisionUiMode(Configuration.UI_MODE_TYPE_WATCH))
}
