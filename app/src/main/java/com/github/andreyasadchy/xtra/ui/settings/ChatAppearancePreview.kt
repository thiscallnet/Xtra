package com.github.andreyasadchy.xtra.ui.settings

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import coil3.imageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.target
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.chat.resolveChatAppearance
import com.github.andreyasadchy.xtra.ui.chat.shouldRenderChatBackground
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

class ChatAppearancePreviewPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.preference_chat_appearance_preview
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.findViewById<ChatAppearancePreviewView>(R.id.chatAppearancePreview)
            .render()
    }

    fun refreshPreview() = notifyChanged()
}

class ChatAppearancePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val scrim = View(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val message = TextView(context)
    private val metadata = TextView(context)
    private val notice = TextView(context)
    private var imageRequest: Disposable? = null
    private var imageRequestGeneration = 0

    init {
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(MaterialColors.getColor(this@ChatAppearancePreviewView, com.google.android.material.R.attr.colorSurfaceContainerHigh))
        }
        clipToOutline = true
        addView(image, LayoutParams(-1, -1))
        addView(scrim, LayoutParams(-1, -1))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(message, LinearLayout.LayoutParams(-1, -2))
            addView(metadata, LinearLayout.LayoutParams(-1, -2))
            addView(notice, LinearLayout.LayoutParams(-1, -2))
        }, LayoutParams(-1, -2).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
        })
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun render() {
        val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
        val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        val secondary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val appearance = resolveChatAppearance(context, surface, primary, secondary)
        val requestGeneration = ++imageRequestGeneration
        imageRequest?.dispose()
        imageRequest = null
        image.setImageDrawable(null)
        if (shouldRenderChatBackground(appearance)) {
            image.visibility = View.VISIBLE
            image.alpha = appearance.backgroundVisibility / 100f
            scrim.visibility = View.VISIBLE
            scrim.setBackgroundColor(ColorUtils.setAlphaComponent(surface, 0xB8))
            imageRequest = context.imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(appearance.backgroundUri)
                    .target(image)
                    .listener(object : ImageRequest.Listener {
                        override fun onError(request: ImageRequest, result: coil3.request.ErrorResult) {
                            if (imageRequestGeneration != requestGeneration) return
                            image.setImageDrawable(null)
                            image.visibility = View.GONE
                            scrim.visibility = View.GONE
                            imageRequest = null
                        }
                    })
                    .build(),
            )
        } else {
            image.visibility = View.GONE
            scrim.visibility = View.GONE
        }
        message.text = "Streamer: ${context.getString(R.string.settings_chat_preview_message)}"
        message.setTextColor(appearance.messageTextColor)
        message.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        metadata.text = context.getString(R.string.settings_chat_preview_metadata)
        metadata.setTextColor(appearance.metadataTextColor)
        metadata.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        notice.text = context.getString(R.string.settings_chat_preview_notice)
        notice.setTextColor(appearance.messageTextColor)
        notice.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        contentDescription = context.getString(R.string.settings_chat_appearance_preview_summary)
    }

    override fun onDetachedFromWindow() {
        imageRequestGeneration++
        imageRequest?.dispose()
        imageRequest = null
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()
}
