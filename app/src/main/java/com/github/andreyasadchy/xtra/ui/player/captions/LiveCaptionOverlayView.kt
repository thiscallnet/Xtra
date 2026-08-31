package com.github.andreyasadchy.xtra.ui.player.captions

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlin.math.ceil

/** A fixed two-row caption panel whose text rolls upward one complete line at a time. */
class LiveCaptionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val preferences = context.prefs()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            C.PLAYER_LIVE_CAPTION_POSITION_X,
            C.PLAYER_LIVE_CAPTION_POSITION_Y -> applySavedPosition()
            C.PLAYER_LIVE_CAPTION_BACKGROUND,
            C.PLAYER_LIVE_CAPTION_BACKGROUND_COLOR,
            C.PLAYER_LIVE_CAPTION_TEXT_COLOR,
            C.PLAYER_LIVE_CAPTION_FONT,
            C.PLAYER_LIVE_CAPTION_FONT_SIZE,
            C.PLAYER_LIVE_CAPTION_OPACITY -> {
                currentStyle = null
                applyStyle(LiveCaptionStyle.from(context))
            }
        }
    }
    private data class CaptionUpdate(
        val lines: List<String>,
        val lineShiftToken: Long,
        val style: LiveCaptionStyle,
    )

    private val topLine = createLineView()
    private val bottomLine = createLineView()
    private val incomingLine = createLineView().apply { visibility = View.INVISIBLE }

    private var currentLines = listOf("", "")
    private var currentLineShiftToken = 0L
    private var currentStyle: LiveCaptionStyle? = null
    private var animationRunning = false
    private var pendingUpdate: CaptionUpdate? = null

    init {
        clipChildren = true
        clipToPadding = true
        setPadding(dp(10), dp(4), dp(10), dp(4))
        addView(topLine)
        addView(bottomLine)
        addView(incomingLine)
        visibility = View.GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        applySavedPosition()
    }

    fun submitCaption(text: String, lineShiftToken: Long) {
        val normalizedLines = formatCaptionTextForDisplay(text)
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
            .takeLast(2)
            .let { lines ->
                when (lines.size) {
                    0 -> listOf("", "")
                    1 -> listOf("", lines[0])
                    else -> lines
                }
            }
        val update = CaptionUpdate(
            lines = normalizedLines,
            lineShiftToken = lineShiftToken,
            style = LiveCaptionStyle.from(context),
        )
        if (normalizedLines.all(String::isBlank)) {
            clearCaption()
        } else if (animationRunning) {
            pendingUpdate = update
        } else {
            applyUpdate(update)
        }
    }

    fun clearCaption() {
        pendingUpdate = null
        cancelLineAnimations()
        animationRunning = false
        currentLines = listOf("", "")
        topLine.text = ""
        bottomLine.text = ""
        incomingLine.text = ""
        incomingLine.visibility = View.INVISIBLE
        contentDescription = null
        visibility = View.GONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val lineHeight = ceil(topLine.paint.fontMetrics.run { descent - ascent }).toInt()
        val desiredHeight = paddingTop + paddingBottom + lineHeight * 2
        val resolvedHeight = resolveSize(desiredHeight, heightMeasureSpec)
        val rowHeight = ((resolvedHeight - paddingTop - paddingBottom) / 2).coerceAtLeast(1)
        listOf(topLine, bottomLine, incomingLine).forEach { line ->
            line.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, rowHeight)
        }
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(resolvedHeight, MeasureSpec.EXACTLY),
        )
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        resetLinePositions()
        applySavedPosition()
    }

    override fun onDetachedFromWindow() {
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        cancelLineAnimations()
        super.onDetachedFromWindow()
    }

    private fun applyUpdate(update: CaptionUpdate) {
        applyStyle(update.style)
        visibility = View.VISIBLE

        val shouldRoll = update.lineShiftToken != currentLineShiftToken &&
            currentLines[1].isNotBlank() &&
            currentLines[1] == update.lines[0] &&
            update.style.animationDurationMs > 0L &&
            height > 0

        if (shouldRoll) {
            animateLineRoll(update)
        } else {
            currentLines = update.lines
            currentLineShiftToken = update.lineShiftToken
            topLine.text = update.lines[0]
            bottomLine.text = update.lines[1]
            incomingLine.visibility = View.INVISIBLE
            resetLinePositions()
            updateAccessibilityText()
        }
    }

    private fun animateLineRoll(update: CaptionUpdate) {
        animationRunning = true
        val rowHeight = contentRowHeight().toFloat()
        incomingLine.text = update.lines[1]
        incomingLine.visibility = View.VISIBLE
        incomingLine.alpha = 1f
        incomingLine.translationY = rowHeight * 2f

        topLine.animate()
            .translationY(-rowHeight)
            .alpha(0f)
            .setDuration(update.style.animationDurationMs)
            .start()
        bottomLine.animate()
            .translationY(0f)
            .setDuration(update.style.animationDurationMs)
            .start()
        incomingLine.animate()
            .translationY(rowHeight)
            .setDuration(update.style.animationDurationMs)
            .withEndAction {
                currentLines = update.lines
                currentLineShiftToken = update.lineShiftToken
                topLine.text = update.lines[0]
                bottomLine.text = update.lines[1]
                incomingLine.text = ""
                incomingLine.visibility = View.INVISIBLE
                animationRunning = false
                resetLinePositions()
                updateAccessibilityText()
                pendingUpdate?.also {
                    pendingUpdate = null
                    applyUpdate(it)
                }
            }
            .start()
    }

    private fun applyStyle(style: LiveCaptionStyle) {
        if (style == currentStyle) return
        currentStyle = style
        background = GradientDrawable().apply {
            setColor(style.backgroundColor)
            cornerRadius = dp(4).toFloat()
        }
        alpha = style.opacity
        val typeface = if (style.fontFamily == "system") {
            Typeface.DEFAULT
        } else {
            Typeface.create(style.fontFamily, Typeface.NORMAL)
        }
        listOf(topLine, bottomLine, incomingLine).forEach { line ->
            line.typeface = typeface
            line.textSize = style.fontSizeSp
            line.setTextColor(style.textColor)
        }
        requestLayout()
    }

    private fun applySavedPosition() {
        val parent = parent as? android.view.ViewGroup ?: return
        if (width == 0 || height == 0 || parent.width == 0 || parent.height == 0) {
            post { applySavedPosition() }
            return
        }
        val x = preferences.getFloat(C.PLAYER_LIVE_CAPTION_POSITION_X, 0f)
        val y = preferences.getFloat(C.PLAYER_LIVE_CAPTION_POSITION_Y, 0f)
        translationX = (x * parent.width).coerceIn(
            -left.toFloat(),
            (parent.width - right).toFloat(),
        )
        translationY = (y * parent.height).coerceIn(
            -top.toFloat(),
            (parent.height - bottom).toFloat(),
        )
    }

    private fun resetLinePositions() {
        val rowHeight = contentRowHeight()
        listOf(topLine, bottomLine, incomingLine).forEach { line ->
            line.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, rowHeight)
            line.alpha = 1f
        }
        topLine.translationY = 0f
        bottomLine.translationY = rowHeight.toFloat()
        incomingLine.translationY = rowHeight * 2f
    }

    private fun contentRowHeight(): Int = ((height - paddingTop - paddingBottom) / 2).coerceAtLeast(1)

    private fun cancelLineAnimations() {
        topLine.animate().cancel()
        bottomLine.animate().cancel()
        incomingLine.animate().cancel()
    }

    private fun updateAccessibilityText() {
        contentDescription = currentLines.filter(String::isNotBlank).joinToString(" ")
    }

    private fun createLineView(): TextView = TextView(context).apply {
        setTextColor(android.graphics.Color.WHITE)
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
