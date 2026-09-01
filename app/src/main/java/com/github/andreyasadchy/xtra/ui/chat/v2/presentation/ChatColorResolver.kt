package com.github.andreyasadchy.xtra.ui.chat.v2.presentation

import androidx.annotation.ColorInt
import kotlin.math.pow
import java.util.LinkedHashMap

class ChatColorResolver(
    @ColorInt private val fallback: Int = 0xFF919191.toInt(),
    private val readable: Boolean = false,
    private val maxEntries: Int = 128,
    @ColorInt private val background: Int = 0xFF101010.toInt(),
) {
    private val cache = object : LinkedHashMap<String, Int>(maxEntries, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > maxEntries
    }

    @ColorInt
    @Synchronized
    fun resolve(raw: String?, identity: String? = null, @ColorInt rowBackground: Int = background): Int {
        val key = "${raw.orEmpty()}|${identity.orEmpty()}|$readable|$rowBackground"
        return cache.getOrPut(key) {
            val parsed = parseColor(raw)
            val identityFallback = fallbackColor(identity, rowBackground)
            val color = parsed ?: identityFallback
            val adjusted = if (parsed == null || isNearWhite(color) || !hasContrast(color, rowBackground)) {
                identityFallback
            } else color
            val readableColor = if (readable) makeReadable(adjusted) else adjusted
            if (hasContrast(readableColor, rowBackground) && !isNearWhite(readableColor)) {
                readableColor
            } else {
                identityFallback
            }
        }
    }

    private fun fallbackColor(identity: String?, @ColorInt rowBackground: Int): Int {
        if (identity == null) {
            return if (!isNearWhite(fallback) && hasContrast(fallback, rowBackground)) {
                fallback
            } else {
                if (isLight(rowBackground)) 0xFF555555.toInt() else 0xFFBDBDBD.toInt()
            }
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
        val start = (hash and Int.MAX_VALUE) % palette.size
        return (0 until palette.size)
            .asSequence()
            .map { palette[(start + it) % palette.size] }
            .firstOrNull { hasContrast(it, rowBackground) }
            ?: if (isLight(rowBackground)) 0xFF555555.toInt() else 0xFFBDBDBD.toInt()
    }

    private fun hasContrast(@ColorInt color: Int, @ColorInt rowBackground: Int): Boolean {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.03928) normalized / 12.92 else ((normalized + 0.055) / 1.055).pow(2.4)
        }
        val luminance = 0.2126 * channel(color ushr 16 and 0xff) + 0.7152 * channel(color ushr 8 and 0xff) + 0.0722 * channel(color and 0xff)
        val backgroundLuminance = 0.2126 * channel(rowBackground ushr 16 and 0xff) + 0.7152 * channel(rowBackground ushr 8 and 0xff) + 0.0722 * channel(rowBackground and 0xff)
        return (maxOf(luminance, backgroundLuminance) + 0.05) / (minOf(luminance, backgroundLuminance) + 0.05) >= 3.0
    }

    private fun isLight(@ColorInt color: Int): Boolean =
        (0.2126 * linear(red(color)) + 0.7152 * linear(green(color)) + 0.0722 * linear(blue(color))) > 0.45

    private fun linear(value: Int): Double {
        val normalized = value / 255.0
        return if (normalized <= 0.03928) normalized / 12.92 else ((normalized + 0.055) / 1.055).pow(2.4)
    }

    private fun isNearWhite(@ColorInt color: Int): Boolean =
        red(color) >= 245 && green(color) >= 245 && blue(color) >= 245

    private fun makeReadable(@ColorInt color: Int): Int {
        val luminance = (0.299 * red(color) + 0.587 * green(color) + 0.114 * blue(color)) / 255
        return if (luminance < 0.35) rgb(
            (red(color) + 255) / 2,
            (green(color) + 255) / 2,
            (blue(color) + 255) / 2,
        ) else color
    }

    @ColorInt
    private fun parseColor(raw: String?): Int? {
        val value = raw?.trim()?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) } ?: return null
        return (value.substring(1).toLong(16).toInt() or 0xFF000000.toInt())
    }

    private fun red(@ColorInt color: Int): Int = color ushr 16 and 0xff
    private fun green(@ColorInt color: Int): Int = color ushr 8 and 0xff
    private fun blue(@ColorInt color: Int): Int = color and 0xff
    private fun rgb(red: Int, green: Int, blue: Int): Int =
        0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
}
