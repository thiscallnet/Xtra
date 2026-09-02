package com.github.andreyasadchy.xtra.ui.main

import android.view.View
import android.view.ContextThemeWrapper
import com.github.andreyasadchy.xtra.R
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnlimitedBottomNavigationViewTest {

    @Test
    fun acceptsAndLaysOutMoreThanMaterialMobileLimit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = UnlimitedBottomNavigationView(
                ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.DarkTheme),
            )
            repeat(7) { index ->
                view.menu.add(View.NO_ID, index + 1, index, "Tab $index")
            }

            val width = 1080
            val height = 96
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST),
            )
            view.layout(0, 0, width, view.measuredHeight)

            assertEquals(7, view.menu.size())
            assertEquals(7, view.menuViewGroup.childCount)
        }
    }
}
