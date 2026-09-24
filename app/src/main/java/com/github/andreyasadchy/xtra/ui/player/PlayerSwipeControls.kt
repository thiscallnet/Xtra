package com.github.andreyasadchy.xtra.ui.player

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.color.MaterialColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private enum class SwipeControlAction {
    OFF,
    BRIGHTNESS,
    VOLUME,
    SPEED,
}

private data class SwipeControlsSettings(
    val enabled: Boolean,
    val leftAction: SwipeControlAction,
    val rightAction: SwipeControlAction,
    val topAction: SwipeControlAction,
    val edgeWidthPercent: Int,
    val speedZoneHeightPercent: Int,
    val brightnessSensitivity: Int,
    val volumeSensitivity: Int,
    val speedSensitivity: Int,
    val speedStep: Float,
    val ignoreWhenLocked: Boolean,
) {
    fun actionAt(x: Float, y: Float, width: Int, height: Int): SwipeControlAction {
        if (width <= 0 || height <= 0 || y < 0f || y > height) return SwipeControlAction.OFF
        val topHeight = height * speedZoneHeightPercent / 100f
        if (y < topHeight) return topAction
        val edgeWidth = width * edgeWidthPercent / 100f
        return when {
            x <= edgeWidth -> leftAction
            x >= width - edgeWidth -> rightAction
            else -> SwipeControlAction.OFF
        }
    }

    companion object {
        fun read(context: Context): SwipeControlsSettings {
            val preferences = context.prefs()
            fun action(key: String, default: String): SwipeControlAction = when (
                preferences.getString(key, default)
            ) {
                "brightness" -> SwipeControlAction.BRIGHTNESS
                "volume" -> SwipeControlAction.VOLUME
                "speed" -> SwipeControlAction.SPEED
                else -> SwipeControlAction.OFF
            }

            return SwipeControlsSettings(
                enabled = preferences.getBoolean(C.PLAYER_SWIPE_CONTROLS_ENABLED, false),
                leftAction = action(C.PLAYER_SWIPE_LEFT_GESTURE, "brightness"),
                rightAction = action(C.PLAYER_SWIPE_RIGHT_GESTURE, "volume"),
                topAction = action(C.PLAYER_SWIPE_TOP_GESTURE, "off"),
                edgeWidthPercent = preferences.getInt(C.PLAYER_SWIPE_EDGE_WIDTH_PERCENT, 37).coerceIn(5, 50),
                speedZoneHeightPercent = preferences.getInt(C.PLAYER_SWIPE_SPEED_ZONE_HEIGHT_PERCENT, 30).coerceIn(5, 75),
                brightnessSensitivity = preferences.getInt(C.PLAYER_SWIPE_BRIGHTNESS_SENSITIVITY, 50).coerceIn(1, 100),
                volumeSensitivity = preferences.getInt(C.PLAYER_SWIPE_VOLUME_SENSITIVITY, 50).coerceIn(1, 100),
                speedSensitivity = preferences.getInt(C.PLAYER_SWIPE_SPEED_SENSITIVITY, 50).coerceIn(1, 100),
                speedStep = preferences.getString(C.PLAYER_SWIPE_SPEED_STEP, "0.05")?.toFloatOrNull()
                    ?.takeIf { it.isFinite() && it > 0f } ?: 0.05f,
                ignoreWhenLocked = preferences.getBoolean(C.PLAYER_SWIPE_IGNORE_WHEN_LOCKED, true),
            )
        }
    }
}

/** Owns the temporary player-window brightness override across player view recreation. */
class PlayerSwipeBrightnessViewModel : ViewModel() {
    private var originalBrightness: Float? = null
    private var activeWindow: Window? = null
    private var currentOverride: Float? = null

    fun attach(window: Window) {
        activeWindow = window
        currentOverride?.let { apply(window, it) }
    }

    fun currentBrightness(window: Window): Float {
        currentOverride?.let { return it }
        val windowBrightness = window.attributes.screenBrightness
        if (windowBrightness >= 0f) return windowBrightness.coerceIn(0f, 1f)
        val systemBrightness = runCatching {
            Settings.System.getInt(window.context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return (systemBrightness / 255f).coerceIn(0f, 1f)
    }

    fun setBrightness(window: Window, value: Float) {
        if (originalBrightness == null) {
            originalBrightness = window.attributes.screenBrightness
        }
        activeWindow = window
        currentOverride = value.coerceIn(0f, 1f)
        apply(window, currentOverride!!)
    }

    fun restoreBrightness() {
        val original = originalBrightness ?: return
        activeWindow?.let { apply(it, original) }
        originalBrightness = null
        activeWindow = null
        currentOverride = null
    }

    private fun apply(window: Window, value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    override fun onCleared() {
        restoreBrightness()
    }
}

internal enum class SwipeMoveResult {
    NONE,
    PENDING,
    STARTED,
    ACTIVE,
    REJECTED,
    CONSUMED,
}

/** Reads and writes the device's media-stream volume for player swipe gestures. */
private class SystemMediaVolume(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun currentFraction(): Float? {
        val manager = audioManager ?: return null
        val range = volumeRange(manager) ?: return null
        return ((manager.getStreamVolume(AudioManager.STREAM_MUSIC) - range.first).toFloat() /
            (range.second - range.first)).coerceIn(0f, 1f)
    }

    fun setFraction(fraction: Float): Float? {
        val manager = audioManager ?: return null
        val range = volumeRange(manager) ?: return null
        val target = (range.first + fraction.coerceIn(0f, 1f) * (range.second - range.first))
            .roundToInt()
            .coerceIn(range.first, range.second)
        try {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (_: SecurityException) {
            // Keep the HUD tied to the actual value even when Android rejects the change.
        }
        return currentFraction()
    }

    private fun volumeRange(manager: AudioManager): Pair<Int, Int>? {
        if (manager.isVolumeFixed) return null
        val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        } else {
            0
        }
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return (min to max).takeIf { max > min }
    }
}

/** Resolves edge swipes and applies values only after a vertical gesture wins the touch. */
internal class PlayerSwipeGestureController(
    private val context: Context,
    private val window: Window,
    private val brightness: PlayerSwipeBrightnessViewModel,
    private val host: FrameLayout,
    private val getSpeed: () -> Float?,
    private val setSpeed: (Float) -> Unit,
) : SharedPreferences.OnSharedPreferenceChangeListener {

    private val preferences = context.prefs()
    private val systemMediaVolume = SystemMediaVolume(context)
    private val feedback = SwipeControlFeedbackView(context)
    private val handler = Handler(Looper.getMainLooper())
    private val hideFeedback = Runnable { feedback.visibility = View.GONE }
    private var candidateAction = SwipeControlAction.OFF
    private var candidateSettings: SwipeControlsSettings? = null
    private var isActive = false
    private var consumeUntilUp = false
    private var startX = 0f
    private var startY = 0f
    private var startHeight = 1
    private var startValue = 0f
    private var currentValue = Float.NaN
    private var startVolumePercent = -1
    private var speedZoneHeight = 1f

    val hasCandidateOrActive: Boolean
        get() = candidateAction != SwipeControlAction.OFF || isActive || consumeUntilUp

    val shouldConsumeCurrentSequence: Boolean
        get() = consumeUntilUp

    init {
        brightness.attach(window)
        host.addView(
            feedback,
            FrameLayout.LayoutParams(dp(220), dp(82), Gravity.CENTER),
        )
        feedback.visibility = View.GONE
        preferences.registerOnSharedPreferenceChangeListener(this)
    }

    fun begin(event: MotionEvent, canHandle: Boolean, interactionLocked: Boolean): Boolean {
        clearGesture(hide = false)
        if (!canHandle) return false
        brightness.attach(window)
        val settings = SwipeControlsSettings.read(context)
        if (!settings.enabled || (interactionLocked && settings.ignoreWhenLocked)) return false
        val action = settings.actionAt(event.x, event.y, host.width, host.height)
        if (action == SwipeControlAction.OFF) return false
        val value = when (action) {
            SwipeControlAction.BRIGHTNESS -> brightness.currentBrightness(window)
            SwipeControlAction.VOLUME -> systemMediaVolume.currentFraction() ?: return false
            SwipeControlAction.SPEED -> getSpeed()?.takeIf(Float::isFinite)
                ?: return false
            SwipeControlAction.OFF -> return false
        }
        candidateAction = action
        candidateSettings = settings
        startX = event.x
        startY = event.y
        startHeight = host.height.coerceAtLeast(1)
        startValue = value
        currentValue = Float.NaN
        startVolumePercent = (value * 100f).roundToInt().coerceIn(0, 100)
        speedZoneHeight = startHeight * settings.speedZoneHeightPercent / 100f
        return true
    }

    fun onMove(event: MotionEvent, touchSlop: Int): SwipeMoveResult {
        if (consumeUntilUp) return SwipeMoveResult.CONSUMED
        if (candidateAction == SwipeControlAction.OFF) return SwipeMoveResult.NONE
        if (event.pointerCount > 1) {
            cancelCandidate()
            return SwipeMoveResult.REJECTED
        }
        if (isActive) {
            applyAt(event.y)
            return SwipeMoveResult.ACTIVE
        }

        val deltaX = event.x - startX
        val deltaY = event.y - startY
        val absX = abs(deltaX)
        val absY = abs(deltaY)
        if (absY > touchSlop && absY > absX * 1.2f) {
            isActive = true
            applyAt(event.y)
            return SwipeMoveResult.STARTED
        }
        // Only keep a candidate while movement stays within touch slop. Once a
        // gesture crosses slop without being vertical-dominant, let the player
        // handle the rest of the sequence as it normally would.
        if (absX > touchSlop || absY > touchSlop) {
            cancelCandidate()
            return SwipeMoveResult.REJECTED
        }
        return SwipeMoveResult.PENDING
    }

    fun onUp(event: MotionEvent): Boolean {
        if (consumeUntilUp) {
            clearGesture(hide = true)
            return true
        }
        if (!isActive) {
            clearGesture(hide = false)
            return false
        }
        applyAt(event.y)
        clearGesture(hide = true)
        return true
    }

    fun onPointerDown(): Boolean {
        // Do not resume the player with a partial stream after withholding pending moves.
        if (isActive || consumeUntilUp || candidateAction != SwipeControlAction.OFF) {
            consumeGestureUntilUp()
            return true
        }
        cancelCandidate()
        return false
    }

    fun onCancel(): Boolean {
        val consumed = isActive || consumeUntilUp
        clearGesture(hide = true)
        return consumed
    }

    fun restoreBrightness() = brightness.restoreBrightness()

    fun endSession(restoreBrightness: Boolean = true) {
        clearGesture(hide = true)
        if (restoreBrightness) brightness.restoreBrightness()
    }

    fun release(restoreBrightness: Boolean) {
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        clearGesture(hide = true)
        (feedback.parent as? android.view.ViewGroup)?.removeView(feedback)
        if (restoreBrightness) brightness.restoreBrightness()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if ((key == null || key == C.PLAYER_SWIPE_CONTROLS_ENABLED) &&
            !preferences.getBoolean(C.PLAYER_SWIPE_CONTROLS_ENABLED, false)
        ) {
            clearGesture(hide = true)
            brightness.restoreBrightness()
        }
    }

    private fun applyAt(y: Float) {
        val settings = candidateSettings ?: return
        val deltaFraction = (startY - y) / startHeight
        val value = when (candidateAction) {
            SwipeControlAction.BRIGHTNESS -> {
                val next = (startValue + deltaFraction * (50f / settings.brightnessSensitivity)).coerceIn(0f, 1f)
                if (next != currentValue) brightness.setBrightness(window, next)
                next
            }
            SwipeControlAction.VOLUME -> {
                val next = (startValue + deltaFraction * (settings.volumeSensitivity / 50f)).coerceIn(0f, 1f)
                val nextPercent = (next * 100f).roundToInt().coerceIn(0, 100)
                val shouldSetVolume = nextPercent != startVolumePercent
                if (shouldSetVolume) {
                    startVolumePercent = nextPercent
                }
                val actual = if (shouldSetVolume) {
                    systemMediaVolume.setFraction(nextPercent / 100f)
                } else {
                    systemMediaVolume.currentFraction()
                }
                actual ?: run {
                    consumeGestureUntilUp()
                    return
                }
            }
            SwipeControlAction.SPEED -> {
                val stepDistance = max(1f, speedZoneHeight * (settings.speedSensitivity / 50f) / 5f)
                val steps = (deltaFraction * startHeight / stepDistance).roundToInt()
                val step = settings.speedStep
                val next = (startValue + steps * step).coerceIn(0.25f, 8f)
                if (next != currentValue) {
                    setSpeed(next)
                    preferences.edit { putFloat(C.PLAYER_SPEED, next) }
                }
                next
            }
            SwipeControlAction.OFF -> return
        }
        currentValue = value
        showFeedback(value)
    }

    private fun showFeedback(value: Float) {
        val title: String
        val valueText: String
        val fraction: Float
        when (candidateAction) {
            SwipeControlAction.BRIGHTNESS -> {
                title = context.getString(R.string.settings_player_swipe_brightness)
                valueText = "${(value * 100f).roundToInt()}%"
                fraction = value
            }
            SwipeControlAction.VOLUME -> {
                title = context.getString(R.string.settings_player_swipe_volume)
                valueText = "${(value * 100f).roundToInt()}%"
                fraction = value
            }
            SwipeControlAction.SPEED -> {
                title = context.getString(R.string.settings_player_swipe_speed)
                valueText = String.format(Locale.ROOT, "%.2fx", value)
                fraction = ((value - 0.25f) / 7.75f).coerceIn(0f, 1f)
            }
            SwipeControlAction.OFF -> return
        }
        feedback.setContent(title, valueText, fraction)
        feedback.visibility = View.VISIBLE
        handler.removeCallbacks(hideFeedback)
        handler.postDelayed(hideFeedback, FEEDBACK_HIDE_DELAY_MS)
    }

    private fun cancelCandidate() {
        candidateAction = SwipeControlAction.OFF
        candidateSettings = null
        isActive = false
        currentValue = Float.NaN
    }

    private fun consumeGestureUntilUp() {
        candidateAction = SwipeControlAction.OFF
        candidateSettings = null
        isActive = false
        consumeUntilUp = true
        handler.removeCallbacks(hideFeedback)
        feedback.visibility = View.GONE
    }

    private fun clearGesture(hide: Boolean) {
        candidateAction = SwipeControlAction.OFF
        candidateSettings = null
        isActive = false
        consumeUntilUp = false
        currentValue = Float.NaN
        startVolumePercent = -1
        if (hide) {
            handler.removeCallbacks(hideFeedback)
            feedback.visibility = View.GONE
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val FEEDBACK_HIDE_DELAY_MS = 900L
    }
}

private class SwipeControlFeedbackView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
    private val bounds = RectF()
    private var title = ""
    private var value = ""
    private var fraction = 0f

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setContent(title: String, value: String, fraction: Float) {
        this.title = title
        this.value = value
        this.fraction = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        backgroundPaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.DKGRAY)
        backgroundPaint.alpha = 160
        canvas.drawRoundRect(bounds, dp(12f), dp(12f), backgroundPaint)

        val centerX = width / 2f
        titlePaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.LTGRAY)
        titlePaint.textSize = 13f * scaledDensity
        valuePaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        valuePaint.textSize = 20f * scaledDensity
        canvas.drawText(title, centerX, dp(24f) - titlePaint.ascent() / 2f, titlePaint)
        canvas.drawText(value, centerX, dp(51f) - valuePaint.ascent() / 2f, valuePaint)

        val barWidth = width - dp(48f)
        val barLeft = (width - barWidth) / 2f
        val barTop = height - dp(15f)
        trackPaint.color = titlePaint.color
        trackPaint.alpha = 70
        canvas.drawRoundRect(barLeft, barTop, barLeft + barWidth, barTop + dp(4f), dp(2f), dp(2f), trackPaint)
        progressPaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer, Color.WHITE)
        canvas.drawRoundRect(barLeft, barTop, barLeft + barWidth * fraction, barTop + dp(4f), dp(2f), dp(2f), progressPaint)
    }

    private fun dp(value: Float): Float = value * density
}
