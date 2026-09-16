package com.github.andreyasadchy.xtra.ui.chat.v2.presentation

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.github.andreyasadchy.xtra.R
import kotlin.math.pow

/**
 * Resolves the semantic fill and the readable rail used by an inline event row.
 *
 * The legacy chat colors are not all the same kind of token: some are opaque accents,
 * while others are already translucent row fills. Keep those meanings separate here so
 * the new event drawable does not turn a fill color into a barely visible rail.
 */
data class ChatEventPalette(
    @ColorInt val surfaceColor: Int,
    @ColorInt val railColor: Int,
)

internal fun resolveChatEventPalette(
    kind: ChatEventKind,
    @ColorInt baseColor: Int,
    colorFor: (Int) -> Int,
): ChatEventPalette {
    val fillColor = colorFor(chatEventFillAttribute(kind))
    val surfaceColor = when (kind) {
        ChatEventKind.WATCH_STREAK,
        ChatEventKind.SUBSCRIPTION,
        -> blendChatEventSurface(baseColor, fillColor, EVENT_SURFACE_ALPHA)

        ChatEventKind.CHANNEL_POINTS,
        ChatEventKind.HIGHLIGHT,
        ChatEventKind.FIRST_CHATTER,
        ChatEventKind.ANNOUNCEMENT,
        ChatEventKind.RAID,
        ChatEventKind.NOTICE,
        -> ColorUtils.compositeColors(fillColor, baseColor)
    }
    val railSource = colorFor(chatEventRailAttribute(kind))

    return ChatEventPalette(
        surfaceColor = surfaceColor,
        railColor = readableChatEventRailColor(
            color = ColorUtils.setAlphaComponent(railSource, 0xFF),
            background = surfaceColor,
        ),
    )
}

internal fun chatEventFillAttribute(kind: ChatEventKind): Int =
    when (kind) {
        ChatEventKind.WATCH_STREAK -> R.attr.chatEventStreakAccentColor
        ChatEventKind.SUBSCRIPTION -> R.attr.chatMessageSubscriptionAccentColor
        ChatEventKind.CHANNEL_POINTS -> R.attr.chatMessageRewardColor
        ChatEventKind.HIGHLIGHT -> R.attr.chatMessageHighlightColor
        ChatEventKind.FIRST_CHATTER -> R.attr.chatMessageFirstChatterColor
        ChatEventKind.ANNOUNCEMENT,
        ChatEventKind.RAID,
        ChatEventKind.NOTICE,
        -> R.attr.chatMessageNoticeColor
    }

private fun chatEventRailAttribute(kind: ChatEventKind): Int =
    when (kind) {
        ChatEventKind.WATCH_STREAK -> R.attr.chatEventStreakAccentColor
        ChatEventKind.SUBSCRIPTION -> R.attr.chatMessageSubscriptionAccentColor
        ChatEventKind.HIGHLIGHT,
        ChatEventKind.FIRST_CHATTER,
        -> R.attr.chatMessageSpecialAccentColor

        ChatEventKind.CHANNEL_POINTS,
        ChatEventKind.ANNOUNCEMENT,
        ChatEventKind.RAID,
        ChatEventKind.NOTICE,
        -> chatEventFillAttribute(kind)
    }

private const val EVENT_SURFACE_ALPHA = 0x18
private const val EVENT_RAIL_MIN_CONTRAST = 4.5

private fun blendChatEventSurface(
    @ColorInt baseColor: Int,
    @ColorInt overlayColor: Int,
    overlayAlpha: Int,
): Int {
    val alpha = overlayAlpha.coerceIn(0, 255)
    fun component(shift: Int): Int {
        val base = baseColor ushr shift and 0xFF
        val overlay = overlayColor ushr shift and 0xFF
        return (base * (255 - alpha) + overlay * alpha) / 255
    }
    return 0xFF000000.toInt() or
        (component(16) shl 16) or
        (component(8) shl 8) or
        component(0)
}

@ColorInt
private fun readableChatEventRailColor(
    @ColorInt color: Int,
    @ColorInt background: Int,
): Int {
    if (contrastRatio(color, background) >= EVENT_RAIL_MIN_CONTRAST) return color

    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color, hsl)
    val darken = isLight(background)
    val preferred = adjustRailLightness(
        hsl = hsl.copyOf(),
        background = background,
        minimumContrast = EVENT_RAIL_MIN_CONTRAST,
        towardBlack = darken,
    )
    val alternative = adjustRailLightness(
        hsl = hsl.copyOf(),
        background = background,
        minimumContrast = EVENT_RAIL_MIN_CONTRAST,
        towardBlack = !darken,
    )
    return listOfNotNull(preferred, alternative)
        .maxByOrNull { contrastRatio(it, background) }
        ?: if (darken) Color.BLACK else Color.WHITE
}

@ColorInt
private fun adjustRailLightness(
    hsl: FloatArray,
    @ColorInt background: Int,
    minimumContrast: Double,
    towardBlack: Boolean,
): Int? {
    val originalLightness = hsl[2]
    val endpointLightness = if (towardBlack) 0f else 1f
    hsl[2] = endpointLightness
    val endpoint = ColorUtils.HSLToColor(hsl)
    if (contrastRatio(endpoint, background) < minimumContrast) return null

    var low = if (towardBlack) 0f else originalLightness
    var high = if (towardBlack) originalLightness else 1f
    var best = endpoint
    repeat(12) {
        val lightness = (low + high) / 2f
        hsl[2] = lightness
        val candidate = ColorUtils.HSLToColor(hsl)
        if (contrastRatio(candidate, background) >= minimumContrast) {
            best = candidate
            if (towardBlack) {
                low = lightness
            } else {
                high = lightness
            }
        } else {
            if (towardBlack) {
                high = lightness
            } else {
                low = lightness
            }
        }
    }
    return best
}

private fun contrastRatio(@ColorInt foreground: Int, @ColorInt background: Int): Double {
    val foregroundLuminance = luminance(foreground)
    val backgroundLuminance = luminance(background)
    return (maxOf(foregroundLuminance, backgroundLuminance) + 0.05) /
        (minOf(foregroundLuminance, backgroundLuminance) + 0.05)
}

private fun luminance(@ColorInt color: Int): Double {
    fun channel(value: Int): Double {
        val normalized = value / 255.0
        return if (normalized <= 0.03928) {
            normalized / 12.92
        } else {
            ((normalized + 0.055) / 1.055).pow(2.4)
        }
    }
    return 0.2126 * channel(color ushr 16 and 0xFF) +
        0.7152 * channel(color ushr 8 and 0xFF) +
        0.0722 * channel(color and 0xFF)
}

private fun isLight(@ColorInt color: Int): Boolean = luminance(color) > 0.45
