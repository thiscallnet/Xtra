package com.github.andreyasadchy.xtra.ui.chat.v2.recommendations

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.widget.TextView
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetState
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatImageHandle
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogEmote
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import kotlin.math.max
import kotlin.math.roundToInt

internal data class ChatInputEmoteToken(
    val text: String,
    val start: Int,
    val end: Int,
)

internal fun chatInputEmoteTokens(text: CharSequence): List<ChatInputEmoteToken> {
    val value = text.toString()
    if (value.isEmpty()) return emptyList()

    val tokens = ArrayList<ChatInputEmoteToken>()
    var index = 0
    while (index < value.length) {
        while (index < value.length && value[index].isWhitespace()) index++
        if (index == value.length) break
        val start = index
        while (index < value.length && !value[index].isWhitespace()) index++
        tokens += ChatInputEmoteToken(
            text = value.substring(start, index),
            start = start,
            end = index,
        )
    }
    return tokens
}

/** Replaces recognized sendable tokens with images while retaining their text underneath. */
internal class ChatInputEmoteRenderer(
    private val textView: TextView,
    private val assets: ChatAssetRepository,
    private val animateGifs: Boolean,
) {
    private var emotesByName = emptyMap<String, ChatCatalogEmote>()
    private val observers = LinkedHashMap<ChatAssetKey, () -> Unit>()

    fun setCatalog(catalog: EmoteRecommendationCatalog?) {
        val next = catalog?.emotes.orEmpty().associateBy(ChatCatalogEmote::name)
        if (next == emotesByName) return
        emotesByName = next
        render()
    }

    fun render() {
        val editable = textView.text as? Editable ?: return
        val existingSpans = editable
            .getSpans(0, editable.length, ChatInputEmoteSpan::class.java)
            .associateBy { span ->
                SpanPlacement(
                    start = editable.getSpanStart(span),
                    end = editable.getSpanEnd(span),
                )
            }
            .toMutableMap()
        val observedKeys = LinkedHashSet<ChatAssetKey>()

        chatInputEmoteTokens(editable).forEach { token ->
            val emote = emotesByName[token.text] ?: return@forEach
            val placement = SpanPlacement(token.start, token.end)
            val existing = existingSpans.remove(placement)
            if (existing == null || !existing.matches(emote.asset, token.text)) {
                existing?.let { span ->
                    span.dispose()
                    editable.removeSpan(span)
                }
                editable.setSpan(
                    ChatInputEmoteSpan(
                        textView = textView,
                        assets = assets,
                        spec = emote.asset,
                        fallback = token.text,
                        animateGifs = animateGifs,
                    ),
                    token.start,
                    token.end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            observedKeys += emote.asset.key
        }

        existingSpans.values.forEach { span ->
            span.dispose()
            editable.removeSpan(span)
        }

        observers.keys.toList().filterNot(observedKeys::contains).forEach { key ->
            observers.remove(key)?.let { callback -> assets.removeObserver(key, callback) }
        }
        observedKeys.forEach(::observe)
    }

    fun dispose() {
        detachObservers()
        val editable = textView.text as? Editable ?: return
        editable.getSpans(0, editable.length, ChatInputEmoteSpan::class.java).forEach { span ->
            span.dispose()
            editable.removeSpan(span)
        }
    }

    private fun observe(key: ChatAssetKey) {
        if (observers.containsKey(key)) return
        lateinit var callback: () -> Unit
        callback = {
            textView.post {
                if (observers[key] !== callback) return@post
                textView.requestLayout()
                textView.invalidate()
            }
        }
        observers[key] = callback
        assets.observe(key, callback)
    }

    private fun detachObservers() {
        observers.forEach { (key, callback) -> assets.removeObserver(key, callback) }
        observers.clear()
    }

    private data class SpanPlacement(
        val start: Int,
        val end: Int,
    )
}

private class ChatInputEmoteSpan(
    private val textView: TextView,
    private val assets: ChatAssetRepository,
    private val spec: ChatAssetSpec,
    private val fallback: String,
    private val animateGifs: Boolean,
) : ReplacementSpan() {
    private var resolvedHandle: ChatImageHandle? = null
    private var drawable: Drawable? = null
    private var width = 1
    private var height = 1

    fun matches(nextSpec: ChatAssetSpec, nextFallback: String): Boolean =
        spec == nextSpec && fallback == nextFallback

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        updateSize(paint)
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
        return if (drawable() == null) {
            max(width, paint.measureText(text, start, end).roundToInt())
        } else {
            width
        }
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val image = drawable()
        if (image == null) {
            val label = text.subSequence(start, end).toString()
            val labelWidth = paint.measureText(label)
            canvas.drawText(label, x + (width - labelWidth).coerceAtLeast(0f) / 2f, y.toFloat(), paint)
            return
        }

        image.setBounds(0, 0, width, height)
        val centerY = (top + bottom) / 2f
        val saveCount = canvas.save()
        canvas.translate(x, centerY - height / 2f)
        image.draw(canvas)
        canvas.restoreToCount(saveCount)
    }

    fun dispose() {
        drawable?.let { image ->
            image.callback = null
            (image as? Animatable)?.let { animatable ->
                if (animatable.isRunning) animatable.stop()
            }
        }
        drawable = null
        resolvedHandle = null
    }

    private fun updateSize(paint: Paint) {
        height = (paint.fontMetricsInt.descent - paint.fontMetricsInt.ascent).coerceAtLeast(1)
        width = (spec.sourceWidth.toLong() * height / spec.sourceHeight.coerceAtLeast(1))
            .toInt()
            .coerceAtLeast(1)
    }

    private fun drawable(): Drawable? {
        val handle = (assets.peek(spec.key) as? ChatAssetState.Ready)?.image ?: return null
        if (handle === resolvedHandle) return drawable

        drawable?.let { image ->
            image.callback = null
            (image as? Animatable)?.let { animatable ->
                if (animatable.isRunning) animatable.stop()
            }
        }
        resolvedHandle = handle
        drawable = handle.newDrawable()?.also { image ->
            image.callback = textView
            if (animateGifs) (image as? Animatable)?.start()
        }
        if (drawable == null) {
            resolvedHandle = null
            assets.retryIfDrawableUnavailable(spec.key)
        }
        return drawable
    }
}
