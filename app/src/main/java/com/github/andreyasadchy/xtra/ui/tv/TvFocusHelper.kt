package com.github.andreyasadchy.xtra.ui.tv

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import com.github.andreyasadchy.xtra.util.isTelevision
import com.github.andreyasadchy.xtra.R
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

object TvFocusHelper {
    fun linkHorizontalFocus(views: List<View>) {
        val focusable = views.filter { it.visibility == View.VISIBLE && it.isFocusable && it.isEnabled }
        focusable.forEachIndexed { index, view ->
            view.nextFocusLeftId = focusable.getOrNull(index - 1)?.id ?: View.NO_ID
            view.nextFocusRightId = focusable.getOrNull(index + 1)?.id ?: View.NO_ID
        }
    }

    fun disableDescendantFocus(root: ViewGroup) {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            child.isFocusable = false
            child.isFocusableInTouchMode = false
            if (child is ViewGroup) disableDescendantFocus(child)
        }
    }

    fun routeDirectionalFocus(
        event: KeyEvent,
        root: ViewGroup,
        top: List<View>,
        transport: List<View>,
        bottom: List<View>,
        focusedOverride: View? = null,
        fallback: View? = null,
        onMoved: (View) -> Unit = {},
    ): Boolean {
        if (event.keyCode !in setOf(
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
            )
        ) return false

        val candidate = focusedOverride ?: root.findFocus()
        val focused = candidate?.takeIf { it in top || it in transport || it in bottom }
            ?: fallback
            ?: return false
        val row = when {
            focused in top -> top
            focused in transport -> transport
            focused in bottom -> bottom
            else -> return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true

        val index = row.indexOf(focused)
        val target = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> row.getOrNull(index - 1) ?: focused
            KeyEvent.KEYCODE_DPAD_RIGHT -> row.getOrNull(index + 1) ?: focused
            KeyEvent.KEYCODE_DPAD_UP -> when {
                row === top -> focused
                row === transport -> top.firstOrNull() ?: focused
                else -> transport.getOrNull(1) ?: transport.firstOrNull() ?: focused
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> when {
                row === bottom -> focused
                row === top -> transport.getOrNull(1) ?: transport.firstOrNull() ?: focused
                else -> bottom.firstOrNull() ?: focused
            }
            else -> focused
        }
        if (target.requestFocus()) onMoved(target) else focused.requestFocus()
        return true
    }

    fun install(view: View, focusedScale: Float = 1.055f, focusedTranslationZDp: Float = 8f) {
        if (!view.context.isTelevision()) return
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        if (view.background is ColorDrawable &&
            (view.background as ColorDrawable).color == Color.TRANSPARENT
        ) {
            view.setBackgroundResource(R.drawable.tv_player_control_background)
        }
        val density = view.resources.displayMetrics.density
        val originalScaleX = view.scaleX
        val originalScaleY = view.scaleY
        val originalTranslationZ = view.translationZ
        val card = view as? MaterialCardView
        val originalStrokeWidth = card?.strokeWidth ?: 0
        val originalStrokeColor = card?.strokeColor ?: 0
        val focusColor = MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary)
        val previous = view.onFocusChangeListener
        view.onFocusChangeListener = View.OnFocusChangeListener { target, focused ->
            previous?.onFocusChange(target, focused)
            target.animate().cancel()
            target.animate()
                .scaleX(if (focused) focusedScale else originalScaleX)
                .scaleY(if (focused) focusedScale else originalScaleY)
                .translationZ(if (focused) originalTranslationZ + focusedTranslationZDp * density else originalTranslationZ)
                .setDuration(120L)
                .start()
            card?.let {
                it.strokeWidth = if (focused) (3f * density).toInt() else originalStrokeWidth
                it.strokeColor = if (focused) focusColor else originalStrokeColor
            }
        }
    }

    fun installClickableDescendants(root: ViewGroup) {
        if (!root.context.isTelevision()) return
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child is ViewGroup) installClickableDescendants(child)
            if (child.isClickable) install(child, focusedScale = 1.04f)
        }
    }
}
