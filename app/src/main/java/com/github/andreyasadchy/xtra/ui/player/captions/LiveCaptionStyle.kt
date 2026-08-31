package com.github.andreyasadchy.xtra.ui.player.captions

import android.content.Context
import android.graphics.Color
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

internal data class LiveCaptionStyle(
    val backgroundColor: Int,
    val fontFamily: String,
    val fontSizeSp: Float,
    val opacity: Float,
    val animationDurationMs: Long,
) {
    companion object {
        fun from(context: Context): LiveCaptionStyle {
            val preferences = context.prefs()
            val backgroundColor = when (
                preferences.getString(C.PLAYER_LIVE_CAPTION_BACKGROUND, BACKGROUND_BLACK)
            ) {
                BACKGROUND_TRANSPARENT -> Color.TRANSPARENT
                BACKGROUND_CUSTOM -> runCatching {
                    Color.parseColor(
                        preferences.getString(
                            C.PLAYER_LIVE_CAPTION_BACKGROUND_COLOR,
                            DEFAULT_CUSTOM_COLOR,
                        ) ?: DEFAULT_CUSTOM_COLOR,
                    )
                }.getOrDefault(DEFAULT_BACKGROUND_COLOR)
                else -> DEFAULT_BACKGROUND_COLOR
            }
            return LiveCaptionStyle(
                backgroundColor = backgroundColor,
                fontFamily = preferences.getString(
                    C.PLAYER_LIVE_CAPTION_FONT,
                    DEFAULT_FONT_FAMILY,
                ) ?: DEFAULT_FONT_FAMILY,
                fontSizeSp = preferences.getInt(
                    C.PLAYER_LIVE_CAPTION_FONT_SIZE,
                    DEFAULT_FONT_SIZE_SP,
                ).coerceIn(12, 30).toFloat(),
                opacity = preferences.getInt(
                    C.PLAYER_LIVE_CAPTION_OPACITY,
                    DEFAULT_OPACITY_PERCENT,
                ).coerceIn(20, 100) / 100f,
                animationDurationMs = preferences.getString(
                    C.PLAYER_LIVE_CAPTION_ANIMATION_MS,
                    DEFAULT_ANIMATION_DURATION_MS.toString(),
                )?.toLongOrNull()?.coerceIn(0L, 1_000L)
                    ?: DEFAULT_ANIMATION_DURATION_MS,
            )
        }
    }
}

internal const val DEFAULT_CAPTION_HOLD_SECONDS = 3
internal const val DEFAULT_CAPTION_PRESENTATION_DELAY_MS = 1_250
internal const val MAX_CAPTION_PRESENTATION_DELAY_MS = 2_000

private const val BACKGROUND_BLACK = "black"
private const val BACKGROUND_TRANSPARENT = "transparent"
private const val BACKGROUND_CUSTOM = "custom"
private const val DEFAULT_CUSTOM_COLOR = "#000000"
private const val DEFAULT_BACKGROUND_COLOR = 0xD9000000.toInt()
private const val DEFAULT_FONT_FAMILY = "sans-serif"
private const val DEFAULT_FONT_SIZE_SP = 18
private const val DEFAULT_OPACITY_PERCENT = 100
private const val DEFAULT_ANIMATION_DURATION_MS = 250L
