package com.github.andreyasadchy.xtra.ui.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.widget.NestedScrollView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.PlayerControlLayout
import com.github.andreyasadchy.xtra.util.SettingsMigration
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.button.MaterialButton
import kotlin.math.roundToInt

/** Edits the same anchored control model used by the runtime player. */
class PlayerControlLayoutEditor(
    context: Context,
    initialItems: List<PlayerControlLayout.ControlPlacement>,
    private val labelFor: (String) -> String,
) : NestedScrollView(context) {

    private val items = initialItems.map { it.copy() }.toMutableList()
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(4), dp(20), dp(16))
    }
    private val preview = PlayerControlPreviewView(context, dragEnabled = true, labelFor = labelFor)
    private val selectionText = TextView(context)
    private val palette = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val moveSelectedButton = MaterialButton(context).apply {
        isAllCaps = false
        minHeight = dp(40)
        minimumHeight = dp(40)
        visibility = View.GONE
        setOnClickListener { selectedAction?.let(::cycleGroup) }
    }
    private var selectedAction: String? = null

    companion object {
        fun showDialog(context: Context, onSaved: (String) -> Unit = {}) {
            val preferences = context.prefs()
            val editor = PlayerControlLayoutEditor(
                context,
                PlayerControlLayout.controlPlacements(
                    preferences.getString(C.SETTINGS_PLAYER_CONTROL_LAYOUT, null),
                    SettingsMigration.defaultControlLayout(),
                ),
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
                "metadata" -> R.string.settings_player_control_metadata
                "timeline" -> R.string.settings_player_control_timeline
                "play_pause" -> R.string.player_play
                "rewind" -> R.string.player_rewind
                "fast_forward" -> R.string.player_fast_forward
                "interaction_lock" -> R.string.player_lock_controls
                "menu" -> R.string.player_more_options
                "minimize" -> R.string.player_minimize
                "download" -> R.string.player_download
                "follow" -> R.string.player_follow
                "quality" -> R.string.player_quality
                "speed" -> R.string.player_playback_speed
                "chapters" -> R.string.player_vod_games
                "restart" -> R.string.player_restart
                "live" -> R.string.player_seek_live
                "live_captions" -> R.string.player_live_captions
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
        clipToPadding = false
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        content.addView(TextView(context).apply {
            setText(R.string.settings_customize_controls_help)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColor(android.R.attr.textColorSecondary, Color.GRAY))
            setPadding(0, dp(6), 0, dp(12))
        })
        content.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })
        preview.setSelectionListener { action ->
            selectedAction = action
            updateSelectionUi()
        }

        val selectionRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        selectionText.apply {
            setText(R.string.settings_customize_controls_selected_none)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(themeColor(android.R.attr.textColorSecondary, Color.GRAY))
        }
        selectionRow.addView(selectionText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        selectionRow.addView(MaterialButton(context).apply {
            text = context.getString(R.string.settings_customize_controls_reset)
            isAllCaps = false
            minHeight = dp(40)
            minimumHeight = dp(40)
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { resetLayout() }
        })
        content.addView(selectionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
        content.addView(moveSelectedButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
        content.addView(palette)
        refresh()
    }

    fun serializedLayout(): String = PlayerControlLayout.serializeControlLayout(items)

    private fun resetLayout() {
        items.clear()
        items.addAll(PlayerControlLayout.controlPlacements(null, SettingsMigration.defaultControlLayout()).map {
            it.copy(anchor = PlayerControlLayout.defaultAnchor(it.action))
        })
        selectedAction = null
        refresh()
    }

    private fun cycleGroup(action: String) {
        val item = items.firstOrNull { it.action == action } ?: return
        item.group = when (item.group) {
            PlayerControlLayout.GROUP_QUICK -> if (PlayerControlLayout.canMenu(action)) PlayerControlLayout.GROUP_MENU else PlayerControlLayout.GROUP_HIDDEN
            PlayerControlLayout.GROUP_MENU -> PlayerControlLayout.GROUP_HIDDEN
            else -> when {
                PlayerControlLayout.canQuick(action) -> PlayerControlLayout.GROUP_QUICK
                PlayerControlLayout.canMenu(action) -> PlayerControlLayout.GROUP_MENU
                else -> PlayerControlLayout.GROUP_HIDDEN
            }
        }
        if (item.group == PlayerControlLayout.GROUP_QUICK && item.anchor !in PlayerControlLayout.validAnchors(action)) {
            item.anchor = PlayerControlLayout.defaultAnchor(action)
        }
        selectedAction = action
        refresh()
    }

    private fun refresh() {
        preview.setItems(items, selectedAction) { updated ->
            items.clear()
            items.addAll(updated.map { it.copy() })
            refreshPaletteOnly()
        }
        refreshPaletteOnly()
    }

    private fun refreshPaletteOnly() {
        palette.removeAllViews()
        addPaletteSection(R.string.settings_control_group_menu, items.filter { it.group == PlayerControlLayout.GROUP_MENU })
        addPaletteSection(R.string.settings_control_group_hidden, items.filter { it.group == PlayerControlLayout.GROUP_HIDDEN })
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        selectionText.text = selectedAction?.let { context.getString(R.string.settings_customize_controls_selected, labelFor(it)) }
            ?: context.getString(R.string.settings_customize_controls_selected_none)
        val selectedItem = selectedAction?.let { action -> items.firstOrNull { it.action == action } }
        moveSelectedButton.visibility = if (selectedItem == null) View.GONE else View.VISIBLE
        selectedItem?.let { item ->
            val destination = when {
                item.group == PlayerControlLayout.GROUP_QUICK && PlayerControlLayout.canMenu(item.action) -> R.string.settings_customize_controls_enable_menu
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
        palette.addView(TextView(context).apply {
            text = context.getString(titleRes)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(themeColor(android.R.attr.textColorSecondary, Color.GRAY))
            setPadding(0, dp(6), 0, dp(4))
        })
        val row = PaletteWrapLayout(context)
        if (sectionItems.isEmpty()) {
            row.addView(TextView(context).apply {
                text = context.getString(R.string.settings_customize_controls_empty)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(themeColor(android.R.attr.textColorTertiary, Color.GRAY))
                setPadding(0, dp(4), 0, dp(8))
            })
        } else {
            sectionItems.forEach { item -> row.addView(paletteButton(item)) }
        }
        palette.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })
    }

    private fun paletteButton(item: PlayerControlLayout.ControlPlacement): MaterialButton = MaterialButton(context).apply {
        text = labelFor(item.action)
        isAllCaps = false
        minWidth = dp(96)
        minimumWidth = dp(96)
        minHeight = dp(40)
        minimumHeight = dp(40)
        setPadding(dp(10), 0, dp(10), 0)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(themeColor(android.R.attr.textColorPrimary, Color.WHITE))
        backgroundTintList = ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.DKGRAY))
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorOutline, Color.GRAY))
        contentDescription = context.getString(
            when {
                item.group == PlayerControlLayout.GROUP_HIDDEN && PlayerControlLayout.canQuick(item.action) -> R.string.settings_customize_controls_enable
                item.group == PlayerControlLayout.GROUP_HIDDEN -> R.string.settings_customize_controls_enable_menu
                else -> R.string.settings_customize_controls_hide
            },
            labelFor(item.action),
        )
        setOnClickListener { cycleGroup(item.action) }
    }

    private fun themeColor(attribute: Int, fallback: Int): Int {
        val value = android.util.TypedValue()
        if (!context.theme.resolveAttribute(attribute, value, true)) return fallback
        return if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
    }

    private inner class PaletteWrapLayout(context: Context) : ViewGroup(context) {
        private val gap = dp(6)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = (View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(1)
            var lineWidth = 0
            var lineHeight = 0
            var totalHeight = paddingTop + paddingBottom
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                measureChild(child, View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST), heightMeasureSpec)
                val nextWidth = if (lineWidth == 0) child.measuredWidth else lineWidth + gap + child.measuredWidth
                if (lineWidth > 0 && nextWidth > available) {
                    totalHeight += lineHeight + gap
                    lineWidth = child.measuredWidth
                    lineHeight = child.measuredHeight
                } else {
                    lineWidth = nextWidth
                    lineHeight = maxOf(lineHeight, child.measuredHeight)
                }
            }
            if (lineWidth > 0) totalHeight += lineHeight
            setMeasuredDimension(resolveSize(View.MeasureSpec.getSize(widthMeasureSpec), widthMeasureSpec), resolveSize(totalHeight, heightMeasureSpec))
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val available = (width - paddingLeft - paddingRight).coerceAtLeast(1)
            var x = paddingLeft
            var y = paddingTop
            var lineHeight = 0
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (x > paddingLeft && x + child.measuredWidth > paddingLeft + available) {
                    x = paddingLeft
                    y += lineHeight + gap
                    lineHeight = 0
                }
                child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
                x += child.measuredWidth + gap
                lineHeight = maxOf(lineHeight, child.measuredHeight)
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
