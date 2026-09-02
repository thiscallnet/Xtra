package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.roundToInt
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.isTelevision

enum class TvChatMode { HIDDEN, SIDE_PANEL, OVERLAY }

enum class TvChatOverlayAnchor {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    CENTER_LEFT, CENTER, CENTER_RIGHT,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT,
}

enum class TvChatOverlayPreset { AUTO, COMPACT, STANDARD, LARGE, FULL_HEIGHT, CUSTOM }

data class TvChatOverlayConfig(
    val anchor: TvChatOverlayAnchor = TvChatOverlayAnchor.TOP_RIGHT,
    val widthPercent: Int = 34,
    val heightPercent: Int = 44,
    val opacityPercent: Int = 85,
    val preset: TvChatOverlayPreset = TvChatOverlayPreset.AUTO,
) {
    val safeWidthPercent get() = widthPercent.coerceIn(15, 70)
    val safeHeightPercent get() = heightPercent.coerceIn(15, 90)
    val safeOpacityPercent get() = opacityPercent.coerceIn(40, 100)
}

fun tvChatMode(value: String?): TvChatMode = runCatching {
    TvChatMode.valueOf(value.orEmpty().uppercase())
}.getOrDefault(TvChatMode.SIDE_PANEL)

fun tvChatAnchor(value: String?): TvChatOverlayAnchor = runCatching {
    TvChatOverlayAnchor.valueOf(value.orEmpty().uppercase())
}.getOrDefault(TvChatOverlayAnchor.TOP_RIGHT)

fun tvChatPreset(value: String?): TvChatOverlayPreset = runCatching {
    TvChatOverlayPreset.valueOf(value.orEmpty().uppercase())
}.getOrDefault(TvChatOverlayPreset.AUTO)

fun tvChatPresetConfig(preset: TvChatOverlayPreset): TvChatOverlayConfig = when (preset) {
    TvChatOverlayPreset.AUTO, TvChatOverlayPreset.STANDARD -> TvChatOverlayConfig()
    TvChatOverlayPreset.COMPACT -> TvChatOverlayConfig(widthPercent = 21, heightPercent = 28)
    TvChatOverlayPreset.LARGE -> TvChatOverlayConfig(widthPercent = 42, heightPercent = 60, opacityPercent = 88)
    TvChatOverlayPreset.FULL_HEIGHT -> TvChatOverlayConfig(
        anchor = TvChatOverlayAnchor.CENTER_RIGHT,
        widthPercent = 32,
        heightPercent = 78,
        opacityPercent = 90,
    )
    TvChatOverlayPreset.CUSTOM -> TvChatOverlayConfig(preset = TvChatOverlayPreset.CUSTOM)
}

internal fun tvSidePanelWidth(parentWidthPx: Int, configuredPercent: Int, minWidthPx: Int): Int {
    if (parentWidthPx <= 0) return 0
    val percent = configuredPercent.coerceIn(15, 50)
    return (parentWidthPx * percent / 100f).roundToInt()
        .coerceIn(minWidthPx.coerceAtMost(parentWidthPx), parentWidthPx)
}

internal fun tvChatSize(parentSizePx: Int, configuredPercent: Int, minSizePx: Int, maxPercent: Int): Int {
    if (parentSizePx <= 0) return 0
    val percent = configuredPercent.coerceIn(15, maxPercent)
    return (parentSizePx * percent / 100f).roundToInt()
        .coerceIn(minSizePx.coerceAtMost(parentSizePx), parentSizePx)
}

fun tvChatOverlayConfig(context: Context): TvChatOverlayConfig {
    val prefs = context.prefs()
    val preset = tvChatPreset(prefs.getString(C.TV_CHAT_OVERLAY_PRESET, null))
    val presetConfig = tvChatPresetConfig(preset)
    return if (preset == TvChatOverlayPreset.CUSTOM) {
        TvChatOverlayConfig(
            anchor = tvChatAnchor(prefs.getString(C.TV_CHAT_OVERLAY_ANCHOR, null)),
            widthPercent = prefs.getInt(C.TV_CHAT_OVERLAY_WIDTH_PERCENT, 34),
            heightPercent = prefs.getInt(C.TV_CHAT_OVERLAY_HEIGHT_PERCENT, 44),
            opacityPercent = prefs.getInt(C.TV_CHAT_OVERLAY_OPACITY, 85),
            preset = preset,
        )
    } else presetConfig.copy(preset = preset)
}

fun tvChatMode(context: Context): TvChatMode =
    tvChatMode(context.prefs().getString(C.TV_CHAT_MODE, null))

fun persistTvChatOverlayConfig(context: Context, config: TvChatOverlayConfig) {
    context.prefs().edit()
        .putString(C.TV_CHAT_OVERLAY_ANCHOR, config.anchor.name)
        .putInt(C.TV_CHAT_OVERLAY_WIDTH_PERCENT, config.safeWidthPercent)
        .putInt(C.TV_CHAT_OVERLAY_HEIGHT_PERCENT, config.safeHeightPercent)
        .putInt(C.TV_CHAT_OVERLAY_OPACITY, config.safeOpacityPercent)
        .putString(C.TV_CHAT_OVERLAY_PRESET, config.preset.name)
        .apply()
}

fun applyTvChatOverlayLayout(container: View, parent: ViewGroup, config: TvChatOverlayConfig) {
    if (!container.context.isTelevision()) return
    if (parent.width <= 0 || parent.height <= 0) {
        parent.post { applyTvChatOverlayLayout(container, parent, config) }
        return
    }
    val width = tvChatSize(parent.width, config.safeWidthPercent,
        container.resources.getDimensionPixelSize(R.dimen.tv_chat_overlay_min_width), 70)
    val height = tvChatSize(parent.height, config.safeHeightPercent,
        container.resources.getDimensionPixelSize(R.dimen.tv_chat_overlay_min_height), 90)
    val gravity = when (config.anchor) {
        TvChatOverlayAnchor.TOP_LEFT -> Gravity.TOP or Gravity.START
        TvChatOverlayAnchor.TOP_CENTER -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
        TvChatOverlayAnchor.TOP_RIGHT -> Gravity.TOP or Gravity.END
        TvChatOverlayAnchor.CENTER_LEFT -> Gravity.CENTER_VERTICAL or Gravity.START
        TvChatOverlayAnchor.CENTER -> Gravity.CENTER
        TvChatOverlayAnchor.CENTER_RIGHT -> Gravity.CENTER_VERTICAL or Gravity.END
        TvChatOverlayAnchor.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
        TvChatOverlayAnchor.BOTTOM_CENTER -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        TvChatOverlayAnchor.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
    }
    container.layoutParams = FrameLayout.LayoutParams(width, height).apply {
        this.gravity = gravity
    }
    container.alpha = 1f
    container.background?.alpha = (config.safeOpacityPercent * 255 / 100f).toInt()
    container.elevation = container.resources.displayMetrics.density * 10f
}

fun applyTvChatPresentation(
    chat: View,
    player: View,
    parent: ViewGroup,
    visible: Boolean,
) {
    if (!parent.context.isTelevision()) return
    if (parent.width <= 0 || parent.height <= 0) {
        parent.post { applyTvChatPresentation(chat, player, parent, visible) }
        return
    }
    val mode = tvChatMode(parent.context)
    val sideWidth = tvSidePanelWidth(
        parent.width,
        parent.context.prefs().getInt(C.TV_CHAT_SIDE_PANEL_WIDTH_PERCENT, 25),
        parent.resources.getDimensionPixelSize(R.dimen.tv_chat_side_min_width),
    )
    val playerParams = player.layoutParams as? FrameLayout.LayoutParams ?: return

    if (!visible || mode == TvChatMode.HIDDEN) {
        chat.visibility = View.GONE
        playerParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        playerParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        playerParams.marginEnd = 0
        player.layoutParams = playerParams
        return
    }

    if (chat is ViewGroup) TvFocusHelper.disableDescendantFocus(chat)
    if (chat is ViewGroup) chat.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

    when (mode) {
        TvChatMode.SIDE_PANEL -> {
            playerParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            playerParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            playerParams.marginEnd = sideWidth
            player.layoutParams = playerParams
            chat.background = chat.context.getDrawable(R.drawable.tv_chat_side_background)
            chat.alpha = 1f
            chat.visibility = View.VISIBLE
            chat.layoutParams = FrameLayout.LayoutParams(sideWidth, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.END
            }
        }
        TvChatMode.OVERLAY -> {
            playerParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            playerParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            playerParams.marginEnd = 0
            player.layoutParams = playerParams
            chat.background = chat.context.getDrawable(R.drawable.tv_chat_overlay_background)
            chat.alpha = 1f
            chat.visibility = View.VISIBLE
            chat.post { applyTvChatOverlayLayout(chat, parent, tvChatOverlayConfig(parent.context)) }
        }
        TvChatMode.HIDDEN -> Unit
    }
}
