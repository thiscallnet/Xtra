package com.github.andreyasadchy.xtra.ui.settings

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * Draws one rounded surface behind every run of settings that belong to the same section, so
 * long preference lists read as separate cards instead of one continuous column.
 */
@SuppressLint("RestrictedApi")
class SettingsCardDecoration(
    private val color: Int,
    private val radius: Float,
) : RecyclerView.ItemDecoration() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = this@SettingsCardDecoration.color }
    private val path = Path()
    private val rect = RectF()
    private val radii = FloatArray(8)

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? PreferenceGroupAdapter ?: return
        var first: View? = null
        var last: View? = null
        var inRun = false
        var startsCard = false

        fun flush(endsCard: Boolean) {
            val top = first ?: return
            val bottom = last ?: return
            rect.set(top.left.toFloat(), top.top + top.translationY, bottom.right.toFloat(), bottom.bottom + bottom.translationY)
            val topRadius = if (startsCard) radius else 0f
            val bottomRadius = if (endsCard) radius else 0f
            radii[0] = topRadius; radii[1] = topRadius; radii[2] = topRadius; radii[3] = topRadius
            radii[4] = bottomRadius; radii[5] = bottomRadius; radii[6] = bottomRadius; radii[7] = bottomRadius
            path.reset()
            path.addRoundRect(rect, radii, Path.Direction.CW)
            canvas.drawPath(path, paint)
            first = null
            last = null
            inRun = false
        }

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            val pref = if (position != RecyclerView.NO_POSITION) adapter.getItem(position) else null
            if (pref == null || pref is PreferenceCategory) {
                flush(endsCard = true)
                continue
            }
            if (!inRun) {
                inRun = true
                first = child
                startsCard = position == 0 || adapter.getItem(position - 1) is PreferenceCategory
            }
            last = child
        }
        flush(endsCard = last?.let { isCardEnd(parent, adapter, it) } ?: true)
    }

    private fun isCardEnd(parent: RecyclerView, adapter: PreferenceGroupAdapter, view: View): Boolean {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return true
        return position + 1 >= adapter.itemCount || adapter.getItem(position + 1) is PreferenceCategory
    }
}
