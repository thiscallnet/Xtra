package com.github.andreyasadchy.xtra.ui.multiview.playback

import kotlin.math.ceil
import kotlin.math.roundToInt

data class MultiviewQualityInput(
    val streamCount: Int,
    val isActive: Boolean,
    val isFocused: Boolean,
    val tileWidthPx: Int = 0,
    val tileHeightPx: Int = 0,
    val mode: MultiviewQualityMode = MultiviewQualityMode.AUTO,
    val manualOverride: String? = null,
    val bufferingDowngradeLevel: Int = 0,
    val resourcePressure: Boolean = false,
)

data class MultiviewQualityTarget(
    val maxWidthPx: Int? = null,
    val maxHeightPx: Int? = null,
    val maxFrameRate: Int = 60,
    val preferredHeightPx: Int? = null,
    val preferredFrameRate: Int? = null,
    val label: String,
    val isConstrained: Boolean,
)

/**
 * Selects a ceiling for Media3 adaptation. It never selects a playlist URL and it never
 * forces 30fps as a normal data-saving measure; the decoder gets the best available format
 * below the ceiling instead.
 */
object MultiviewQualityPolicy {
    private val qualityHeights = intArrayOf(360, 480, 720, 1080)

    fun target(input: MultiviewQualityInput): MultiviewQualityTarget {
        val override = input.manualOverride?.let(::parseHeight)
        val sourceOverride = input.manualOverride.equals("Source", ignoreCase = true)
        val recoveryRequested = input.resourcePressure ||
            input.mode == MultiviewQualityMode.AUTO &&
            input.bufferingDowngradeLevel > 0

        // Decoder/resource pressure is deliberately handled before explicit quality
        // preferences. A device which cannot initialize another decoder must still
        // have a way out of Source/High Quality. Network buffering, on the other
        // hand, never overrides a manual preference.
        if (input.resourcePressure) {
            val recoveryCap = if (input.streamCount >= 3 && !input.isActive && !input.isFocused) 480 else 720
            val degradedCap = downgrade(
                recoveryCap,
                recoveryLevel(input),
            )
            val manualCap = override ?: Int.MAX_VALUE
            val globalCap = explicitQualityHeight(input.mode) ?: Int.MAX_VALUE
            val effectiveHeight = minOf(manualCap, degradedCap, globalCap)
            return constrainedHeight(effectiveHeight, "RECOVERY · ${effectiveHeight}p60")
        }

        if (override != null || sourceOverride) {
            return override?.let {
                val frameRate = input.manualOverride.let(::parseFrameRate) ?: 60
                constrainedHeight(
                    it,
                    "${it}p$frameRate",
                    preferredHeight = it,
                    preferredFrameRate = frameRate,
                )
            }
                ?: unconstrained("Source")
        }
        val normalCap = when (input.mode) {
            MultiviewQualityMode.QUALITY_360P -> 360
            MultiviewQualityMode.QUALITY_480P -> 480
            MultiviewQualityMode.QUALITY_720P -> 720
            MultiviewQualityMode.QUALITY_1080P -> 1080
            MultiviewQualityMode.AUTO -> when {
                input.isFocused && input.streamCount > 1 -> null
                input.streamCount <= 1 -> null
                input.isActive -> 720
                input.streamCount >= 3 -> 480
                else -> 720
            }
        }

        if (normalCap == null) {
            // Smart deliberately leaves one stream/focused playback adaptive,
            // but a decoder failure or sustained rebuffers must still have a
            // resource-saving fallback. Resolution is reduced before fps.
            if (!recoveryRequested) {
                return unconstrained("AUTO")
            }
            val recoveryCap = if (input.streamCount >= 3 && !input.isActive && !input.isFocused) 480 else 720
            val degradedCap = downgrade(recoveryCap, recoveryLevel(input))
            val tileCap = tileCap(input.tileWidthPx, input.tileHeightPx, degradedCap)
            val effectiveHeight = minOf(degradedCap, tileCap)
            return constrainedHeight(effectiveHeight, "AUTO · ${effectiveHeight}p60")
        }

        val degradedCap = if (input.mode == MultiviewQualityMode.AUTO) {
            downgrade(normalCap, recoveryLevel(input))
        } else {
            normalCap
        }
        val isExplicit = input.mode != MultiviewQualityMode.AUTO
        val effectiveHeight = if (isExplicit) degradedCap else minOf(
            degradedCap,
            tileCap(input.tileWidthPx, input.tileHeightPx, degradedCap),
        )
        return constrainedHeight(
            effectiveHeight,
            if (isExplicit) "${effectiveHeight}p" else "AUTO · ${effectiveHeight}p60",
            preferredHeight = effectiveHeight.takeIf { isExplicit },
        )
    }

    fun targetForManualOverride(label: String?): MultiviewQualityTarget? {
        if (label.isNullOrBlank()) return null
        return target(
            MultiviewQualityInput(
                streamCount = 1,
                isActive = true,
                isFocused = false,
                mode = MultiviewQualityMode.AUTO,
                manualOverride = label,
            )
        )
    }

    fun availableManualLabels(availableFormats: List<AvailableFormat>): List<String> {
        val labels = availableFormats
            .mapNotNull { format -> format.height.takeIf { it > 0 }?.let { height -> height to format.frameRate } }
            .sortedWith(compareByDescending<Pair<Int, Float>> { it.first }.thenByDescending { it.second })
            .map { (height, frameRate) -> "${height}p${frameRate.takeIf { it > 0 }?.roundToInt() ?: 60}" }
            .distinctBy { it.substringBefore('p') }
            .toMutableList()
        if (availableFormats.any { it.isSource }) labels.add(0, "Source")
        return labels.distinct()
    }

    fun effectiveFormatLabel(width: Int, height: Int, frameRate: Float): String {
        if (width <= 0 || height <= 0) return "AUTO"
        val fps = frameRate.takeIf { it > 0 }?.roundToInt() ?: 60
        return "${height}p${fps}"
    }

    private fun constrainedHeight(
        height: Int,
        label: String,
        preferredHeight: Int? = null,
        preferredFrameRate: Int? = null,
    ): MultiviewQualityTarget {
        val normalizedHeight = height.coerceAtLeast(144)
        return MultiviewQualityTarget(
            maxWidthPx = normalizedHeight * 16 / 9,
            maxHeightPx = normalizedHeight,
            maxFrameRate = 60,
            preferredHeightPx = preferredHeight,
            preferredFrameRate = preferredFrameRate,
            label = label,
            isConstrained = true,
        )
    }

    private fun unconstrained(label: String) = MultiviewQualityTarget(
        maxWidthPx = null,
        maxHeightPx = null,
        maxFrameRate = 60,
        label = label,
        isConstrained = false,
    )

    private fun tileCap(tileWidthPx: Int, tileHeightPx: Int, cap: Int): Int {
        if (tileWidthPx <= 0 && tileHeightPx <= 0) return cap
        val widthAsHeight = tileWidthPx * 9f / 16f
        val renderedHeight = maxOf(tileHeightPx.toFloat(), widthAsHeight)
        val desired = ceil(renderedHeight * TILE_OVERSAMPLE).toInt()
        return qualityHeights.firstOrNull { it >= desired }?.coerceAtMost(cap) ?: cap
    }

    private fun downgrade(cap: Int, level: Int): Int {
        if (level <= 0) return cap
        var result = cap
        repeat(level) {
            result = when {
                result > 720 -> 720
                result > 480 -> 480
                result > 360 -> 360
                else -> 360
            }
        }
        return result
    }

    private fun explicitQualityHeight(mode: MultiviewQualityMode): Int? = when (mode) {
        MultiviewQualityMode.AUTO -> null
        MultiviewQualityMode.QUALITY_360P -> 360
        MultiviewQualityMode.QUALITY_480P -> 480
        MultiviewQualityMode.QUALITY_720P -> 720
        MultiviewQualityMode.QUALITY_1080P -> 1080
    }

    private fun recoveryLevel(input: MultiviewQualityInput): Int {
        // The recovery state already increments its level for a decoder/resource
        // failure. Do not count the boolean pressure marker as another step.
        return maxOf(input.bufferingDowngradeLevel, if (input.resourcePressure) 1 else 0)
    }

    private fun parseHeight(label: String): Int? {
        return Regex("^(\\d{3,4})p(?:\\d+)?$", RegexOption.IGNORE_CASE)
            .matchEntire(label.trim())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun parseFrameRate(label: String): Int? {
        return Regex("^\\d{3,4}p(\\d+)$", RegexOption.IGNORE_CASE)
            .matchEntire(label.trim())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    data class AvailableFormat(
        val height: Int,
        val frameRate: Float = 60f,
        val isSource: Boolean = false,
    )

    private const val TILE_OVERSAMPLE = 1.2
}
