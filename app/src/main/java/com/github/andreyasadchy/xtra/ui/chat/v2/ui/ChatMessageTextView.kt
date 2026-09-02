package com.github.andreyasadchy.xtra.ui.chat.v2.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.Gravity
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.AppCompatTextView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetState
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEmoteInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatGifInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPiece
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowBackground
import com.github.andreyasadchy.xtra.ui.view.CenteredImageSpan
import kotlin.math.roundToInt

open class ChatMessageTextView(context: Context, private val assets: ChatAssetRepository) : AppCompatTextView(context) {
    private val initialPaddingStart = paddingStart
    private val initialPaddingTop = paddingTop
    private val initialPaddingEnd = paddingEnd
    private val initialPaddingBottom = paddingBottom
    private var keys = emptySet<ChatAssetKey>()
    private val drawables = HashMap<ChatAssetKey, Drawable>()
    private val drawableHandles = HashMap<ChatAssetKey, Any>()
    private val invalidator: () -> Unit = { postInvalidateOnAnimation() }
    private var renderingActive = true
    private var animateGifs = true
    private var boundMessageId: ChatMessageId? = null
    private var longPressConsumed = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var longPressRunnable: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var onMessageLongClick: ((ChatMessageId) -> Unit)? = null
    private var onEmoteClick: ((ChatEmoteInteraction) -> Unit)? = null
    private var onGifClick: ((ChatGifInteraction) -> Unit)? = null

    fun setInteractionCallbacks(
        onMessageLongClick: ((ChatMessageId) -> Unit)?,
        onEmoteClick: ((ChatEmoteInteraction) -> Unit)?,
        onGifClick: ((ChatGifInteraction) -> Unit)? = null,
    ) {
        this.onMessageLongClick = onMessageLongClick
        this.onEmoteClick = onEmoteClick
        this.onGifClick = onGifClick
    }

    fun setMessageTextSizeSp(value: Float) {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, value)
    }

    fun setAnimateGifs(value: Boolean) {
        animateGifs = value
        if (!value) {
            drawables.values.forEach { it.stopIfNeeded() }
        } else if (renderingActive && isAttachedToWindow) {
            drawables.values.forEach { drawable ->
                drawable.callback = this
                drawable.startIfNeeded()
            }
        }
    }

    fun bind(row: ChatRowUiModel) {
        boundMessageId = row.id
        longPressConsumed = false
        longPressRunnable?.let(mainHandler::removeCallbacks)
        longPressRunnable = null
        isLongClickable = onMessageLongClick != null
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
        when (row.backgroundStyle) {
            ChatRowBackground.NORMAL -> setBackgroundColor(row.background)
            ChatRowBackground.HIGHLIGHT -> setBackgroundResource(R.drawable.bg_chat_highlight)
            ChatRowBackground.FIRST_CHATTER -> setBackgroundResource(R.drawable.bg_chat_first_chatter)
            ChatRowBackground.FIRST_CHATTER_TINT -> setBackgroundColor(ContextCompat.getColor(context, R.color.chatMessageFirst))
            ChatRowBackground.WATCH_STREAK -> setBackgroundResource(R.drawable.bg_chat_watch_streak)
            ChatRowBackground.REWARD -> setBackgroundColor(ContextCompat.getColor(context, R.color.chatMessageReward))
            ChatRowBackground.NOTICE -> setBackgroundColor(ContextCompat.getColor(context, R.color.chatMessageNotice))
        }
        val extraStartPadding = if (row.backgroundStyle == ChatRowBackground.WATCH_STREAK) {
            (30 * resources.displayMetrics.density).roundToInt()
        } else {
            0
        }
        setPaddingRelative(
            initialPaddingStart + extraStartPadding,
            initialPaddingTop,
            initialPaddingEnd,
            initialPaddingBottom,
        )
        gravity = Gravity.CENTER_VERTICAL
        movementMethod = LinkMovementMethod.getInstance()
        linksClickable = true
        highlightColor = Color.TRANSPARENT
        val output = SpannableStringBuilder()
        row.pieces.forEach { piece ->
            when (piece) {
                is ChatPiece.Text -> appendStyled(output, piece.value, piece.color, piece.bold)
                is ChatPiece.Username -> appendStyled(output, piece.value + ": ", piece.color, piece.bold)
                is ChatPiece.Icon -> appendIcon(output, piece)
                is ChatPiece.Mention -> appendStyled(output, piece.value, null)
                is ChatPiece.Badge -> appendAsset(output, piece.asset, "badge")
                is ChatPiece.Emote -> appendAsset(output, piece.asset, piece.fallback, piece.interaction)
                is ChatPiece.Gif -> appendAsset(output, piece.asset, piece.fallback, gifInteraction = piece.interaction)
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

    /**
     * TextView's normal long-click is intentionally the row action. In particular,
     * it must win over LinkMovementMethod when the pointer goes down on an emote.
     * The consumed flag suppresses LinkMovementMethod's ACTION_UP click after the
     * long press has already opened the user card.
     */
    override fun performLongClick(): Boolean {
        val id = boundMessageId
        val callback = onMessageLongClick
        if (longPressConsumed) return true
        if (id != null && callback != null) {
            longPressConsumed = true
            callback(id)
            return true
        }
        return super.performLongClick()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressConsumed = false
                touchDownX = event.x
                touchDownY = event.y
                longPressRunnable?.let(mainHandler::removeCallbacks)
                if (onMessageLongClick != null && boundMessageId != null) {
                    longPressRunnable = Runnable { performLongClick() }.also {
                        mainHandler.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (kotlin.math.abs(event.x - touchDownX) > slop ||
                    kotlin.math.abs(event.y - touchDownY) > slop
                ) {
                    longPressRunnable?.let(mainHandler::removeCallbacks)
                    longPressRunnable = null
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                longPressRunnable?.let(mainHandler::removeCallbacks)
                longPressRunnable = null
            }

            MotionEvent.ACTION_UP -> {
                longPressRunnable?.let(mainHandler::removeCallbacks)
                longPressRunnable = null
                if (longPressConsumed) {
                    longPressConsumed = false
                    return true
                }
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && longPressConsumed) {
            longPressConsumed = false
            return true
        }
        val handled = super.onTouchEvent(event)
        return if (event.actionMasked == MotionEvent.ACTION_UP && longPressConsumed) {
            longPressConsumed = false
            true
        } else {
            handled
        }
    }

    private fun appendStyled(output: SpannableStringBuilder, value: String, color: Int?, bold: Boolean = false) {
        val start = output.length
        output.append(value)
        if (color != null) output.setSpan(ForegroundColorSpan(color), start, output.length, 0)
        if (bold) output.setSpan(StyleSpan(Typeface.BOLD), start, output.length, 0)
    }

    private fun appendIcon(output: SpannableStringBuilder, piece: ChatPiece.Icon) {
        val drawable = ContextCompat.getDrawable(context, piece.drawableRes)?.mutate() ?: return
        piece.tint?.let(drawable::setTint)
        val size = (piece.sizeDp * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        drawable.setBounds(0, 0, size, size)
        val start = output.length
        output.append('.')
        output.setSpan(ForegroundColorSpan(Color.TRANSPARENT), start, output.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        output.setSpan(CenteredImageSpan(drawable, size, size), start, output.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendAsset(
        output: SpannableStringBuilder,
        spec: ChatAssetSpec,
        fallback: String,
        interaction: ChatEmoteInteraction? = null,
        gifInteraction: ChatGifInteraction? = null,
    ) {
        val start = output.length
        output.append(" ")
        val end = output.length
        output.setSpan(ChatAssetSpan(spec, fallback) { drawableLayersFor(spec) }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        interaction?.let { value ->
            output.setSpan(object : ClickableSpan() {
                override fun onClick(widget: android.view.View) {
                    onEmoteClick?.invoke(value)
                }

                override fun updateDrawState(ds: TextPaint) = Unit
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        gifInteraction?.let { value ->
            output.setSpan(object : ClickableSpan() {
                override fun onClick(widget: android.view.View) {
                    onGifClick?.invoke(value)
                }

                override fun updateDrawState(ds: TextPaint) = Unit
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
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
        if (isAttachedToWindow && renderingActive) {
            drawable.callback = this
            if (animateGifs) drawable.startIfNeeded()
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
        longPressRunnable?.let(mainHandler::removeCallbacks)
        longPressRunnable = null
        keys.forEach { assets.removeObserver(it, invalidator) }
        drawables.values.forEach { it.stopIfNeeded(); it.callback = null }
        drawables.clear()
        drawableHandles.clear()
        keys = emptySet()
        text = null
    }

    /** Stops asset callbacks while the containing chat surface is hidden. */
    fun setRenderingActive(active: Boolean) {
        if (renderingActive == active) return
        renderingActive = active
        if (active) {
            if (isAttachedToWindow) keys.forEach { assets.observe(it, invalidator) }
            if (isAttachedToWindow) {
                drawables.values.forEach { drawable ->
                    drawable.callback = this
                    if (animateGifs) drawable.startIfNeeded()
                }
            }
        } else {
            keys.forEach { assets.removeObserver(it, invalidator) }
            drawables.values.forEach { drawable -> drawable.stopIfNeeded(); drawable.callback = null }
        }
    }

    override fun onDetachedFromWindow() {
        longPressRunnable?.let(mainHandler::removeCallbacks)
        longPressRunnable = null
        keys.forEach { assets.removeObserver(it, invalidator) }
        drawables.values.forEach { it.stopIfNeeded(); it.callback = null }
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (renderingActive) {
            keys.forEach { assets.observe(it, invalidator) }
            drawables.values.forEach { drawable ->
                drawable.callback = this
                if (animateGifs) drawable.startIfNeeded()
            }
        }
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
