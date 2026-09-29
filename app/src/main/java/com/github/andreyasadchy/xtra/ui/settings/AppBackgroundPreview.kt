package com.github.andreyasadchy.xtra.ui.settings

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import coil3.imageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.target
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.appearance.AppearanceRepository
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

class AppBackgroundPreviewPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.preference_app_background_preview
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.findViewById<AppBackgroundPreviewView>(R.id.appBackgroundPreview).render()
    }

    fun refreshPreview() = notifyChanged()
}

class AppBackgroundPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val repository = AppearanceRepository(context)
    private val image = ImageView(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    private val scrim = View(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var imageRequest: Disposable? = null
    private var requestGeneration = 0

    init {
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(MaterialColors.getColor(this@AppBackgroundPreviewView, com.google.android.material.R.attr.colorSurfaceContainerHigh))
        }
        clipToOutline = true
        addView(image, LayoutParams(-1, -1))
        addView(scrim, LayoutParams(-1, -1))
        val sample = LayoutInflater.from(context)
            .inflate(R.layout.app_background_preview_sample, this, false)
            .apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                isClickable = false
                isFocusable = false
            }
        addView(sample, LayoutParams(dp(140), dp(128), Gravity.CENTER))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun render() {
        val configuration = repository.appBackground()
        val uri = configuration.uri
        val generation = ++requestGeneration
        imageRequest?.dispose()
        imageRequest = null
        image.setImageDrawable(null)
        if (configuration.canRender && uri != null) {
            image.isVisible = true
            image.alpha = configuration.visibility / 100f
            scrim.isVisible = true
            val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
            scrim.setBackgroundColor(ColorUtils.setAlphaComponent(surface, 0xB8))
            imageRequest = context.imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(uri)
                    .target(image)
                    .listener(object : ImageRequest.Listener {
                        override fun onError(request: ImageRequest, result: coil3.request.ErrorResult) {
                            if (requestGeneration != generation) return
                            image.setImageDrawable(null)
                            image.isGone = true
                            scrim.isGone = true
                            imageRequest = null
                        }
                    })
                    .build(),
            )
        } else {
            image.isGone = true
            scrim.isGone = true
        }
        contentDescription = context.getString(R.string.settings_app_background_preview_content_description)
    }

    override fun onDetachedFromWindow() {
        requestGeneration++
        imageRequest?.dispose()
        imageRequest = null
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
