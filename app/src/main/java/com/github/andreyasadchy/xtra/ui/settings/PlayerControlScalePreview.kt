package com.github.andreyasadchy.xtra.ui.settings

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.core.content.ContextCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.PlayerControlLayout
import com.github.andreyasadchy.xtra.util.PortraitPlayerControls
import com.github.andreyasadchy.xtra.util.SettingsMigration
import com.github.andreyasadchy.xtra.util.prefs
import kotlin.math.min
import kotlin.math.roundToInt

class PlayerControlScalePreviewPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.preference_player_control_scale_preview
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.findViewById<PlayerControlScalePreviewView>(R.id.playerControlScalePreview).setValues(
            controlScale(context.prefs().getString(C.PLAYER_CONTROL_SCALE_PORTRAIT, "auto")),
            controlScale(context.prefs().getString(C.PLAYER_CONTROL_SCALE_LANDSCAPE, "100")) ?: 1f,
            context.prefs().getString(C.PLAYER_CONTROL_METADATA_SCALE, "100")?.toFloatOrNull()?.div(100f)?.coerceIn(0.55f, 1.2f) ?: 1f,
            scaleLabel(context, context.prefs().getString(C.PLAYER_CONTROL_SCALE_PORTRAIT, "auto")),
            scaleLabel(context, context.prefs().getString(C.PLAYER_CONTROL_SCALE_LANDSCAPE, "100")),
            scaleLabel(context, context.prefs().getString(C.PLAYER_CONTROL_METADATA_SCALE, "100")),
        )
    }

    fun refreshPreview() = notifyChanged()

    private fun controlScale(value: String?): Float? = value?.takeUnless { it == "auto" }?.toFloatOrNull()?.div(100f)?.coerceIn(0.55f, 1.2f)

    private fun scaleLabel(context: Context, value: String?): String = if (value == "auto") context.getString(R.string.auto)
    else value?.toFloatOrNull()?.roundToInt()?.let { "$it%" } ?: context.getString(R.string.auto)
}

/** Shows the real player surface twice with the current portrait and landscape scales. */
class PlayerControlScalePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val panels = listOf(
        PreviewPanel(context, R.string.settings_player_control_preview_vertical, isPortrait = true),
        PreviewPanel(context, R.string.settings_player_control_preview_horizontal, isPortrait = false),
    )
    private var contentDescriptionText = ""

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(4), 0, dp(8))
        panels.forEach { addView(it, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        contentDescription = context.getString(R.string.settings_player_control_preview_summary)
    }

    fun setValues(
        vertical: Float?,
        horizontal: Float,
        metadata: Float,
        verticalLabel: String,
        horizontalLabel: String,
        metadataLabel: String,
    ) {
        panels[0].setValues(vertical ?: automaticScale(), metadata, verticalLabel, metadataLabel)
        panels[1].setValues(horizontal, metadata, horizontalLabel, metadataLabel)
        contentDescriptionText = context.getString(R.string.settings_player_control_preview_description, verticalLabel, horizontalLabel, metadataLabel)
        contentDescription = contentDescriptionText
        requestLayout()
    }

    private fun automaticScale(): Float = PortraitPlayerControls.automaticControlScale(
        playerHeight = (min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 9f / 16f).roundToInt(),
        density = resources.displayMetrics.density,
    )

    private inner class PreviewPanel(
        context: Context,
        private val labelRes: Int,
        private val isPortrait: Boolean,
    ) : FrameLayout(context) {
        private val header = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(10), 0, dp(10), dp(6))
        }
        private val title = TextView(context).apply {
            setText(labelRes)
            setTextColor(themeColor(android.R.attr.textColorPrimary, Color.WHITE))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        private val detail = TextView(context).apply {
            setTextColor(themeColor(android.R.attr.textColorSecondary, Color.GRAY))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        // The scale comparison is static chrome; two independent decoders would add needless
        // codec and TextureView pressure every time this preference is opened.
        private val preview = PlayerControlPreviewView(context, dragEnabled = false, playbackEnabled = false)

        init {
            clipChildren = false
            header.addView(title)
            header.addView(detail)
            addView(header)
            addView(preview)
        }

        fun setValues(control: Float, metadata: Float, controlLabel: String, metadataLabel: String) {
            detail.text = "$controlLabel - ${context.getString(R.string.settings_player_control_preview_info)} $metadataLabel"
            preview.setVisualScale(control, metadata, isPortrait)
            preview.setItems(
                PlayerControlLayout.controlPlacements(
                    context.prefs().getString(C.SETTINGS_PLAYER_CONTROL_LAYOUT, null),
                    SettingsMigration.defaultControlLayout(),
                ),
            )
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
            val panelWidth = availableWidth.coerceAtMost(dp(520))
            val headerHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            header.measure(MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY), headerHeightSpec)
            val previewHeight = (panelWidth * 9f / 16f).roundToInt()
            preview.measure(MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(previewHeight, MeasureSpec.EXACTLY))
            setMeasuredDimension(availableWidth, header.measuredHeight + preview.measuredHeight)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val panelLeft = (width - preview.measuredWidth) / 2
            header.layout(panelLeft, 0, panelLeft + preview.measuredWidth, header.measuredHeight)
            preview.layout(panelLeft, header.measuredHeight, panelLeft + preview.measuredWidth, header.measuredHeight + preview.measuredHeight)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun themeColor(attribute: Int, fallback: Int): Int {
        val value = android.util.TypedValue()
        if (!context.theme.resolveAttribute(attribute, value, true)) return fallback
        return if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
    }
}
