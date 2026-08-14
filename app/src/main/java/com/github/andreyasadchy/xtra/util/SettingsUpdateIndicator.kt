package com.github.andreyasadchy.xtra.util

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.updater.UpdateState
import com.github.andreyasadchy.xtra.util.updater.hasActionableUpdate
import com.google.android.material.color.MaterialColors

object SettingsUpdateIndicator {

    fun update(toolbar: Toolbar, context: Context, state: UpdateState? = null) {
        val item = toolbar.menu.findItem(R.id.settings) ?: return
        val icon = ContextCompat.getDrawable(context, R.drawable.baseline_settings_black_24)?.mutate() ?: return
        val normalColor = MaterialColors.getColor(toolbar, androidx.appcompat.R.attr.colorControlNormal)
        DrawableCompat.setTint(icon, normalColor)
        val updateState = state ?: (context.applicationContext as? XtraApp)?.xtraModule?.updateRepository?.state?.value
        val pending = updateState?.hasActionableUpdate() == true
        if (!pending) {
            item.icon = icon
            return
        }
        val dotSize = (6 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val dotInset = context.resources.displayMetrics.density.toInt().coerceAtLeast(1)
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(context, android.R.color.holo_red_light))
        }
        item.icon = LayerDrawable(arrayOf(icon, dot)).apply {
            setLayerGravity(1, Gravity.TOP or Gravity.END)
            setLayerSize(1, dotSize, dotSize)
            setLayerInset(1, 0, dotInset, dotInset, 0)
        }
    }
}
