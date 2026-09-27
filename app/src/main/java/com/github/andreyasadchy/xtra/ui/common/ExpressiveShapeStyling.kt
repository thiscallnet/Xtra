package com.github.andreyasadchy.xtra.ui.common

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.View
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.isTelevision
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

fun Context.usesExpressiveInterface(): Boolean =
    prefs().getString(C.SETTINGS_UI_STYLE, "expressive") == "expressive"

object ExpressiveShapeStyling {
    fun applyStreamShelfItem(view: View) {
        applySurface(
            view,
            com.google.android.material.R.attr.shapeAppearanceLargeComponent,
            com.google.android.material.R.attr.colorSurfaceContainerLow,
            clickable = true,
        )
        view.findViewById<View>(R.id.thumbnail)?.let { thumbnail ->
            applySurface(
                thumbnail,
                com.google.android.material.R.attr.shapeAppearanceLargeComponent,
                com.google.android.material.R.attr.colorSurfaceContainerLow,
            )
        }
        view.findViewById<View>(R.id.previewHost)?.let { previewHost ->
            applyClipShape(
                previewHost,
                com.google.android.material.R.attr.shapeAppearanceLargeComponent,
            )
        }
        applySolidSurface(
            view.findViewById(R.id.liveBadge),
            com.google.android.material.R.attr.shapeAppearanceSmallComponent,
            androidx.core.content.ContextCompat.getColor(view.context, R.color.liveStreamRed),
        )
        val tagColor = MaterialColors.getColor(
            view,
            com.google.android.material.R.attr.colorSurfaceContainerHighest,
        )
        listOf(R.id.tagOne, R.id.tagTwo, R.id.tagThree).forEach { tagId ->
            view.findViewById<View>(tagId)?.let { tag ->
                applySolidSurface(
                    tag,
                    com.google.android.material.R.attr.shapeAppearanceSmallComponent,
                    tagColor,
                )
            }
        }
    }

    fun applyFeaturedStreamItem(view: View) {
        applySolidSurface(
            view.findViewById(R.id.liveBadge),
            com.google.android.material.R.attr.shapeAppearanceSmallComponent,
            androidx.core.content.ContextCompat.getColor(view.context, R.color.liveStreamRed),
        )
        applySolidSurface(
            view.findViewById(R.id.tagOne),
            com.google.android.material.R.attr.shapeAppearanceSmallComponent,
            Color.argb(184, 0, 0, 0),
        )
    }

    fun applyFollowingOverviewSection(view: View) {
        applyRippleSurface(
            view.findViewById(R.id.seeAll),
            com.google.android.material.R.attr.shapeAppearanceSmallComponent,
            com.google.android.material.R.attr.colorPrimaryContainer,
        )
    }

    fun applySurface(
        view: View,
        shapeAppearanceAttribute: Int,
        fillColorAttribute: Int,
        clickable: Boolean = false,
    ) {
        val shape = shapeModel(view.context, shapeAppearanceAttribute)
        val content = MaterialShapeDrawable(shape).apply {
            fillColor = ColorStateList.valueOf(MaterialColors.getColor(view, fillColorAttribute))
        }
        view.clipToOutline = true
        if (clickable) {
            val rippleColors = ColorStateList.valueOf(
                MaterialColors.getColor(view, android.R.attr.colorControlHighlight),
            )
            val mask = MaterialShapeDrawable(shape).apply {
                fillColor = ColorStateList.valueOf(Color.WHITE)
            }
            if (view.context.isTelevision()) {
                view.background = RippleDrawable(rippleColors, content, mask)
            } else {
                view.background = content
                view.foreground = RippleDrawable(rippleColors, null, mask)
            }
        } else {
            view.background = content
        }
    }

    fun applyClipShape(view: View, shapeAppearanceAttribute: Int) {
        view.background = MaterialShapeDrawable(shapeModel(view.context, shapeAppearanceAttribute)).apply {
            fillColor = ColorStateList.valueOf(Color.TRANSPARENT)
        }
        view.clipToOutline = true
    }

    fun applyRippleSurface(
        view: View,
        shapeAppearanceAttribute: Int,
        fillColorAttribute: Int,
    ) {
        val shape = shapeModel(view.context, shapeAppearanceAttribute)
        val content = MaterialShapeDrawable(shape).apply {
            fillColor = ColorStateList.valueOf(MaterialColors.getColor(view, fillColorAttribute))
        }
        val mask = MaterialShapeDrawable(shape).apply {
            fillColor = ColorStateList.valueOf(Color.WHITE)
        }
        val rippleColor = MaterialColors.getColor(
            view,
            android.R.attr.colorControlHighlight,
        )
        view.background = RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
        view.clipToOutline = true
    }

    fun applySolidSurface(view: View, shapeAppearanceAttribute: Int, color: Int) {
        view.background = MaterialShapeDrawable(shapeModel(view.context, shapeAppearanceAttribute)).apply {
            fillColor = ColorStateList.valueOf(color)
        }
        view.clipToOutline = true
    }

    private fun shapeModel(context: Context, shapeAppearanceAttribute: Int): ShapeAppearanceModel {
        val value = TypedValue()
        val style = if (context.theme.resolveAttribute(shapeAppearanceAttribute, value, true)) {
            value.resourceId
        } else {
            0
        }
        return if (style != 0) {
            ShapeAppearanceModel.builder(context, style, 0).build()
        } else {
            ShapeAppearanceModel.builder().build()
        }
    }
}
