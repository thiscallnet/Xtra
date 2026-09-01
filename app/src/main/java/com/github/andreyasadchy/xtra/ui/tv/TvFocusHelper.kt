package com.github.andreyasadchy.xtra.ui.tv

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import com.github.andreyasadchy.xtra.util.isTelevision
import com.github.andreyasadchy.xtra.R
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

object TvFocusHelper {
    private data class FocusRow(
        val views: List<View>,
    )

    /** Links controls using their final screen positions after custom placement is applied. */
    fun linkVisualFocus(root: ViewGroup, candidates: List<View>) {
        val rows = focusRows(root, candidates)
        rows.forEach { row ->
            row.views.forEachIndexed { index, view ->
                view.nextFocusLeftId = row.views.getOrNull(index - 1)?.id ?: View.NO_ID
                view.nextFocusRightId = row.views.getOrNull(index + 1)?.id ?: View.NO_ID
            }
        }
        rows.forEachIndexed { rowIndex, row ->
            row.views.forEach { view ->
                view.nextFocusUpId = nearestInRow(view, rows.getOrNull(rowIndex - 1))?.id ?: View.NO_ID
                view.nextFocusDownId = nearestInRow(view, rows.getOrNull(rowIndex + 1))?.id ?: View.NO_ID
            }
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
        candidates: List<View>,
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

        val rows = focusRows(root, candidates)
        val candidate = focusedOverride ?: root.findFocus()
        val focused = candidate?.takeIf { view -> rows.any { view in it.views } }
            ?: fallback
            ?: return false
        val rowIndex = rows.indexOfFirst { focused in it.views }
        if (rowIndex < 0) return false
        val row = rows[rowIndex].views
        if (event.action != KeyEvent.ACTION_DOWN) return true

        val index = row.indexOf(focused)
        val target = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> row.getOrNull(index - 1) ?: focused
            KeyEvent.KEYCODE_DPAD_RIGHT -> row.getOrNull(index + 1) ?: focused
            KeyEvent.KEYCODE_DPAD_UP -> nearestInRow(focused, rows.getOrNull(rowIndex - 1)) ?: focused
            KeyEvent.KEYCODE_DPAD_DOWN -> nearestInRow(focused, rows.getOrNull(rowIndex + 1)) ?: focused
            else -> focused
        }
        if (target.requestFocus()) onMoved(target) else focused.requestFocus()
        return true
    }

    private fun focusRows(root: ViewGroup, candidates: List<View>): List<FocusRow> {
        val visible = candidates.filter { view ->
            view.isShown && view.visibility == View.VISIBLE && view.isEnabled && view.isFocusable &&
                view.width > 0 && view.height > 0
        }
        if (visible.isEmpty()) return emptyList()

        val locations = visible.associateWith { view ->
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            floatArrayOf(
                location[0] + view.width / 2f,
                location[1] + view.height / 2f,
            )
        }
        val rowTolerance = root.resources.displayMetrics.density * 28f
        val rows = mutableListOf<MutableList<View>>()
        val rowCenters = mutableListOf<Float>()

        visible.sortedBy { locations.getValue(it)[1] }.forEach { view ->
            val centerY = locations.getValue(view)[1]
            val rowIndex = rowCenters.indexOfFirst { kotlin.math.abs(it - centerY) <= rowTolerance }
            if (rowIndex >= 0) {
                val row = rows[rowIndex]
                row += view
                rowCenters[rowIndex] = row.map { locations.getValue(it)[1] }.average().toFloat()
            } else {
                rows += mutableListOf(view)
                rowCenters += centerY
            }
        }

        return rows.indices
            .sortedBy { rowCenters[it] }
            .map { index ->
                FocusRow(
                    views = rows[index].sortedBy { locations.getValue(it)[0] },
                )
            }
    }

    private fun nearestInRow(view: View, row: FocusRow?): View? {
        if (row == null) return null
        val viewLocation = IntArray(2)
        view.getLocationOnScreen(viewLocation)
        val viewCenterX = viewLocation[0] + view.width / 2f
        return row.views.minByOrNull { candidate ->
            val location = IntArray(2)
            candidate.getLocationOnScreen(location)
            kotlin.math.abs(viewCenterX - (location[0] + candidate.width / 2f))
        }
    }

    fun install(view: View, focusedScale: Float = 1.055f, focusedTranslationZDp: Float = 8f) {
        if (!view.context.isTelevision()) return
        if (view.getTag(R.id.tv_focus_helper_installed) == true) return
        view.setTag(R.id.tv_focus_helper_installed, true)
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        if (view is MaterialCardView) {
            // MaterialCardView already has a real surface. Its selectable foreground is a
            // dim grey ripple on Google TV, which obscures the content instead of showing
            // focus, so use the stroke below as the only TV focus treatment.
            view.foreground = ColorDrawable(Color.TRANSPARENT)
        } else if (view is ImageButton || view.foreground != null) {
            // The phone layout uses transparent ImageButton backgrounds. Put the TV focus
            // outline in that existing slot so it remains visible on controls near the
            // bottom edge; some platform foreground implementations are clipped there.
            // Plain rewind/fast-forward Buttons also use their foreground for the transport
            // artwork, so never replace that foreground with the focus drawable.
            view.background = ContextCompat.getDrawable(
                view.context,
                R.drawable.tv_player_control_background,
            )
        } else {
            // Keep the existing background (buttons may use a tinted Material background),
            // and put a transparent-by-default outline above it. This avoids the grey focus
            // square produced by the platform selectable background.
            view.foreground = ContextCompat.getDrawable(
                view.context,
                R.drawable.tv_player_control_background,
            )
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
                .translationZ(
                    if (focused && card == null) {
                        originalTranslationZ + focusedTranslationZDp * density
                    } else {
                        originalTranslationZ
                    },
                )
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
