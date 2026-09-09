package com.github.andreyasadchy.xtra.ui.drops

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View.BaseSavedState
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.GestureDetectorCompat
import kotlin.math.max
import kotlin.math.min

/** An image that can be inspected without losing a simple tap action. */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {
    private val imageMatrixState = Matrix()
    private var fitScale = 1f
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var restoredTransform: Transform? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setZoom(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        },
    )

    private val gestureDetector = GestureDetectorCompat(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onScroll(
                firstEvent: MotionEvent?,
                event: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (zoom > 1f) {
                    restoredTransform = null
                    offsetX -= distanceX
                    offsetY -= distanceY
                    applyImageMatrix()
                }
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                setZoom(if (zoom > 1.1f) 1f else 2.5f, event.x, event.y)
                return true
            }

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                performClick()
                return true
            }
        },
    )

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
        isFocusable = true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        restoredTransform?.let {
            zoom = it.zoom
            offsetX = it.offsetX
            offsetY = it.offsetY
        } ?: run {
            zoom = 1f
            offsetX = 0f
            offsetY = 0f
        }
        post(::applyImageMatrix)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        applyImageMatrix()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            applyImageMatrix()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onSaveInstanceState(): Parcelable {
        return SavedState(super.onSaveInstanceState()).also {
            it.zoom = zoom
            it.offsetX = offsetX
            it.offsetY = offsetY
        }
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is SavedState) {
            super.onRestoreInstanceState(state.superState)
            restoredTransform = Transform(state.zoom, state.offsetX, state.offsetY)
            zoom = state.zoom
            offsetX = state.offsetX
            offsetY = state.offsetY
            post(::applyImageMatrix)
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    private fun setZoom(target: Float, focalX: Float, focalY: Float) {
        val drawable = drawable ?: return
        if (fitScale <= 0f || width <= 0 || height <= 0) return

        restoredTransform = null

        val oldScale = fitScale * zoom
        val oldLeft = centeredLeft(drawable, oldScale) + offsetX
        val oldTop = centeredTop(drawable, oldScale) + offsetY
        val imageX = (focalX - oldLeft) / oldScale
        val imageY = (focalY - oldTop) / oldScale

        zoom = target.coerceIn(1f, MAX_ZOOM)
        val newScale = fitScale * zoom
        offsetX = focalX - imageX * newScale - centeredLeft(drawable, newScale)
        offsetY = focalY - imageY * newScale - centeredTop(drawable, newScale)
        applyImageMatrix()
    }

    private fun applyImageMatrix() {
        val drawable = drawable ?: return
        if (width <= 0 || height <= 0) return
        val drawableWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: return
        val drawableHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: return
        fitScale = min(width.toFloat() / drawableWidth, height.toFloat() / drawableHeight)
        if (fitScale <= 0f) return

        val scale = fitScale * zoom
        val imageWidth = drawableWidth * scale
        val imageHeight = drawableHeight * scale
        val maxOffsetX = max(0f, (imageWidth - width) / 2f)
        val maxOffsetY = max(0f, (imageHeight - height) / 2f)
        offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX)
        offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY)

        imageMatrixState.reset()
        imageMatrixState.setScale(scale, scale)
        imageMatrixState.postTranslate(
            (width - imageWidth) / 2f + offsetX,
            (height - imageHeight) / 2f + offsetY,
        )
        imageMatrix = imageMatrixState
    }

    private fun centeredLeft(drawable: Drawable, scale: Float): Float =
        (width - drawable.intrinsicWidth * scale) / 2f

    private fun centeredTop(drawable: Drawable, scale: Float): Float =
        (height - drawable.intrinsicHeight * scale) / 2f

    private data class Transform(
        val zoom: Float,
        val offsetX: Float,
        val offsetY: Float,
    )

    private class SavedState : BaseSavedState {
        var zoom = 1f
        var offsetX = 0f
        var offsetY = 0f

        constructor(superState: Parcelable?) : super(superState)

        private constructor(source: Parcel) : super(source) {
            zoom = source.readFloat()
            offsetX = source.readFloat()
            offsetY = source.readFloat()
        }

        override fun writeToParcel(destination: Parcel, flags: Int) {
            super.writeToParcel(destination, flags)
            destination.writeFloat(zoom)
            destination.writeFloat(offsetX)
            destination.writeFloat(offsetY)
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<SavedState> = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(source: Parcel): SavedState = SavedState(source)
                override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
            }
        }
    }

    private companion object {
        const val MAX_ZOOM = 5f
    }
}
