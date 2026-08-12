package com.github.andreyasadchy.xtra.ui.chat

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmotesPickerLayoutTest {

    @Test
    fun pickerImageScalesSmallEmotesToTheCell() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val imageButton = LayoutInflater.from(context)
            .inflate(R.layout.fragment_emotes_list_item, null) as ImageButton
        val size = (48 * context.resources.displayMetrics.density).toInt()
        imageButton.setImageDrawable(
            BitmapDrawable(
                context.resources,
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
            ),
        )
        imageButton.measure(
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
        )
        imageButton.layout(0, 0, size, size)

        val matrixValues = FloatArray(9)
        imageButton.imageMatrix.getValues(matrixValues)

        assertEquals(ImageView.ScaleType.FIT_CENTER, imageButton.scaleType)
        assertTrue(matrixValues[Matrix.MSCALE_X] > 1f)
        assertTrue(matrixValues[Matrix.MSCALE_Y] > 1f)
    }
}
