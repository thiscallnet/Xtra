package com.github.andreyasadchy.xtra.ui.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.PlayerControlLayout
import com.github.andreyasadchy.xtra.util.PortraitPlayerControls
import com.github.andreyasadchy.xtra.util.SettingsMigration
import com.github.andreyasadchy.xtra.util.prefs
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
        holder.itemView.findViewById<PlayerControlScalePreviewView>(R.id.playerControlScalePreview)
            .setValues(
                controlScale(context.prefs().getString(C.PLAYER_CONTROL_SCALE_PORTRAIT, "auto")),
                controlScale(context.prefs().getString(C.PLAYER_CONTROL_SCALE_LANDSCAPE, "100")) ?: 1f,
                context.prefs().getString(C.PLAYER_CONTROL_METADATA_SCALE, "100")
                    ?.toFloatOrNull()
                    ?.div(100f)
                    ?.coerceIn(0.55f, 1.2f)
                    ?: 1f,
                scaleLabel(context, context.prefs().getString(C.PLAYER_CONTROL_SCALE_PORTRAIT, "auto")),
                scaleLabel(context, context.prefs().getString(C.PLAYER_CONTROL_SCALE_LANDSCAPE, "100")),
                scaleLabel(context, context.prefs().getString(C.PLAYER_CONTROL_METADATA_SCALE, "100")),
            )
    }

    fun refreshPreview() = notifyChanged()

    private fun controlScale(value: String?): Float? = value
        ?.takeUnless { it == "auto" }
        ?.toFloatOrNull()
        ?.div(100f)
        ?.coerceIn(0.55f, 1.2f)

    private fun scaleLabel(context: Context, value: String?): String = if (value == "auto") {
        context.getString(R.string.auto)
    } else {
        value?.toFloatOrNull()?.roundToInt()?.let { "$it%" }
            ?: context.getString(R.string.auto)
    }
}

/** A compact version of the real control-layout editor preview. */
class PlayerControlScalePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val gap = dp(12)
    private val headerHeight = dp(38)
    private val maxPanelWidth = dp(520)
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panels = listOf(
        PreviewPanel(context, R.string.settings_player_control_preview_vertical),
        PreviewPanel(context, R.string.settings_player_control_preview_horizontal),
    )

    init {
        setWillNotDraw(false)
        clipChildren = false
        panels.forEach(::addView)
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
        panels[0].setValues(vertical, metadata, verticalLabel, metadataLabel)
        panels[1].setValues(horizontal, metadata, horizontalLabel, metadataLabel)
        contentDescription = context.getString(
            R.string.settings_player_control_preview_description,
            verticalLabel,
            horizontalLabel,
            metadataLabel,
        )
        invalidate()
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val panelWidth = width.coerceAtMost(maxPanelWidth).coerceAtLeast(0)
        val panelHeight = (panelWidth * 9f / 16f).roundToInt()
        val totalHeight = panelHeight * panels.size + gap + headerHeight * panels.size
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(totalHeight, heightMeasureSpec))
        panels.forEach { panel ->
            panel.measure(
                MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(panelHeight, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val panelWidth = width.coerceAtMost(maxPanelWidth).coerceAtLeast(0)
        val panelHeight = (panelWidth * 9f / 16f).roundToInt()
        val start = (width - panelWidth) / 2
        panels.forEachIndexed { index, panel ->
            val panelTop = index * (panelHeight + gap + headerHeight) + headerHeight
            panel.layout(start, panelTop, start + panelWidth, panelTop + panelHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val panelWidth = width.coerceAtMost(maxPanelWidth).coerceAtLeast(0)
        val panelHeight = (panelWidth * 9f / 16f).roundToInt()
        val start = (width - panelWidth) / 2f
        val size = { value: Float -> value * resources.displayMetrics.density }

        panels.forEachIndexed { index, panel ->
            val panelTop = index * (panelHeight + gap + headerHeight) + headerHeight
            val headerTop = panelTop - headerHeight
            headerPaint.color = Color.WHITE
            headerPaint.alpha = 220
            headerPaint.typeface = Typeface.DEFAULT_BOLD
            headerPaint.textSize = size(11f)
            canvas.drawText(panel.headerLabel(), start + size(10f), headerTop + size(15f), headerPaint)
            headerPaint.alpha = 150
            headerPaint.typeface = Typeface.DEFAULT
            headerPaint.textSize = size(8f)
            canvas.drawText(panel.headerDetails(context), start + size(10f), headerTop + size(30f), headerPaint)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private inner class PreviewPanel(
        context: Context,
        private val labelRes: Int,
    ) : FrameLayout(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val chips = linkedMapOf<String, ImageButton>()
        private var controlScale = 1f
        private var controlScaleIsAutomatic = false
        private var metadataScale = 1f
        private var controlLabel = ""
        private var metadataLabel = ""
        private var items = emptyList<PlayerControlLayout.ControlPlacement>()
        private val play = ImageView(context).apply {
            setImageResource(R.drawable.baseline_play_arrow_black_48)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            alpha = 0.9f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val menu = ImageButton(context).apply {
            setImageResource(R.drawable.baseline_more_vert_black_24)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = transparentBackground()
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        init {
            setWillNotDraw(false)
            clipChildren = true
            background = panelBackground()
            addView(play)
            addView(menu)
        }

        fun setValues(scale: Float?, metadata: Float, scaleLabel: String, infoLabel: String) {
            controlScaleIsAutomatic = scale == null
            controlScale = scale ?: 0.55f
            metadataScale = metadata
            controlLabel = scaleLabel
            metadataLabel = infoLabel
            items = PlayerControlLayout.controlPlacements(
                context.prefs().getString(C.SETTINGS_PLAYER_CONTROL_LAYOUT, null),
                SettingsMigration.defaultControlLayout(),
            )
            rebuildControls()
            invalidate()
        }

        fun headerLabel(): String = context.getString(labelRes)

        fun headerDetails(context: Context): String =
            "$controlLabel · ${context.getString(R.string.settings_player_control_preview_info)} $metadataLabel"

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            if (width > 0 && height > 0) rebuildControls()
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            positionChildren()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val unit = previewUnit()
            if (unit <= 0f) return
            val size = { value: Float -> value * unit }

            paint.style = Paint.Style.FILL
            paint.color = Color.argb(28, 255, 255, 255)
            canvas.drawRect(
                0f,
                height - size(68f),
                width.toFloat(),
                height - size(67f),
                paint,
            )

            // Keep the metadata row fixed while the minimize control sits in
            // the compact top-start control slot.
            drawMetadata(canvas, 12f, size)

            paint.color = Color.argb(140, 255, 255, 255)
            canvas.drawRoundRect(
                size(44f), height - size(13f), width - size(44f), height - size(10f),
                size(2f), size(2f), paint,
            )
            paint.color = Color.rgb(190, 90, 255)
            canvas.drawRoundRect(
                size(44f), height - size(13f), width * 0.38f, height - size(10f),
                size(2f), size(2f), paint,
            )

            paint.color = Color.WHITE
            paint.alpha = 170
            paint.textSize = size(8f)
            canvas.drawText("12:16", size(10f), height - size(20f), paint)
            canvas.drawText("1:12:16", width - size(47f), height - size(20f), paint)
            paint.textSize = size(8f)
        }

        private fun drawMetadata(canvas: Canvas, infoStart: Float, size: (Float) -> Float) {
            val avatarX = size(infoStart + 16f)
            val avatarY = size(48f)
            val infoSlotStart = infoStart + 36f
            val panelWidthDp = width / previewUnit()
            val topEndCount = items.count {
                it.group == PlayerControlLayout.GROUP_QUICK &&
                    it.anchor == PlayerControlLayout.ANCHOR_TOP_END
            }
            val topEndWidth = topEndCount * 44f +
                (topEndCount - 1).coerceAtLeast(0) * 6f
            val infoSlotRight = panelWidthDp - 9f - 48f - topEndWidth
            val metadataAvailableWidth = (infoSlotRight - infoSlotStart).coerceAtLeast(0f)
            val metadataPivotX = size(
                infoSlotStart + PortraitPlayerControls.METADATA_PIVOT_AFTER_INFO_START_DP,
            )
            val metadataPivotY = avatarY
            val actualControlScale = actualControlScale()
            val scaledPivotX = metadataPivotX * actualControlScale
            val scaledPivotY = metadataPivotY * actualControlScale
            val effectiveScale = actualControlScale * metadataScale
            var metadataContentWidth = PortraitPlayerControls.metadataContentWidth(
                metadataAvailableWidth,
                metadataScale,
            )
            val point: (Float, Float) -> PointF = { x, y ->
                val composedX = x * actualControlScale
                val composedY = y * actualControlScale
                PointF(
                    scaledPivotX + (composedX - scaledPivotX) * metadataScale,
                    scaledPivotY + (composedY - scaledPivotY) * metadataScale,
                )
            }
            var avatarPoint = PointF(0f, 0f)
            var titlePoint = PointF(0f, 0f)
            var livePoint = PointF(0f, 0f)
            var viewersPoint = PointF(0f, 0f)
            var metadataBoundaryPoint = PointF(0f, 0f)
            var metadataLeft = 0f
            var metadataRightEdge = 0f
            val rightBoundaryPoint = size(infoSlotRight) * actualControlScale
            repeat(2) {
                avatarPoint = point(avatarX, avatarY)
                titlePoint = point(size(infoSlotStart), size(43f))
                livePoint = point(size(infoSlotStart), size(54f))
                viewersPoint = point(
                    size(infoSlotStart + metadataContentWidth - 62f),
                    size(64f),
                )
                metadataBoundaryPoint = point(size(infoSlotStart + metadataContentWidth), avatarY)
                metadataLeft = minOf(
                    avatarPoint.x - size(16f) * effectiveScale,
                    titlePoint.x,
                    livePoint.x,
                    viewersPoint.x,
                )
                metadataRightEdge = maxOf(
                    viewersPoint.x + size(62f) * effectiveScale,
                    metadataBoundaryPoint.x,
                )
                val minimumDelta = -metadataLeft
                val maximumDelta = rightBoundaryPoint - metadataRightEdge
                if (minimumDelta > maximumDelta) {
                    val reduction = (minimumDelta - maximumDelta) /
                        (previewUnit() * actualControlScale * metadataScale)
                    val newWidth = (metadataContentWidth - reduction).coerceAtLeast(1f)
                    if (newWidth < metadataContentWidth) {
                        metadataContentWidth = newWidth
                    }
                }
            }
            val minimumDelta = -metadataLeft
            val maximumDelta = rightBoundaryPoint - metadataRightEdge
            val compositionCorrectionX = when {
                minimumDelta <= 0f && maximumDelta >= 0f -> 0f
                minimumDelta > 0f -> minimumDelta
                else -> maximumDelta
            }
            val correctedTitlePoint = PointF(titlePoint.x + compositionCorrectionX, titlePoint.y)
            val correctedLivePoint = PointF(livePoint.x + compositionCorrectionX, livePoint.y)
            val correctedViewersPoint = PointF(viewersPoint.x + compositionCorrectionX, viewersPoint.y)
            val correctedAvatar = PointF(avatarPoint.x + compositionCorrectionX, avatarPoint.y)
            canvas.save()
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(210, 220, 230)
            canvas.drawCircle(
                correctedAvatar.x,
                correctedAvatar.y,
                size(16f) * effectiveScale,
                paint,
            )
            paint.color = Color.WHITE
            canvas.drawRect(
                correctedTitlePoint.x,
                correctedTitlePoint.y - size(5f) * effectiveScale,
                correctedTitlePoint.x + size(42f) * effectiveScale,
                correctedTitlePoint.y - size(2f) * effectiveScale,
                paint,
            )
            paint.alpha = 210
            canvas.drawRect(
                correctedLivePoint.x,
                correctedLivePoint.y - size(4f) * effectiveScale,
                correctedLivePoint.x + size(52f) * effectiveScale,
                correctedLivePoint.y - size(2f) * effectiveScale,
                paint,
            )
            canvas.drawRect(
                correctedViewersPoint.x,
                correctedViewersPoint.y - size(4f) * effectiveScale,
                correctedViewersPoint.x + size(62f) * effectiveScale,
                correctedViewersPoint.y - size(2f) * effectiveScale,
                paint,
            )
            canvas.restore()
        }

        private fun rebuildControls() {
            chips.values.toList().forEach(::removeView)
            chips.clear()
            val buttonSize = previewDp(44)
            items.filter { it.group == PlayerControlLayout.GROUP_QUICK }.forEach { item ->
                val chip = ImageButton(context).apply {
                    setImageResource(iconFor(item.action))
                    imageTintList = ColorStateList.valueOf(Color.WHITE)
                    background = chipBackground()
                    setPadding(previewDp(9), previewDp(9), previewDp(9), previewDp(9))
                    isClickable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                chips[item.action] = chip
                addView(chip, LayoutParams(buttonSize, buttonSize))
            }
            positionChildren()
        }

        private fun positionChildren() {
            if (width <= 0 || height <= 0) return
            val grouped = items.filter { it.group == PlayerControlLayout.GROUP_QUICK }.groupBy { it.anchor }
            grouped.forEach { (anchor, group) ->
                group.forEachIndexed { index, item ->
                    chips[item.action]?.let { chip ->
                        placeScaled(chip, controlPoint(anchor, index, group.size), anchor)
                    }
                }
            }
            menu.layoutParams = LayoutParams(previewDp(42), previewDp(42))
            menu.x = (width - previewDp(10) - menu.width).coerceAtLeast(0).toFloat()
            menu.y = previewDp(4).toFloat()
            play.layoutParams = LayoutParams(previewDp(48), previewDp(48))
            play.x = (width - play.width) / 2f
            play.y = (height - play.height) / 2f
            placeScaled(play, PointF(width / 2f, height / 2f), null)
        }

        private fun placeScaled(view: View, point: PointF, anchor: String?) {
            val actualControlScale = actualControlScale()
            view.pivotX = view.width / 2f
            view.pivotY = view.height / 2f
            view.scaleX = actualControlScale
            view.scaleY = actualControlScale
            val viewCenterX = view.left + view.width / 2f
            val viewCenterY = view.top + view.height / 2f
            val scaledHalfWidth = view.width * actualControlScale / 2f
            val scaledHalfHeight = view.height * actualControlScale / 2f
            val scaledX = when (anchor) {
                PlayerControlLayout.ANCHOR_TOP_START,
                PlayerControlLayout.ANCHOR_MIDDLE_START,
                PlayerControlLayout.ANCHOR_BOTTOM_START -> {
                    val edge = previewDp(9).toFloat()
                    edge + (point.x - edge) * actualControlScale
                }
                PlayerControlLayout.ANCHOR_TOP_END -> {
                    val edge = width - previewDp(9) - previewDp(48)
                    edge - (edge - point.x) * actualControlScale
                }
                PlayerControlLayout.ANCHOR_MIDDLE_END,
                PlayerControlLayout.ANCHOR_BOTTOM_END -> {
                    val edge = width - previewDp(9).toFloat()
                    edge - (edge - point.x) * actualControlScale
                }
                else -> width / 2f + (point.x - width / 2f) * actualControlScale
            }
            val scaledY = when (anchor) {
                PlayerControlLayout.ANCHOR_TOP_START,
                PlayerControlLayout.ANCHOR_TOP_CENTER,
                PlayerControlLayout.ANCHOR_TOP_END -> {
                    val edge = previewDp(9).toFloat()
                    edge + (point.y - edge) * actualControlScale
                }
                PlayerControlLayout.ANCHOR_BOTTOM_START,
                PlayerControlLayout.ANCHOR_BOTTOM_CENTER,
                PlayerControlLayout.ANCHOR_BOTTOM_END -> {
                    val edge = height - previewDp(9) - previewDp(13)
                    edge - (edge - point.y) * actualControlScale
                }
                else -> height / 2f + (point.y - height / 2f) * actualControlScale
            }
            val boundedX = scaledX
                .coerceIn(
                    scaledHalfWidth,
                    (width - scaledHalfWidth).coerceAtLeast(scaledHalfWidth),
                )
            val boundedY = scaledY
                .coerceIn(
                    scaledHalfHeight,
                    (height - scaledHalfHeight).coerceAtLeast(scaledHalfHeight),
                )
            view.translationX = boundedX - viewCenterX
            view.translationY = boundedY - viewCenterY
        }

        private fun controlPoint(anchor: String, index: Int, count: Int): PointF {
            val size = previewDp(44).toFloat()
            val spacing = previewDp(6).toFloat()
            val padding = previewDp(9).toFloat()
            val menuReserve = if (anchor == PlayerControlLayout.ANCHOR_TOP_END) previewDp(48) else 0
            val total = count * size + (count - 1).coerceAtLeast(0) * spacing
            val x = when (anchor) {
                PlayerControlLayout.ANCHOR_TOP_START,
                PlayerControlLayout.ANCHOR_BOTTOM_START -> padding + size / 2f + index * (size + spacing)
                PlayerControlLayout.ANCHOR_TOP_CENTER,
                PlayerControlLayout.ANCHOR_BOTTOM_CENTER -> (width - total) / 2f + size / 2f + index * (size + spacing)
                PlayerControlLayout.ANCHOR_TOP_END,
                PlayerControlLayout.ANCHOR_BOTTOM_END -> width - padding - menuReserve - total + size / 2f + index * (size + spacing)
                PlayerControlLayout.ANCHOR_MIDDLE_START,
                PlayerControlLayout.ANCHOR_MIDDLE_END -> if (anchor.endsWith("start")) padding + size / 2f else width - padding - size / 2f
                else -> width / 2f
            }
            val bottomEndRowOffset = if (
                anchor == PlayerControlLayout.ANCHOR_BOTTOM_END &&
                items.any {
                    it.group == PlayerControlLayout.GROUP_QUICK &&
                        it.anchor == PlayerControlLayout.ANCHOR_BOTTOM_START
                }
            ) {
                size + spacing
            } else {
                0f
            }
            val y = when (anchor) {
                PlayerControlLayout.ANCHOR_TOP_CENTER,
                PlayerControlLayout.ANCHOR_TOP_END -> padding + size / 2f
                PlayerControlLayout.ANCHOR_TOP_START ->
                    padding + size / 2f + previewDp(64)
                PlayerControlLayout.ANCHOR_BOTTOM_START,
                PlayerControlLayout.ANCHOR_BOTTOM_CENTER -> height - padding - size / 2f - previewDp(13)
                PlayerControlLayout.ANCHOR_BOTTOM_END ->
                    height - padding - size / 2f - previewDp(13) - bottomEndRowOffset
                PlayerControlLayout.ANCHOR_MIDDLE_START,
                PlayerControlLayout.ANCHOR_MIDDLE_END -> (height - total) / 2f + size / 2f + index * (size + spacing)
                else -> height / 2f
            }
            return PointF(
                x.coerceIn(size / 2f, (width - size / 2f).coerceAtLeast(size / 2f)),
                y.coerceIn(size / 2f, (height - size / 2f).coerceAtLeast(size / 2f)),
            )
        }

        private fun previewUnit(): Float = if (width == 0) 0f else width / (360f * resources.displayMetrics.density)

        private fun actualControlScale(): Float = if (controlScaleIsAutomatic) {
            val unit = previewUnit()
            val simulatedPlayerHeight = if (unit > 0f) (height / unit).roundToInt() else 0
            PortraitPlayerControls.automaticControlScale(
                simulatedPlayerHeight,
                resources.displayMetrics.density,
            )
        } else {
            controlScale
        }

        private fun previewDp(value: Int): Int = (value * resources.displayMetrics.density * previewUnit()).roundToInt().coerceAtLeast(1)

        private fun panelBackground() = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.rgb(12, 15, 21))
        }

        private fun chipBackground() = GradientDrawable().apply {
            cornerRadius = previewDp(12).toFloat()
            setColor(Color.argb(170, 35, 42, 52))
            setStroke(previewDp(1), Color.argb(100, 255, 255, 255))
        }

        private fun transparentBackground() = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
        }

        private fun iconFor(action: String): Int = when (action) {
            "minimize" -> R.drawable.baseline_expand_more_black_48
            "download" -> R.drawable.ic_file_download_black_24dp
            "follow" -> R.drawable.baseline_favorite_border_black_24
            "quality" -> R.drawable.baseline_settings_black_24
            "speed" -> androidx.media3.ui.R.drawable.exo_ic_speed
            "sleep" -> R.drawable.baseline_alarm_black_24
            "aspect" -> R.drawable.baseline_aspect_ratio_black_24
            "chapters" -> R.drawable.baseline_format_list_bulleted_black_24
            "restart" -> R.drawable.baseline_replay_black_24
            "live" -> androidx.media3.ui.R.drawable.exo_icon_fastforward
            "clip" -> R.drawable.ic_movie_clip_black_24
            "volume" -> R.drawable.baseline_volume_up_black_24
            "compressor" -> R.drawable.baseline_audio_compressor_off_24dp
            "mode" -> R.drawable.baseline_audiotrack_black_24
            "subtitles" -> androidx.media3.ui.R.drawable.exo_ic_subtitle_off
            "chat_input" -> R.drawable.baseline_keyboard_black_24
            "chat" -> R.drawable.baseline_speaker_notes_black_24
            "fullscreen" -> R.drawable.baseline_fullscreen_black_24
            else -> R.drawable.baseline_more_vert_black_24
        }
    }

    }
