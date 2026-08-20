package com.github.andreyasadchy.xtra.ui.player.clip

import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Keeps the pre-scoped-storage publishing path covered on an API 28 emulator. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(maxSdkVersion = 28)
class ClipMediaStoreLegacyInstrumentationTest {
    @Test
    fun legacyClipValuesUseTheMoviesDataPath() {
        val displayName = "clip.mp4"
        val file = ClipMediaStore.legacyFile(displayName)
        val values = ClipMediaStore.legacyValues(displayName, file)

        assertTrue(Build.VERSION.SDK_INT <= 28)
        assertEquals(displayName, values.getAsString(MediaStore.Video.Media.DISPLAY_NAME))
        assertEquals(file.absolutePath, values.getAsString(MediaStore.Video.Media.DATA))
    }
}
