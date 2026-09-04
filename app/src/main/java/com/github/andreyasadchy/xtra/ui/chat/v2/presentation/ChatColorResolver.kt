package com.github.andreyasadchy.xtra.ui.chat.v2.presentation

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import java.util.LinkedHashMap
import kotlin.math.abs
import kotlin.math.pow

class ChatColorResolver(
    @ColorInt private val fallback: Int = 0xFF919191.toInt(),
    private val readable: Boolean = false,
    private val randomFallback: Boolean = true,
    private val neutralFallback: Boolean = false,
    private val maxEntries: Int = 128,
    @ColorInt private val background: Int = 0xFF101010.toInt(),
) {
    private companion object {
        const val SECONDARY_TEXT_MIN_CONTRAST = 4.5
    }

    private data class LightnessCandidate(
        @ColorInt val color: Int,
        val lightness: Float,
    )

    private val cache = object : LinkedHashMap<String, Int>(maxEntries, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > maxEntries
    }

    @ColorInt
    @Synchronized
    fun resolve(raw: String?, identity: String? = null, @ColorInt rowBackground: Int = background): Int {
        val key = "${raw.orEmpty()}|${identity.orEmpty()}|$readable|$randomFallback|$neutralFallback|$rowBackground"
        return cache.getOrPut(key) {
            val parsed = parseColor(raw)
            val identityFallback = fallbackColor(identity, rowBackground)
            val color = parsed?.takeUnless(::isNearWhite) ?: identityFallback
            val readableColor = if (readable) makeReadable(color, rowBackground) else color
            if (hasContrast(readableColor, rowBackground) && !isNearWhite(readableColor)) {
                readableColor
            } else {
                identityFallback
            }
        }
    }

    /**
     * Muted secondary text for replies, subscription bodies, reward captions,
     * and translations. The dark-mode mauve is unreadable on light surfaces,
     * so light rows get a dark gray with equivalent contrast.
     */
    @ColorInt
    fun mutedTextColor(@ColorInt rowBackground: Int): Int =
        readableSecondaryText(rowBackground, 0xFF5F5B66.toInt(), 0xFFC4BEC9.toInt())

    /**
     * Bright heading text for tinted event rows (e.g. the subscription actor
     * fallback). Near-white works on dark rows but vanishes on light ones.
     */
    @ColorInt
    fun brightTextColor(@ColorInt rowBackground: Int): Int =
        readableSecondaryText(rowBackground, 0xFF1F1B24.toInt(), 0xFFE8E4EC.toInt())

    private fun fallbackColor(identity: String?, @ColorInt rowBackground: Int): Int {
        if (identity == null) {
            return if (!isNearWhite(fallback) && hasContrast(fallback, rowBackground)) {
                fallback
            } else {
                if (isLight(rowBackground)) 0xFF555555.toInt() else 0xFFBDBDBD.toInt()
            }
        }
        if (neutralFallback) {
            return fallback.takeUnless(::isNearWhite)
                ?.takeIf { hasContrast(it, rowBackground) }
                ?: if (isLight(rowBackground)) 0xFF555555.toInt() else 0xFFBDBDBD.toInt()
        }
        return identityColor(identity, rowBackground)
    }

    private fun identityColor(identity: String, @ColorInt rowBackground: Int): Int {
        val palette = if (isLight(rowBackground)) {
            intArrayOf(0xFF7B1FA2.toInt(), 0xFFC62828.toInt(), 0xFF00695C.toInt(), 0xFF1565C0.toInt(), 0xFF6D4C41.toInt(), 0xFFAD1457.toInt(), 0xFF4E342E.toInt())
        } else {
            intArrayOf(0xFFFF8A80.toInt(), 0xFFFFD180.toInt(), 0xFFFFFF8A.toInt(), 0xFFB9F6CA.toInt(), 0xFF80D8FF.toInt(), 0xFFB388FF.toInt(), 0xFFFF80AB.toInt())
        }
        val hash = identity.orEmpty().fold(0) { result, character -> result * 31 + character.code }
        val start = if (randomFallback) {
            // Keep each user's fallback stable while honoring the random-color preference.
            (hash and Int.MAX_VALUE) % palette.size
        } else {
            0
        }
        return (0 until palette.size)
            .asSequence()
            .map { palette[(start + it) % palette.size] }
            .firstOrNull { hasContrast(it, rowBackground) }
            ?: if (isLight(rowBackground)) 0xFF555555.toInt() else 0xFFBDBDBD.toInt()
    }

    private fun hasContrast(@ColorInt color: Int, @ColorInt rowBackground: Int): Boolean {
        return contrastRatio(color, rowBackground) >= 3.0
    }

    private fun useDarkText(@ColorInt rowBackground: Int, @ColorInt darkText: Int, @ColorInt lightText: Int): Boolean =
        contrastRatio(darkText, rowBackground) >= contrastRatio(lightText, rowBackground)

    @ColorInt
    private fun readableSecondaryText(@ColorInt rowBackground: Int, @ColorInt darkText: Int, @ColorInt lightText: Int): Int {
        val preferred = if (useDarkText(rowBackground, darkText, lightText)) darkText else lightText
        val readable = raiseContrast(preferred, rowBackground, SECONDARY_TEXT_MIN_CONTRAST)
        if (contrastRatio(readable, rowBackground) >= SECONDARY_TEXT_MIN_CONTRAST) return readable

        val alternative = raiseContrast(
            if (preferred == darkText) lightText else darkText,
            rowBackground,
            SECONDARY_TEXT_MIN_CONTRAST,
        )
        return if (contrastRatio(readable, rowBackground) >= contrastRatio(alternative, rowBackground)) readable else alternative
    }

    @ColorInt
    private fun raiseContrast(@ColorInt color: Int, @ColorInt rowBackground: Int, minimumContrast: Double): Int {
        if (contrastRatio(color, rowBackground) >= minimumContrast) return color
        val target = when {
            contrastRatio(Color.BLACK, rowBackground) >= minimumContrast -> Color.BLACK
            contrastRatio(Color.WHITE, rowBackground) >= minimumContrast -> Color.WHITE
            else -> return color
        }
        var low = 0.0
        var high = 1.0
        repeat(12) {
            val amount = (low + high) / 2.0
            if (contrastRatio(blend(color, target, amount), rowBackground) >= minimumContrast) {
                high = amount
            } else {
                low = amount
            }
        }
        return blend(color, target, high)
    }

    @ColorInt
    private fun blend(@ColorInt color: Int, @ColorInt target: Int, amount: Double): Int {
        fun component(source: Int, destination: Int): Int =
            (source + (destination - source) * amount).toInt().coerceIn(0, 255)

        return 0xFF000000.toInt() or
            (component(red(color), red(target)) shl 16) or
            (component(green(color), green(target)) shl 8) or
            component(blue(color), blue(target))
    }

    private fun contrastRatio(@ColorInt color: Int, @ColorInt rowBackground: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.03928) normalized / 12.92 else ((normalized + 0.055) / 1.055).pow(2.4)
        }
        val luminance = 0.2126 * channel(color ushr 16 and 0xff) + 0.7152 * channel(color ushr 8 and 0xff) + 0.0722 * channel(color and 0xff)
        val backgroundLuminance = 0.2126 * channel(rowBackground ushr 16 and 0xff) + 0.7152 * channel(rowBackground ushr 8 and 0xff) + 0.0722 * channel(rowBackground and 0xff)
        return (maxOf(luminance, backgroundLuminance) + 0.05) / (minOf(luminance, backgroundLuminance) + 0.05)
    }

    private fun isLight(@ColorInt color: Int): Boolean =
        (0.2126 * linear(red(color)) + 0.7152 * linear(green(color)) + 0.0722 * linear(blue(color))) > 0.45

    private fun linear(value: Int): Double {
        val normalized = value / 255.0
        return if (normalized <= 0.03928) normalized / 12.92 else ((normalized + 0.055) / 1.055).pow(2.4)
    }

    private fun isNearWhite(@ColorInt color: Int): Boolean =
        red(color) >= 245 && green(color) >= 245 && blue(color) >= 245

    private fun makeReadable(@ColorInt color: Int, @ColorInt rowBackground: Int): Int {
        if (hasContrast(color, rowBackground)) return color

        // Raise or lower lightness while retaining the Twitch-selected hue and saturation.
        // Blending toward white makes reds and blues look pastel, which is unlike Twitch chat.
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        val originalLightness = hsl[2]
        val canDarken = hasContrast(Color.BLACK, rowBackground)
        val canLighten = hasContrast(Color.WHITE, rowBackground)
        val darkened = if (canDarken) {
            findReadableCandidate(hsl, originalLightness, rowBackground, towardBlack = true)
        } else {
            null
        }
        val lightened = if (canLighten) {
            findReadableCandidate(hsl, originalLightness, rowBackground, towardBlack = false)
        } else {
            null
        }

        return when {
            darkened == null -> lightened?.color ?: color
            lightened == null -> darkened.color
            abs(darkened.lightness - originalLightness) <= abs(lightened.lightness - originalLightness) -> darkened.color
            else -> lightened.color
        }
    }

    private fun findReadableCandidate(
        hsl: FloatArray,
        originalLightness: Float,
        @ColorInt rowBackground: Int,
        towardBlack: Boolean,
    ): LightnessCandidate? {
        val searchHsl = hsl.copyOf()
        val endpointLightness = if (towardBlack) 0f else 1f
        searchHsl[2] = endpointLightness
        val endpoint = ColorUtils.HSLToColor(searchHsl)
        if (!hasContrast(endpoint, rowBackground)) return null

        var low = if (towardBlack) 0f else originalLightness
        var high = if (towardBlack) originalLightness else 1f
        var best = LightnessCandidate(endpoint, endpointLightness)
        repeat(12) {
            val lightness = (low + high) / 2f
            searchHsl[2] = lightness
            val candidate = ColorUtils.HSLToColor(searchHsl)
            if (hasContrast(candidate, rowBackground)) {
                best = LightnessCandidate(candidate, lightness)
                if (towardBlack) {
                    low = lightness
                } else {
                    high = lightness
                }
            } else if (towardBlack) {
                high = lightness
            } else {
                low = lightness
            }
        }
        return best
    }

    @ColorInt
    private fun parseColor(raw: String?): Int? {
        val value = raw?.trim()?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) } ?: return null
        return (value.substring(1).toLong(16).toInt() or 0xFF000000.toInt())
    }

    private fun red(@ColorInt color: Int): Int = color ushr 16 and 0xff
    private fun green(@ColorInt color: Int): Int = color ushr 8 and 0xff
    private fun blue(@ColorInt color: Int): Int = color and 0xff
}
