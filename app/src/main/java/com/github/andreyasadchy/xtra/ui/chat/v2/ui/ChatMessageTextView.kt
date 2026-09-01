package com.github.andreyasadchy.xtra.ui.chat.v2.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetState
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPiece
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel

open class ChatMessageTextView(context: Context, private val assets: ChatAssetRepository) : AppCompatTextView(context) {
    private var keys = emptySet<ChatAssetKey>()
    private val drawables = HashMap<ChatAssetKey, Drawable>()
    private val drawableHandles = HashMap<ChatAssetKey, Any>()
    private val invalidator: () -> Unit = { postInvalidateOnAnimation() }

    fun bind(row: ChatRowUiModel) {
        keys.forEach { assets.removeObserver(it, invalidator) }
        drawables.values.forEach { it.stopIfNeeded(); it.callback = null }
        drawables.clear()
        drawableHandles.clear()
        keys = row.pieces.flatMap { piece ->
            val spec = when (piece) {
                is ChatPiece.Badge -> piece.asset
                is ChatPiece.Emote -> piece.asset
                is ChatPiece.Cheermote -> piece.asset
                is ChatPiece.Gif -> piece.asset
                else -> null
            }
            spec?.let { it.allKeys() }.orEmpty()
        }.toSet()
        keys.forEach { assets.observe(it, invalidator) }
        setBackgroundColor(row.background)
        gravity = Gravity.CENTER_VERTICAL
        val output = SpannableStringBuilder()
        row.pieces.forEach { piece ->
            when (piece) {
                is ChatPiece.Text -> appendStyled(output, piece.value, piece.color)
                is ChatPiece.Username -> appendStyled(output, piece.value + ": ", piece.color)
                is ChatPiece.Mention -> appendStyled(output, piece.value, null)
                is ChatPiece.Badge -> appendAsset(output, piece.asset, "badge")
                is ChatPiece.Emote -> appendAsset(output, piece.asset, piece.fallback)
                is ChatPiece.Gif -> appendAsset(output, piece.asset, piece.fallback)
                is ChatPiece.Cheermote -> {
                    appendAsset(output, piece.asset, piece.bits.toString())
                    appendStyled(output, " ${piece.bits}", piece.color)
                }
            }
        }
        if (row.timestampText != null) output.insert(0, "${row.timestampText} ")
        if (row.isAction) output.setSpan(StyleSpan(android.graphics.Typeface.ITALIC), 0, output.length, 0)
        contentDescription = row.accessibilityText
        text = output
    }

    private fun appendStyled(output: SpannableStringBuilder, value: String, color: Int?) {
        val start = output.length
        output.append(value)
        if (color != null) output.setSpan(ForegroundColorSpan(color), start, output.length, 0)
    }

    private fun appendAsset(output: SpannableStringBuilder, spec: ChatAssetSpec, fallback: String) {
        val start = output.length
        output.append(" ")
        output.setSpan(ChatAssetSpan(spec, fallback) { drawableLayersFor(spec) }, start, output.length, 0)
    }

    private fun drawableFor(key: ChatAssetKey, spec: ChatAssetSpec): Drawable? {
        val handle = (assets.peek(key) as? ChatAssetState.Ready)?.image ?: return null
        val drawable = if (drawableHandles[key] !== handle) {
            drawables.remove(key)?.also { it.stopIfNeeded(); it.callback = null }
            drawableHandles[key] = handle
            handle.newDrawable().also { drawables[key] = it }
        } else {
            drawables[key] ?: return null
        }
        drawable.setBounds(0, 0, spec.computedWidth, spec.targetHeight)
        if (isAttachedToWindow) {
            drawable.callback = this
            drawable.startIfNeeded()
        }
        return drawable
    }

    private fun drawableLayersFor(spec: ChatAssetSpec): List<DrawableLayer>? {
        val specs = spec.flatten()
        if (specs.any { assets.peek(it.key) !is ChatAssetState.Ready }) return null
        val layers = ArrayList<DrawableLayer>(specs.size)
        for (layerSpec in specs) {
            val drawable = drawableFor(layerSpec.key, layerSpec) ?: return null
            layers += DrawableLayer(layerSpec, drawable)
        }
        return layers
    }

    fun recycle() {
        keys.forEach { assets.removeObserver(it, invalidator) }
        drawables.values.forEach { it.stopIfNeeded(); it.callback = null }
        drawables.clear()
        drawableHandles.clear()
        keys = emptySet()
        text = null
    }

    override fun onDetachedFromWindow() {
        keys.forEach { assets.removeObserver(it, invalidator) }
        drawables.values.forEach { it.stopIfNeeded(); it.callback = null }
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        keys.forEach { assets.observe(it, invalidator) }
        drawables.values.forEach { drawable -> drawable.callback = this; drawable.startIfNeeded() }
    }

    override fun verifyDrawable(who: Drawable): Boolean =
        super.verifyDrawable(who) || drawables.values.any { it === who }
}

private fun Drawable.stopIfNeeded() { (this as? Animatable)?.stop() }
private fun Drawable.startIfNeeded() {
    (this as? Animatable)?.let { if (!it.isRunning) it.start() }
}

private fun ChatAssetSpec.allKeys(): List<ChatAssetKey> = buildList {
    add(key)
    overlays.forEach { addAll(it.allKeys()) }
}

private fun ChatAssetSpec.flatten(): List<ChatAssetSpec> = buildList {
    add(this@flatten)
    overlays.forEach { addAll(it.flatten()) }
}

private data class DrawableLayer(val spec: ChatAssetSpec, val drawable: Drawable)

private class ChatAssetSpan(
    private val spec: ChatAssetSpec,
    private val fallback: String,
    private val drawables: () -> List<DrawableLayer>?,
) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val height = spec.compositionHeight
        if (fm != null) {
            val currentHeight = fm.descent - fm.ascent
            if (height > currentHeight) {
                val extra = height - currentHeight
                val above = extra / 2
                val below = extra - above
                fm.ascent -= above
                fm.top = fm.ascent
                fm.descent += below
                fm.bottom = fm.descent
            }
        }
        return spec.compositionWidth
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val oldColor = paint.color
        val oldStyle = paint.style
        val oldTextSize = paint.textSize
        val oldTextAlign = paint.textAlign
        val centerY = (top + bottom) / 2f
        val rect = RectF(x, centerY - spec.compositionHeight / 2f, x + spec.compositionWidth, centerY + spec.compositionHeight / 2f)
        val images = drawables()
        if (images != null) {
            images.forEach { layer ->
                val width = layer.spec.computedWidth
                val height = layer.spec.targetHeight
                val left = rect.centerX() - width / 2f
                val layerRect = RectF(left, rect.centerY() - height / 2f, left + width, rect.centerY() + height / 2f)
                layer.drawable.setBounds(layerRect.left.toInt(), layerRect.top.toInt(), layerRect.right.toInt(), layerRect.bottom.toInt())
                layer.drawable.draw(canvas)
            }
        } else {
            paint.color = 0x55777777
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(rect, spec.targetHeight / 4f, spec.targetHeight / 4f, paint)
            paint.color = 0xFFDDDDDD.toInt()
            canvas.drawCircle(rect.centerX(), rect.centerY(), (spec.targetHeight / 8f).coerceAtLeast(1f), paint)
            paint.color = 0xFF202020.toInt()
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = (spec.compositionHeight * 0.55f).coerceAtLeast(6f)
            val label = fallback.trim().firstOrNull()?.toString() ?: "?"
            val baseline = rect.centerY() - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(label, rect.centerX(), baseline, paint)
        }
        paint.color = oldColor
        paint.style = oldStyle
        paint.textSize = oldTextSize
        paint.textAlign = oldTextAlign
    }
}
