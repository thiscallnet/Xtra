package com.github.andreyasadchy.xtra.ui.player.captions

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A fixed two-row caption panel whose text rolls upward one complete line at a time. */
class LiveCaptionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val preferences = context.prefs()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            C.PLAYER_LIVE_CAPTION_POSITION_CENTER_X,
            C.PLAYER_LIVE_CAPTION_POSITION_CENTER_Y,
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
                post { reflowVisibleLines() }
            }
            C.PLAYER_LIVE_CAPTION_WIDTH -> {
                requestLayout()
                post { reflowVisibleLines() }
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
    private var rawLines = listOf("", "")
    private var currentLineShiftToken = 0L
    private var currentStyle: LiveCaptionStyle? = null
    private var animationRunning = false
    private var pendingUpdate: CaptionUpdate? = null
    private var positioning = false
    private var positioningUpdate: CaptionUpdate? = null
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartTranslationX = 0f
    private var dragStartTranslationY = 0f

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onLongPress(event: MotionEvent) {
                beginPositioning(event.rawX, event.rawY)
            }
        },
    )

    init {
        clipChildren = true
        clipToPadding = true
        setPadding(0, dp(4), 0, dp(4))
        addView(topLine)
        addView(bottomLine)
        addView(incomingLine)
        isClickable = true
        isLongClickable = true
        setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (positioning) {
                        updatePosition(event.rawX, event.rawY)
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (positioning) {
                        finishPositioning()
                    }
                }
            }
            positioning || event.actionMasked != MotionEvent.ACTION_UP
        }
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
        if (positioning) {
            // Keep the visible phrase stable while the user drags it. Apply only
            // the newest worker result when the gesture ends.
            positioningUpdate = update
        } else if (normalizedLines.all(String::isBlank)) {
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
        rawLines = listOf("", "")
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
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val widthFraction = captionWidthFraction()
        val desiredWidth = if (availableWidth > 0) {
            (availableWidth * widthFraction).toInt()
        } else {
            dp(320)
        }
        val resolvedWidth = resolveSize(desiredWidth, widthMeasureSpec)
        val resolvedHeight = resolveSize(desiredHeight, heightMeasureSpec)
        val rowHeight = ((resolvedHeight - paddingTop - paddingBottom) / 2).coerceAtLeast(1)
        listOf(topLine, bottomLine, incomingLine).forEach { line ->
            line.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, rowHeight).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(resolvedWidth, MeasureSpec.EXACTLY),
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
        val fittedUpdate = update.copy(lines = fitCaptionLines(update.lines))
        visibility = View.VISIBLE

        val shouldRoll = fittedUpdate.lineShiftToken != currentLineShiftToken &&
            currentLines[1].isNotBlank() &&
            currentLines[1] == fittedUpdate.lines[0] &&
            fittedUpdate.style.animationDurationMs > 0L &&
            height > 0

        if (shouldRoll) {
            rawLines = update.lines
            animateLineRoll(fittedUpdate)
        } else {
            rawLines = update.lines
            currentLines = fittedUpdate.lines
            currentLineShiftToken = fittedUpdate.lineShiftToken
            topLine.text = fittedUpdate.lines[0]
            bottomLine.text = fittedUpdate.lines[1]
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
        // Keep the panel itself transparent. YouTube-style captions have a
        // small background behind each rendered line, not a wide empty box.
        background = null
        alpha = 1f
        val typeface = if (style.fontFamily == "system") {
            Typeface.DEFAULT
        } else {
            Typeface.create(style.fontFamily, Typeface.NORMAL)
        }
        listOf(topLine, bottomLine, incomingLine).forEach { line ->
            line.typeface = typeface
            line.textSize = style.fontSizeSp
            line.setTextColor(withOpacity(style.textColor, style.opacity))
            line.background = if (Color.alpha(style.backgroundColor) == 0) {
                null
            } else {
                GradientDrawable().apply {
                    setColor(withOpacity(style.backgroundColor, style.opacity))
                    cornerRadius = dp(4).toFloat()
                }
            }
        }
        requestLayout()
    }

    private fun applySavedPosition() {
        val parent = parent as? android.view.ViewGroup ?: return
        if (width == 0 || height == 0 || parent.width == 0 || parent.height == 0) {
            post { applySavedPosition() }
            return
        }
        if (!preferences.contains(C.PLAYER_LIVE_CAPTION_POSITION_CENTER_X) ||
            !preferences.contains(C.PLAYER_LIVE_CAPTION_POSITION_CENTER_Y)
        ) {
            translationX = 0f
            translationY = 0f
            return
        }
        translationX = LiveCaptionPosition.translationForCenter(
            preferences.getFloat(C.PLAYER_LIVE_CAPTION_POSITION_CENTER_X, 0.5f),
            parent.width,
            left,
            width,
        ).coerceIn(-left.toFloat(), (parent.width - right).toFloat())
        translationY = LiveCaptionPosition.translationForCenter(
            preferences.getFloat(C.PLAYER_LIVE_CAPTION_POSITION_CENTER_Y, 0.5f),
            parent.height,
            top,
            height,
        ).coerceIn(-top.toFloat(), (parent.height - bottom).toFloat())
    }

    private fun beginPositioning(rawX: Float, rawY: Float) {
        if (positioning || visibility != View.VISIBLE) return
        positioning = true
        positioningUpdate = null
        cancelLineAnimations()
        animationRunning = false
        dragStartRawX = rawX
        dragStartRawY = rawY
        dragStartTranslationX = translationX
        dragStartTranslationY = translationY
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        contentDescription = context.getString(R.string.live_caption_positioning)
    }

    private fun updatePosition(rawX: Float, rawY: Float) {
        val parent = parent as? android.view.ViewGroup ?: return
        translationX = (dragStartTranslationX + rawX - dragStartRawX).coerceIn(
            -left.toFloat(),
            (parent.width - right).toFloat(),
        )
        translationY = (dragStartTranslationY + rawY - dragStartRawY).coerceIn(
            -top.toFloat(),
            (parent.height - bottom).toFloat(),
        )
    }

    private fun finishPositioning() {
        val parent = parent as? android.view.ViewGroup
        if (parent != null && parent.width > 0 && parent.height > 0) {
            preferences.edit()
                .putFloat(
                    C.PLAYER_LIVE_CAPTION_POSITION_CENTER_X,
                    LiveCaptionPosition.normalizedCenterForTranslation(
                        translationX,
                        parent.width,
                        left,
                        width,
                    ),
                )
                .putFloat(
                    C.PLAYER_LIVE_CAPTION_POSITION_CENTER_Y,
                    LiveCaptionPosition.normalizedCenterForTranslation(
                        translationY,
                        parent.height,
                        top,
                        height,
                    ),
                )
                .apply()
        }
        positioning = false
        positioningUpdate?.also {
            positioningUpdate = null
            if (it.lines.all(String::isBlank)) clearCaption() else applyUpdate(it)
        }
        updateAccessibilityText()
    }

    private fun resetLinePositions() {
        val rowHeight = contentRowHeight()
        listOf(topLine, bottomLine, incomingLine).forEach { line ->
            line.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, rowHeight).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
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
        // The caption state already constrains the rolling window by words.
        // TextView ellipsizing would replace the last spoken word with "…" on
        // narrow displays, which is especially confusing for live speech.
        ellipsize = null
        setPadding(dp(10), 0, dp(10), 0)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun captionWidthFraction(): Float = preferences
        .getString(C.PLAYER_LIVE_CAPTION_WIDTH, "auto")
        ?.toFloatOrNull()
        ?.div(100f)
        ?.coerceIn(0.5f, 1f)
        ?: 0.92f

    private fun captionContentWidth(): Float {
        val parentWidth = (parent as? ViewGroup)?.width ?: width
        val panelWidth = if (parentWidth > 0) parentWidth * captionWidthFraction() else width.toFloat()
        val textPadding = topLine.paddingLeft + topLine.paddingRight
        return (panelWidth - textPadding).coerceAtLeast(1f)
    }

    /** Wraps all words in order and keeps only the last two complete display rows. */
    private fun fitCaptionLines(lines: List<String>): List<String> {
        val maxWidth = captionContentWidth()
        val wrapped = mutableListOf<String>()
        var current = ""

        fun flush() {
            if (current.isNotEmpty()) {
                wrapped += current
                current = ""
            }
        }

        lines.asSequence()
            .flatMap { it.trim().split(Regex("\\s+")).asSequence() }
            .filter(String::isNotEmpty)
            .forEach { word ->
                if (topLine.paint.measureText(word) <= maxWidth) {
                    val candidate = if (current.isEmpty()) word else "$current $word"
                    if (current.isNotEmpty() && topLine.paint.measureText(candidate) > maxWidth) {
                        flush()
                        current = word
                    } else {
                        current = candidate
                    }
                } else {
                    // A URL or other single long token must not be accepted as
                    // an unmeasurable line. Split it deterministically instead.
                    flush()
                    var remainder = word
                    while (remainder.isNotEmpty()) {
                        val count = topLine.paint.breakText(remainder, true, maxWidth, null)
                            .coerceAtLeast(1)
                        wrapped += remainder.take(count)
                        remainder = remainder.drop(count)
                    }
                }
            }
        flush()

        return wrapped.takeLast(2).let { result ->
            when (result.size) {
                0 -> listOf("", "")
                1 -> listOf("", result[0])
                else -> result
            }
        }
    }

    private fun reflowVisibleLines() {
        if (rawLines.all(String::isBlank)) return
        currentLines = fitCaptionLines(rawLines)
        topLine.text = currentLines[0]
        bottomLine.text = currentLines[1]
        requestLayout()
    }

    private fun withOpacity(color: Int, opacity: Float): Int = Color.argb(
        (Color.alpha(color) * opacity).roundToInt().coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )
}
