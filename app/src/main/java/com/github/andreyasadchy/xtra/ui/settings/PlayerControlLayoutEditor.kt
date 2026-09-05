package com.github.andreyasadchy.xtra.ui.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.PlayerControlLayout
import com.github.andreyasadchy.xtra.util.SettingsMigration
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import kotlin.math.hypot
import kotlin.math.roundToInt

class PlayerControlLayoutEditor(
    context: Context,
    initialItems: List<PlayerControlLayout.ControlPlacement>,
    private val labelFor: (String) -> String,
) : NestedScrollView(context) {

    private val items = initialItems.map { it.copy() }.toMutableList()
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(12))
    }
    private val preview = PreviewCanvas(context)
    private val previewHolder = PreviewHolder(context).apply {
        addView(
            preview,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            },
        )
    }
    private val selectionText = TextView(context)
    private val palette = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val moveSelectedButton = MaterialButton(context).apply {
        isAllCaps = false
        minHeight = dp(36)
        minimumHeight = dp(36)
        setPadding(dp(8), 0, dp(8), 0)
        visibility = View.GONE
        setOnClickListener { selectedAction?.let(::cycleGroup) }
    }
    private var selectedAction: String? = null

    companion object {
        fun showDialog(context: Context, onSaved: (String) -> Unit = {}) {
            val preferences = context.prefs()
            val items = PlayerControlLayout.controlPlacements(
                preferences.getString(C.SETTINGS_PLAYER_CONTROL_LAYOUT, null),
                SettingsMigration.defaultControlLayout(),
            )
            val editor = PlayerControlLayoutEditor(
                context = context,
                initialItems = items,
                labelFor = { action -> controlTitle(context, action) },
            )
            context.getAlertDialogBuilder()
                .setTitle(R.string.settings_customize_controls)
                .setView(editor)
                .setPositiveButton(R.string.settings_customize_controls_save) { _, _ ->
                    val serialized = editor.serializedLayout()
                    preferences.edit { putString(C.SETTINGS_PLAYER_CONTROL_LAYOUT, serialized) }
                    SettingsMigration.syncLegacyControlVisibility(preferences, serialized)
                    onSaved(serialized)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun controlTitle(context: Context, action: String): String = context.getString(
            when (action) {
                "minimize" -> R.string.player_minimize
                "download" -> R.string.player_download
                "follow" -> R.string.player_follow
                "quality" -> R.string.player_quality
                "speed" -> R.string.player_playback_speed
                "chapters" -> R.string.player_vod_games
                "restart" -> R.string.player_restart
                "live" -> R.string.player_seek_live
                "clip" -> R.string.player_clip
                "volume" -> R.string.player_volume
                "compressor" -> R.string.player_audio_compressor
                "mode" -> R.string.settings_player_mode
                "subtitles" -> R.string.player_subtitles
                "chat_input" -> R.string.player_chat_input
                "chat" -> R.string.player_show_chat
                "fullscreen" -> R.string.fullscreen
                "viewers" -> R.string.viewer_list
                "bookmark" -> R.string.bookmark
                "share" -> R.string.share
                "find_vod" -> R.string.find_unlisted_video
                "sleep" -> R.string.sleep_timer
                "aspect" -> R.string.aspect_ratio
                "reload_emotes" -> R.string.reload_emotes
                "disconnect_chat" -> R.string.disconnect_chat
                "video_info" -> R.string.video_info
                else -> return action
            },
        )
    }

    init {
        isFillViewport = true
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val instruction = TextView(context).apply {
            setText(R.string.settings_customize_controls_help)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColor(android.R.attr.textColorSecondary, Color.GRAY))
            setPadding(0, dp(6), 0, dp(10))
        }
        content.addView(instruction)
        content.addView(
            previewHolder,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            },
        )

        val selectionRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        selectionText.apply {
            text = context.getString(R.string.settings_customize_controls_selected_none)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(themeColor(android.R.attr.textColorSecondary, Color.GRAY))
        }
        selectionRow.addView(
            selectionText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val resetButton = MaterialButton(context).apply {
            text = context.getString(R.string.settings_customize_controls_reset)
            isAllCaps = false
            minHeight = dp(36)
            minimumHeight = dp(36)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { resetLayout() }
        }
        selectionRow.addView(resetButton)
        content.addView(
            selectionRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            },
        )
        content.addView(
            moveSelectedButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            },
        )
        content.addView(palette)
        refresh()
    }

    fun serializedLayout(): String = PlayerControlLayout.serializeControlLayout(items)

    private fun resetLayout() {
        val defaults = PlayerControlLayout.controlPlacements(
            null,
            SettingsMigration.defaultControlLayout(),
        )
        items.clear()
        items.addAll(defaults.map { it.copy(anchor = PlayerControlLayout.defaultAnchor(it.action)) })
        selectedAction = null
        refresh()
    }

    private fun cycleGroup(action: String) {
        val item = items.firstOrNull { it.action == action } ?: return
        item.group = when (item.group) {
            PlayerControlLayout.GROUP_QUICK -> if (PlayerControlLayout.canMenu(action)) {
                PlayerControlLayout.GROUP_MENU
            } else {
                PlayerControlLayout.GROUP_HIDDEN
            }
            PlayerControlLayout.GROUP_MENU -> PlayerControlLayout.GROUP_HIDDEN
            else -> when {
                PlayerControlLayout.canQuick(action) -> PlayerControlLayout.GROUP_QUICK
                PlayerControlLayout.canMenu(action) -> PlayerControlLayout.GROUP_MENU
                else -> PlayerControlLayout.GROUP_HIDDEN
            }
        }
        if (item.group == PlayerControlLayout.GROUP_QUICK && item.anchor !in PlayerControlLayout.anchors) {
            item.anchor = PlayerControlLayout.defaultAnchor(action)
        }
        selectedAction = action
        refresh()
    }

    private fun refresh() {
        preview.refreshControls()
        palette.removeAllViews()
        addPaletteSection(
            R.string.settings_control_group_menu,
            items.filter { it.group == PlayerControlLayout.GROUP_MENU },
        )
        addPaletteSection(
            R.string.settings_control_group_hidden,
            items.filter { it.group == PlayerControlLayout.GROUP_HIDDEN },
        )
        selectionText.text = selectedAction?.let { action ->
            context.getString(R.string.settings_customize_controls_selected, labelFor(action))
        } ?: context.getString(R.string.settings_customize_controls_selected_none)
        val selectedItem = selectedAction?.let { action -> items.firstOrNull { it.action == action } }
        moveSelectedButton.visibility = if (selectedItem == null) View.GONE else View.VISIBLE
        selectedItem?.let { item ->
            val destination = when {
                item.group == PlayerControlLayout.GROUP_QUICK && PlayerControlLayout.canMenu(item.action) ->
                    R.string.settings_customize_controls_enable_menu
                item.group == PlayerControlLayout.GROUP_QUICK -> R.string.settings_customize_controls_hide
                item.group == PlayerControlLayout.GROUP_MENU -> R.string.settings_customize_controls_hide
                PlayerControlLayout.canQuick(item.action) -> R.string.settings_customize_controls_enable
                else -> R.string.settings_customize_controls_enable_menu
            }
            moveSelectedButton.text = context.getString(destination, labelFor(item.action))
            moveSelectedButton.contentDescription = moveSelectedButton.text
        }
    }

    private fun addPaletteSection(titleRes: Int, sectionItems: List<PlayerControlLayout.ControlPlacement>) {
        val header = TextView(context).apply {
            text = context.getString(titleRes)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(themeColor(android.R.attr.textColorSecondary, Color.GRAY))
            setPadding(0, dp(5), 0, dp(4))
        }
        palette.addView(header)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (sectionItems.isEmpty()) {
            row.addView(TextView(context).apply {
                text = context.getString(R.string.settings_customize_controls_empty)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(themeColor(android.R.attr.textColorTertiary, Color.GRAY))
                setPadding(0, dp(4), 0, dp(8))
            })
        } else {
            sectionItems.forEach { item ->
                row.addView(
                    paletteButton(item),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply {
                        marginEnd = dp(6)
                    },
                )
            }
        }
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        palette.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
                bottomMargin = dp(3)
            },
        )
    }

    private fun paletteButton(item: PlayerControlLayout.ControlPlacement): MaterialButton = MaterialButton(context).apply {
        text = labelFor(item.action)
        isAllCaps = false
        minHeight = dp(38)
        minimumHeight = dp(38)
        setPadding(dp(10), 0, dp(10), 0)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(themeColor(android.R.attr.textColorPrimary, Color.WHITE))
        backgroundTintList = ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.DKGRAY))
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorOutline, Color.GRAY))
        contentDescription = context.getString(
            when {
                item.group == PlayerControlLayout.GROUP_HIDDEN && PlayerControlLayout.canQuick(item.action) ->
                    R.string.settings_customize_controls_enable
                item.group == PlayerControlLayout.GROUP_HIDDEN ->
                    R.string.settings_customize_controls_enable_menu
                else -> R.string.settings_customize_controls_hide
            },
            labelFor(item.action),
        )
        setOnClickListener { cycleGroup(item.action) }
    }

    private inner class PreviewHolder(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
            val previewWidth = availableWidth.coerceAtMost(dp(720))
            val previewHeight = (previewWidth * 9f / 16f).roundToInt()
            measureChild(
                preview,
                MeasureSpec.makeMeasureSpec(previewWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(previewHeight, MeasureSpec.EXACTLY),
            )
            setMeasuredDimension(availableWidth, previewHeight)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val previewWidth = width.coerceAtMost(dp(720))
            val previewHeight = (previewWidth * 9f / 16f).roundToInt()
            val previewLeft = (width - previewWidth) / 2
            preview.layout(previewLeft, 0, previewLeft + previewWidth, previewHeight)
        }
    }

    private inner class PreviewCanvas(context: Context) : FrameLayout(context) {

        private val chips = linkedMapOf<String, ImageButton>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
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
        private var draggingAction: String? = null
        private var dragStartX = 0f
        private var dragStartY = 0f
        private var chipStartX = 0f
        private var chipStartY = 0f
        private var isDragging = false

        init {
            setWillNotDraw(false)
            clipChildren = false
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.rgb(12, 15, 21))
            }
            clipToOutline = true
            addView(play, LayoutParams(dp(48), dp(48)))
            addView(menu, LayoutParams(dp(42), dp(42)))
            contentDescription = context.getString(R.string.settings_customize_controls_preview_description)
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            post { positionChildren() }
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            positionChildren()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(80, 105, 125, 155)
            canvas.drawRect(0f, height * 0.31f, width.toFloat(), height * 0.32f, paint)
            canvas.drawRect(0f, height * 0.67f, width.toFloat(), height * 0.68f, paint)

            paint.color = Color.WHITE
            paint.alpha = 220
            paint.textSize = dp(12).toFloat()
            paint.typeface = Typeface.DEFAULT_BOLD
            val topStartCount = items.count {
                it.group == PlayerControlLayout.GROUP_QUICK && it.anchor == PlayerControlLayout.ANCHOR_TOP_START
            }
            val infoStart = if (topStartCount > 0) {
                dp(9 + topStartCount * 44 + (topStartCount - 1).coerceAtLeast(0) * 6 + 6)
            } else {
                dp(12)
            }
            canvas.drawText("LIVE PREVIEW", infoStart.toFloat(), dp(22).toFloat(), paint)
            paint.alpha = 170
            paint.typeface = Typeface.DEFAULT
            paint.textSize = dp(13).toFloat()
            canvas.drawText("channel name", infoStart.toFloat(), dp(42).toFloat(), paint)

            paint.color = Color.argb(140, 255, 255, 255)
            canvas.drawRoundRect(
                dp(44).toFloat(),
                (height - dp(13)).toFloat(),
                (width - dp(44)).toFloat(),
                (height - dp(10)).toFloat(),
                dp(2).toFloat(),
                dp(2).toFloat(),
                paint,
            )
            paint.color = themeColor(androidx.appcompat.R.attr.colorPrimary, Color.WHITE)
            canvas.drawRoundRect(
                dp(44).toFloat(),
                (height - dp(13)).toFloat(),
                (width * 0.38f).coerceAtLeast(dp(45).toFloat()),
                (height - dp(10)).toFloat(),
                dp(2).toFloat(),
                dp(2).toFloat(),
                paint,
            )
            paint.color = Color.WHITE
            paint.alpha = 170
            paint.textSize = dp(10).toFloat()
            canvas.drawText("12:16", dp(10).toFloat(), (height - dp(20)).toFloat(), paint)
            canvas.drawText("1:12:16", (width - dp(47)).toFloat(), (height - dp(20)).toFloat(), paint)

            if (draggingAction != null) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1).toFloat()
                paint.color = themeColor(androidx.appcompat.R.attr.colorPrimary, Color.WHITE)
                paint.alpha = 180
                PlayerControlLayout.anchors.forEach { anchor ->
                    val point = anchorPoint(anchor)
                    canvas.drawCircle(point.x, point.y, dp(8).toFloat(), paint)
                }
                paint.style = Paint.Style.FILL
                paint.alpha = 110
                canvas.drawText(
                    context.getString(R.string.settings_customize_controls_snap_hint),
                    dp(12).toFloat(),
                    (height - dp(29)).toFloat(),
                    paint,
                )
            }
        }

        fun refreshControls() {
            chips.values.toList().forEach { removeView(it) }
            chips.clear()
            items.filter { it.group == PlayerControlLayout.GROUP_QUICK }.forEach { item ->
                val chip = ImageButton(context).apply {
                    setImageResource(iconFor(item.action))
                    imageTintList = ColorStateList.valueOf(Color.WHITE)
                    background = chipBackground(item.action == selectedAction)
                    setPadding(dp(9), dp(9), dp(9), dp(9))
                    contentDescription = context.getString(
                        R.string.settings_customize_controls_drag_action,
                        labelFor(item.action),
                    )
                    setOnTouchListener { _, event -> handleChipTouch(item.action, this, event) }
                }
                chips[item.action] = chip
                addView(chip, LayoutParams(dp(44), dp(44)))
            }
            // The More menu also contains the in-player customization entry, so keep
            // the affordance visible even when every other action is hidden.
            menu.visibility = View.VISIBLE
            positionChildren()
            invalidate()
        }

        private fun handleChipTouch(action: String, chip: ImageButton, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    selectAction(action)
                    draggingAction = action
                    isDragging = false
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    chipStartX = chip.x
                    chipStartY = chip.y
                    chip.bringToFront()
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartX
                    val dy = event.rawY - dragStartY
                    if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        chip.x = (chipStartX + dx).coerceIn(0f, (width - chip.width).coerceAtLeast(0).toFloat())
                        chip.y = (chipStartY + dy).coerceIn(0f, (height - chip.height).coerceAtLeast(0).toFloat())
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        snapChip(action, chip.x + chip.width / 2f, chip.y + chip.height / 2f)
                    } else {
                        selectAction(action)
                    }
                    draggingAction = null
                    isDragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    chip.x = chipStartX
                    chip.y = chipStartY
                    draggingAction = null
                    isDragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }
            }
            return true
        }

        private fun selectAction(action: String) {
            selectedAction = action
            chips.forEach { (chipAction, control) ->
                control.background = chipBackground(chipAction == selectedAction)
            }
            selectionText.text = context.getString(
                R.string.settings_customize_controls_selected,
                labelFor(action),
            )
            val item = items.firstOrNull { it.action == action }
            moveSelectedButton.visibility = if (item == null) View.GONE else View.VISIBLE
            item?.let {
                val destination = when {
                    it.group == PlayerControlLayout.GROUP_QUICK && PlayerControlLayout.canMenu(it.action) ->
                        R.string.settings_customize_controls_enable_menu
                    it.group == PlayerControlLayout.GROUP_QUICK -> R.string.settings_customize_controls_hide
                    it.group == PlayerControlLayout.GROUP_MENU -> R.string.settings_customize_controls_hide
                    PlayerControlLayout.canQuick(it.action) -> R.string.settings_customize_controls_enable
                    else -> R.string.settings_customize_controls_enable_menu
                }
                moveSelectedButton.text = context.getString(destination, labelFor(it.action))
                moveSelectedButton.contentDescription = moveSelectedButton.text
            }
            invalidate()
        }

        private fun snapChip(action: String, centerX: Float, centerY: Float) {
            val item = items.firstOrNull { it.action == action } ?: return
            val anchor = PlayerControlLayout.anchors.minByOrNull { candidate ->
                val point = anchorPoint(candidate)
                hypot((point.x - centerX).toDouble(), (point.y - centerY).toDouble())
            } ?: PlayerControlLayout.defaultAnchor(action)
            items.remove(item)
            item.anchor = anchor
            val sameAnchor = items.filter {
                it.group == PlayerControlLayout.GROUP_QUICK && it.anchor == anchor
            }
            val crossCoordinate = if (anchor.startsWith("middle")) centerY else centerX
            val insertBefore = sameAnchor.firstOrNull { other ->
                val index = sameAnchor.indexOf(other)
                crossCoordinateFor(anchor, index, sameAnchor.size) > crossCoordinate
            }
            val targetIndex = insertBefore?.let { items.indexOf(it) } ?: items.size
            items.add(targetIndex, item)
            positionChildren()
            invalidate()
        }

        private fun positionChildren() {
            val grouped = items.filter { it.group == PlayerControlLayout.GROUP_QUICK }.groupBy { it.anchor }
            grouped.forEach { (anchor, group) ->
                group.forEachIndexed { index, item ->
                    chips[item.action]?.let { chip ->
                        val point = controlPoint(anchor, index, group.size)
                        chip.x = point.x - chip.width / 2f
                        chip.y = point.y - chip.height / 2f
                    }
                }
            }
            menu.x = (width - dp(10) - menu.width).coerceAtLeast(0).toFloat()
            menu.y = dp(4).toFloat()
            play.x = (width - play.width) / 2f
            play.y = (height - play.height) / 2f
        }

        private fun controlPoint(anchor: String, index: Int, count: Int): PointF {
            val size = dp(44)
            val spacing = dp(6)
            val padding = dp(9)
            val menuReserve = if (menu.visibility == View.VISIBLE && anchor == PlayerControlLayout.ANCHOR_TOP_END) dp(48) else 0
            val total = count * size + (count - 1).coerceAtLeast(0) * spacing
            val x = when (anchor) {
                PlayerControlLayout.ANCHOR_TOP_START, PlayerControlLayout.ANCHOR_BOTTOM_START -> padding + size / 2f + index * (size + spacing)
                PlayerControlLayout.ANCHOR_TOP_CENTER, PlayerControlLayout.ANCHOR_BOTTOM_CENTER -> (width - total) / 2f + size / 2f + index * (size + spacing)
                PlayerControlLayout.ANCHOR_TOP_END, PlayerControlLayout.ANCHOR_BOTTOM_END -> width - padding - menuReserve - total + size / 2f + index * (size + spacing)
                PlayerControlLayout.ANCHOR_MIDDLE_START, PlayerControlLayout.ANCHOR_MIDDLE_END -> if (anchor.endsWith("start")) padding + size / 2f else width - padding - size / 2f
                else -> width / 2f
            }
            val y = when (anchor) {
                PlayerControlLayout.ANCHOR_TOP_START, PlayerControlLayout.ANCHOR_TOP_CENTER, PlayerControlLayout.ANCHOR_TOP_END -> padding + size / 2f
                PlayerControlLayout.ANCHOR_BOTTOM_START, PlayerControlLayout.ANCHOR_BOTTOM_CENTER, PlayerControlLayout.ANCHOR_BOTTOM_END -> height - padding - size / 2f - dp(13)
                PlayerControlLayout.ANCHOR_MIDDLE_START, PlayerControlLayout.ANCHOR_MIDDLE_END -> (height - total) / 2f + size / 2f + index * (size + spacing)
                else -> height / 2f
            }
            return PointF(x.coerceIn(size / 2f, (width - size / 2f).coerceAtLeast(size / 2f)), y.coerceIn(size / 2f, (height - size / 2f).coerceAtLeast(size / 2f)))
        }

        private fun crossCoordinateFor(anchor: String, index: Int, count: Int): Float {
            val point = controlPoint(anchor, index, count)
            return if (anchor.startsWith("middle")) point.y else point.x
        }

        private fun anchorPoint(anchor: String): PointF = controlPoint(anchor, 0, 1)

        private fun chipBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (selected) {
                themeColor(androidx.appcompat.R.attr.colorPrimary, Color.WHITE)
            } else {
                Color.argb(170, 35, 42, 52)
            })
            setStroke(dp(1), if (selected) Color.WHITE else Color.argb(100, 255, 255, 255))
        }

        private fun transparentBackground(): GradientDrawable = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
        }
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

    private fun themeColor(attribute: Int, fallback: Int): Int {
        val value = android.util.TypedValue()
        if (!context.theme.resolveAttribute(attribute, value, true)) return fallback
        return if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
