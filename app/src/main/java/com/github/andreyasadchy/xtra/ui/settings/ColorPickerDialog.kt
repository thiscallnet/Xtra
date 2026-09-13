package com.github.andreyasadchy.xtra.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.accessibility.AccessibilityNodeInfo
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import com.github.andreyasadchy.xtra.R
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.roundToInt

internal object ColorPickerDialog {

    fun show(
        context: Context,
        title: CharSequence,
        @ColorInt initialColor: Int,
        @ColorInt defaultColor: Int?,
        allowAlpha: Boolean,
        onColorSelected: (String) -> Boolean,
        onDefault: (() -> Boolean)? = null,
    ): AlertDialog {
        val state = PickerState(initialColor, allowAlpha)
        val density = context.resources.displayMetrics.density
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(density, 24), 0, dp(density, 24), 0)
        }

        val swatch = View(context).apply {
            minimumHeight = dp(density, 34)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = context.getString(R.string.settings_color_picker_preview)
        }
        content.addView(
            swatch,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(density, 34)).apply {
                bottomMargin = dp(density, 12)
            },
        )

        val sample = TextView(context).apply {
            text = context.getString(R.string.settings_color_picker_sample)
            setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface))
            setPadding(dp(density, 14), dp(density, 10), dp(density, 14), dp(density, 10))
            minHeight = dp(density, 64)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        content.addView(
            sample,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(density, 14)
            },
        )
        val contrastWarning = TextView(context).apply {
            text = context.getString(R.string.settings_color_picker_low_contrast)
            setTextColor(context.getColor(R.color.accent))
            textSize = 12f
            visibility = View.GONE
        }
        content.addView(
            contrastWarning,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(density, 10)
            },
        )

        val saturationValue = SaturationValueView(context, state).apply {
            contentDescription = context.getString(R.string.settings_color_picker_saturation_brightness)
        }
        content.addView(
            saturationValue,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(density, 210)).apply {
                bottomMargin = dp(density, 12)
            },
        )

        val hue = HueView(context, state).apply {
            contentDescription = context.getString(R.string.settings_color_picker_hue)
        }
        content.addView(
            hue,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(density, 48)).apply {
                bottomMargin = dp(density, 12)
            },
        )

        val alpha = if (allowAlpha) {
            AlphaView(context, state).apply {
                contentDescription = context.getString(R.string.settings_color_picker_alpha)
            }
        } else null
        alpha?.let {
            content.addView(
                it,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(density, 48)).apply {
                    bottomMargin = dp(density, 12)
                },
            )
        }

        val inputLayout = TextInputLayout(context).apply {
            hint = context.getString(R.string.settings_color_picker_hex)
            endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
        }
        val hexInput = TextInputEditText(inputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setSelectAllOnFocus(true)
        }
        inputLayout.addView(hexInput)
        content.addView(inputLayout)

        var updatingHex = false
        fun refreshControls(updateHex: Boolean) {
            swatch.setBackgroundColor(state.color())
            sample.setTextColor(state.color())
            val compositeColor = ColorUtils.compositeColors(state.color(), MaterialColors.getColor(sample, com.google.android.material.R.attr.colorSurface))
            contrastWarning.visibility = if (ColorUtils.calculateContrast(compositeColor, MaterialColors.getColor(sample, com.google.android.material.R.attr.colorSurface)) < 4.5) {
                View.VISIBLE
            } else {
                View.GONE
            }
            saturationValue.invalidate()
            hue.invalidate()
            alpha?.invalidate()
            swatch.contentDescription = context.getString(
                R.string.settings_color_picker_preview_value,
                formatColor(state.color(), allowAlpha),
            )
            saturationValue.contentDescription = context.getString(
                R.string.settings_color_picker_saturation_brightness_value,
                state.saturation.roundToInt().coerceIn(0, 100),
                (state.value * 100).roundToInt().coerceIn(0, 100),
            )
            hue.contentDescription = context.getString(
                R.string.settings_color_picker_hue_value,
                state.hue.roundToInt().mod(360),
            )
            alpha?.contentDescription = context.getString(
                R.string.settings_color_picker_alpha_value,
                (state.alpha * 100).roundToInt().coerceIn(0, 100),
            )
            if (updateHex) {
                updatingHex = true
                hexInput.setText(formatColor(state.color(), allowAlpha))
                hexInput.setSelection(hexInput.length())
                updatingHex = false
            }
        }

        val onStateChanged = {
            inputLayout.error = null
            refreshControls(updateHex = true)
        }
        saturationValue.onColorChanged = onStateChanged
        hue.onColorChanged = onStateChanged
        alpha?.onColorChanged = onStateChanged
        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updatingHex) return
                parsePickerColor(s?.toString(), allowAlpha)?.let {
                    state.setColor(it)
                    inputLayout.error = null
                    refreshControls(updateHex = false)
                }
            }
        })

        refreshControls(updateHex = true)
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(ScrollView(context).apply {
                isFillViewport = false
                addView(content)
            })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_color_picker_apply, null)
        if (defaultColor != null) {
            builder.setNeutralButton(R.string.settings_color_picker_default, null)
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val parsed = parsePickerColor(hexInput.text?.toString(), allowAlpha)
                if (parsed == null) {
                    inputLayout.error = context.getString(R.string.settings_color_picker_invalid)
                    hexInput.requestFocus()
                    return@setOnClickListener
                }
                if (onColorSelected(formatColor(parsed, allowAlpha))) dialog.dismiss()
            }
            if (defaultColor != null) {
                dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                    if (onDefault?.invoke() != true) {
                        state.setColor(defaultColor)
                        inputLayout.error = null
                        refreshControls(updateHex = true)
                    } else {
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
        return dialog
    }

    private fun dp(density: Float, value: Int): Int = (value * density).roundToInt()
}

private class PickerState(initialColor: Int, private val allowAlpha: Boolean) {
    var hue = 0f
    var saturation = 0f
    var value = 0f
    var alpha = 1f

    init {
        setColor(initialColor)
    }

    fun color(): Int = Color.HSVToColor((alpha * 255).roundToInt().coerceIn(0, 255), floatArrayOf(hue, saturation, value))

    fun setColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
        alpha = if (allowAlpha) Color.alpha(color) / 255f else 1f
    }
}

private abstract class PickerBarView(
    context: Context,
    protected val state: PickerState,
) : View(context) {
    var onColorChanged: (() -> Unit)? = null
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isFocusable = true
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_UP) {
            updateFromTouch(event.x, event.y)
            if (event.action != MotionEvent.ACTION_UP) parent.requestDisallowInterceptTouchEvent(true)
            if (event.action == MotionEvent.ACTION_UP) performClick()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        announceAccessibilityState()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MINUS -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS -> 1
            else -> 0
        }
        if (direction != 0) {
            adjustForAccessibility(direction, keyCode)
            onColorChanged?.invoke()
            invalidate()
            announceAccessibilityState()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        val direction = when (action) {
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -1
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 1
            else -> 0
        }
        if (direction == 0) return super.performAccessibilityAction(action, arguments)
        adjustForAccessibility(direction)
        onColorChanged?.invoke()
        invalidate()
        announceAccessibilityState()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.isScrollable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        accessibilityRangeInfo()?.let { info.rangeInfo = it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            info.stateDescription = contentDescription
        }
    }

    protected abstract fun updateFromTouch(x: Float, y: Float)
    protected abstract fun adjustForAccessibility(direction: Int)
    protected open fun adjustForAccessibility(direction: Int, keyCode: Int) = adjustForAccessibility(direction)
    protected open fun accessibilityRangeInfo(): AccessibilityNodeInfo.RangeInfo? = null

    private fun announceAccessibilityState() {
        sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED)
        announceForAccessibility(contentDescription)
    }

    protected fun position(value: Float, length: Float): Float = value.coerceIn(0f, 1f) * length
}

private class SaturationValueView(context: Context, state: PickerState) : PickerBarView(context, state) {
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val hueColor = Color.HSVToColor(floatArrayOf(state.hue, 1f, 1f))
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, Color.WHITE, hueColor, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        drawSelector(canvas, position(state.saturation, width.toFloat()), position(1f - state.value, height.toFloat()))
    }

    override fun updateFromTouch(x: Float, y: Float) {
        state.saturation = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        state.value = (1f - y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
        onColorChanged?.invoke()
        invalidate()
    }

    override fun adjustForAccessibility(direction: Int) {
        state.value = (state.value + direction * 0.05f).coerceIn(0f, 1f)
    }

    override fun adjustForAccessibility(direction: Int, keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                state.saturation = (state.saturation + direction * 0.05f).coerceIn(0f, 1f)
            }
            else -> state.value = (state.value + direction * 0.05f).coerceIn(0f, 1f)
        }
    }

    private fun drawSelector(canvas: Canvas, x: Float, y: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = if (state.value > .5f) Color.BLACK else Color.WHITE
        canvas.drawCircle(x, y, 9f, paint)
        paint.style = Paint.Style.FILL
    }
}

private class HueView(context: Context, state: PickerState) : PickerBarView(context, state) {
    private val colors = intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, colors, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        val x = position(state.hue / 360f, width.toFloat())
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.WHITE
        canvas.drawCircle(x, height / 2f, 9f, paint)
        paint.color = Color.BLACK
        paint.strokeWidth = 1f
        canvas.drawCircle(x, height / 2f, 10f, paint)
        paint.style = Paint.Style.FILL
    }

    override fun updateFromTouch(x: Float, y: Float) {
        state.hue = (x / width.coerceAtLeast(1) * 360f).coerceIn(0f, 359.99f)
        onColorChanged?.invoke()
        invalidate()
    }

    override fun adjustForAccessibility(direction: Int) {
        state.hue = (state.hue + direction * 5f).mod(360f)
    }

    override fun accessibilityRangeInfo(): AccessibilityNodeInfo.RangeInfo =
        AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
            0f,
            360f,
            state.hue,
        )
}

private class AlphaView(context: Context, state: PickerState) : PickerBarView(context, state) {
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val tile = 8f * resources.displayMetrics.density
        val checker = Paint()
        for (x in 0 until (width / tile).toInt() + 1) {
            for (y in 0 until (height / tile).toInt() + 1) {
                checker.color = if ((x + y) % 2 == 0) 0xFFE0E0E0.toInt() else Color.WHITE
                canvas.drawRect(x * tile, y * tile, (x + 1) * tile, (y + 1) * tile, checker)
            }
        }
        val opaque = Color.HSVToColor(floatArrayOf(state.hue, state.saturation, state.value))
        paint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            0f,
            ColorUtils.setAlphaComponent(opaque, 0),
            opaque,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        val x = position(state.alpha, width.toFloat())
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.WHITE
        canvas.drawCircle(x, height / 2f, 9f, paint)
        paint.color = Color.BLACK
        paint.strokeWidth = 1f
        canvas.drawCircle(x, height / 2f, 10f, paint)
        paint.style = Paint.Style.FILL
    }

    override fun updateFromTouch(x: Float, y: Float) {
        state.alpha = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        onColorChanged?.invoke()
        invalidate()
    }

    override fun adjustForAccessibility(direction: Int) {
        state.alpha = (state.alpha + direction * 0.05f).coerceIn(0f, 1f)
    }

    override fun accessibilityRangeInfo(): AccessibilityNodeInfo.RangeInfo =
        AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
            0f,
            1f,
            state.alpha,
        )
}

internal fun parsePickerColor(raw: String?, allowAlpha: Boolean): Int? {
    val value = raw?.trim()?.removePrefix("#") ?: return null
    val validLength = if (allowAlpha) value.length == 6 || value.length == 8 else value.length == 6
    if (!validLength || !value.matches(Regex("[0-9a-fA-F]+"))) return null
    return if (value.length == 8) {
        (value.toLong(16).toInt())
    } else {
        (value.toLong(16).toInt() or 0xFF000000.toInt())
    }
}

internal fun formatColor(@ColorInt color: Int, allowAlpha: Boolean): String = if (allowAlpha && (color ushr 24 and 0xff) != 255) {
    "#%08X".format(color)
} else {
    "#%06X".format(color and 0xFFFFFF)
}
