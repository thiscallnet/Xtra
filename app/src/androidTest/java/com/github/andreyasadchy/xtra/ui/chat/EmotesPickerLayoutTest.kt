package com.github.andreyasadchy.xtra.ui.chat

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.view.GridAutofitLayoutManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmotesPickerLayoutTest {

    @Test
    fun pickerImageScalesSmallEmotesToTheCell() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val item = LayoutInflater.from(context)
            .inflate(R.layout.fragment_emotes_list_item, null)
        val imageButton = item.findViewById<ImageButton>(R.id.emote)
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

    @Test
    fun pickerCellsStayInsideTheRightEdge() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pickerWidth = (640 * context.resources.displayMetrics.density).toInt()
        val pickerHeight = (150 * context.resources.displayMetrics.density).toInt()
        val pickerRoot = LayoutInflater.from(context)
            .inflate(R.layout.fragment_emotes, null)
        val picker = pickerRoot.findViewById<RecyclerView>(R.id.emotesRecyclerView).apply {
            layoutManager = GridAutofitLayoutManager(context, (50 * context.resources.displayMetrics.density).toInt())
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    return object : RecyclerView.ViewHolder(
                        LayoutInflater.from(parent.context)
                            .inflate(R.layout.fragment_emotes_list_item, parent, false),
                    ) {}
                }

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit

                override fun getItemCount(): Int = 24
            }
        }

        pickerRoot.measure(
            View.MeasureSpec.makeMeasureSpec(pickerWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(pickerHeight, View.MeasureSpec.EXACTLY),
        )
        pickerRoot.layout(0, 0, pickerWidth, pickerHeight)

        val rightmostDecoratedEdge = (0 until picker.childCount)
            .maxOf { childIndex ->
                picker.layoutManager!!.getDecoratedRight(picker.getChildAt(childIndex))
            }
        assertTrue(
            "An emote cell extends past the picker: $rightmostDecoratedEdge > $pickerWidth",
            rightmostDecoratedEdge <= pickerWidth,
        )
    }
}
