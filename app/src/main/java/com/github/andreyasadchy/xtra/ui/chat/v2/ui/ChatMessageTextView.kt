package com.github.andreyasadchy.xtra.ui.chat.v2.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.text.util.Linkify
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.graphics.Typeface
import android.view.Gravity
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView
import android.util.TypedValue
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.AppCompatTextView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
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
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreviewLink
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreview
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreviewRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.formatClipDuration
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.parseClipTimestamp
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatNamePaint
import com.github.andreyasadchy.xtra.ui.view.CenteredImageSpan
import kotlin.math.min
import kotlin.math.roundToInt

open class ChatMessageTextView private constructor(
    context: Context,
    private val assets: ChatAssetRepository,
    private val clipPreviews: ChatClipPreviewRepository?,
    attrs: AttributeSet?,
) : AppCompatTextView(context, attrs) {
    constructor(context: Context, assets: ChatAssetRepository) : this(
        context,
        assets,
        (context.applicationContext as? XtraApp)?.xtraModule?.chatClipPreviewRepository,
        null,
    )

    constructor(context: Context, assets: ChatAssetRepository, clipPreviews: ChatClipPreviewRepository?) : this(
        context,
        assets,
        clipPreviews,
        null,
    )

    constructor(context: Context, attrs: AttributeSet?) : this(
        context,
        (context.applicationContext as? XtraApp)?.xtraModule?.chatAssetRepository
            ?: error("ChatMessageTextView requires an Xtra application context"),
        (context.applicationContext as? XtraApp)?.xtraModule?.chatClipPreviewRepository,
        attrs,
    )

    private val initialPaddingStart = paddingStart
    private val initialPaddingTop = paddingTop
    private val initialPaddingEnd = paddingEnd
    private val initialPaddingBottom = paddingBottom
    private var keys = emptySet<ChatAssetKey>()
    private val drawables = HashMap<ChatAssetKey, Drawable>()
    private val drawableHandles = HashMap<ChatAssetKey, Any>()
    private val assetInvalidator: () -> Unit = {
        post {
            requestLayout()
            postInvalidateOnAnimation()
        }
    }
    private val clipMetadataInvalidator: () -> Unit = {
        post {
            refreshClipPreviewAssets()
            requestLayout()
            postInvalidateOnAnimation()
        }
    }
    private val clipThumbnailInvalidator: () -> Unit = { postInvalidateOnAnimation() }
    private var renderingActive = true
    private var animateGifs = true
    private var boundMessageId: ChatMessageId? = null
    private var longPressConsumed = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var longPressRunnable: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var onMessageLongClick: ((ChatMessageId) -> Unit)? = null
    private var onMessageClick: ((ChatMessageId) -> Unit)? = null
    private var onEmoteClick: ((ChatEmoteInteraction) -> Unit)? = null
    private var onGifClick: ((ChatGifInteraction) -> Unit)? = null
    private var onClipPreviewClick: ((String) -> Unit)? = null
    private var boundRow: ChatRowUiModel? = null
    private var bindingRow = false
    private var touchMoved = false
    private var clipPreviewSlugs = emptySet<String>()
    private var clipPreviewAssetKeys = emptySet<ChatAssetKey>()

    fun setInteractionCallbacks(
        onMessageLongClick: ((ChatMessageId) -> Unit)?,
        onEmoteClick: ((ChatEmoteInteraction) -> Unit)?,
        onGifClick: ((ChatGifInteraction) -> Unit)? = null,
    ) {
        this.onMessageLongClick = onMessageLongClick
        this.onEmoteClick = onEmoteClick
        this.onGifClick = onGifClick
    }

    fun setMessageClickCallback(callback: ((ChatMessageId) -> Unit)?) {
        onMessageClick = callback
        isClickable = onMessageClick != null
    }

    fun setClipPreviewClickCallback(callback: ((String) -> Unit)?) {
        onClipPreviewClick = callback
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
        boundRow = row
        bindingRow = true
        try {
            bindRow(row)
        } finally {
            bindingRow = false
        }
    }

    private fun bindRow(row: ChatRowUiModel) {
        boundMessageId = row.id
        longPressConsumed = false
        longPressRunnable?.let(mainHandler::removeCallbacks)
        longPressRunnable = null
        isLongClickable = onMessageLongClick != null
        keys.forEach { assets.removeObserver(it, assetInvalidator) }
        clipPreviewSlugs.forEach { slug -> clipPreviews?.removeObserver(slug, clipMetadataInvalidator) }
        clipPreviewAssetKeys.forEach { assets.removeObserver(it, clipThumbnailInvalidator) }
        clipPreviewSlugs = emptySet()
        clipPreviewAssetKeys = emptySet()
        drawables.values.forEach { it.stopIfNeeded(); it.callback = null }
        drawables.clear()
        drawableHandles.clear()
        keys = row.pieces.flatMap { piece ->
            buildList {
                val spec = when (piece) {
                    is ChatPiece.Badge -> piece.asset
                    is ChatPiece.RewardIcon -> piece.asset
                    is ChatPiece.Emote -> piece.asset
                    is ChatPiece.Cheermote -> piece.asset
                    is ChatPiece.Gif -> piece.asset
                    else -> null
                }
                spec?.let { addAll(it.allKeys()) }
                if (piece is ChatPiece.Username) {
                    piece.paint?.imageUrl?.takeIf { it.isNotBlank() }?.let { add(ChatAssetKey(it)) }
                }
            }
        }.toSet()
        keys.forEach { assets.observe(it, assetInvalidator) }
        clipPreviewSlugs = row.clipPreviews.map { it.slug }.toSet()
        clipPreviewSlugs.forEach { slug -> clipPreviews?.observe(slug, clipMetadataInvalidator) }
        refreshClipPreviewAssets()
        when (row.backgroundStyle) {
            ChatRowBackground.NORMAL -> setBackgroundColor(row.background)
            ChatRowBackground.PERSONAL_HIGHLIGHT -> setBackgroundColor(row.background)
            ChatRowBackground.HIGHLIGHT -> setBackgroundResource(R.drawable.bg_chat_highlight)
            ChatRowBackground.FIRST_CHATTER -> setBackgroundResource(R.drawable.bg_chat_first_chatter)
            ChatRowBackground.FIRST_CHATTER_TINT -> setBackgroundColor(ContextCompat.getColor(context, R.color.chatMessageFirst))
            ChatRowBackground.SUBSCRIPTION -> setBackgroundResource(R.drawable.bg_chat_subscription)
            ChatRowBackground.WATCH_STREAK -> setBackgroundResource(R.drawable.bg_chat_watch_streak)
            ChatRowBackground.REWARD -> setBackgroundColor(ContextCompat.getColor(context, R.color.chatMessageReward))
            ChatRowBackground.NOTICE -> setBackgroundColor(ContextCompat.getColor(context, R.color.chatMessageNotice))
        }
        val density = resources.displayMetrics.density
        val extraStartPadding = when (row.backgroundStyle) {
            ChatRowBackground.WATCH_STREAK -> (30 * density).roundToInt()
            ChatRowBackground.FIRST_CHATTER,
            ChatRowBackground.SUBSCRIPTION,
            -> (6 * density).roundToInt()
            else -> 0
        }
        val extraVerticalPadding = when (row.backgroundStyle) {
            ChatRowBackground.FIRST_CHATTER,
            ChatRowBackground.SUBSCRIPTION,
            -> (4 * density).roundToInt()
            else -> 0
        }
        setPaddingRelative(
            initialPaddingStart + extraStartPadding,
            initialPaddingTop + extraVerticalPadding,
            initialPaddingEnd,
            initialPaddingBottom + extraVerticalPadding,
        )
        gravity = Gravity.TOP or Gravity.START
        movementMethod = LinkMovementMethod.getInstance()
        linksClickable = true
        highlightColor = Color.TRANSPARENT
        val output = SpannableStringBuilder()
        row.pieces.forEach { piece ->
            when (piece) {
                is ChatPiece.Text -> appendStyled(output, piece.value, piece.color, piece.bold)
                is ChatPiece.Reply -> appendReply(output, piece, row.timestampText)
                is ChatPiece.Username -> appendUsername(output, piece)
                is ChatPiece.Source -> appendStyled(output, "[${piece.value}] ", piece.color)
                is ChatPiece.Icon -> appendIcon(output, piece)
                is ChatPiece.Mention -> appendStyled(output, piece.value, null)
                is ChatPiece.Badge -> appendAsset(output, piece.asset, piece.interaction?.name ?: "badge", piece.interaction)
                is ChatPiece.RewardIcon -> appendAsset(output, piece.asset, piece.fallback)
                is ChatPiece.Emote -> appendAsset(output, piece.asset, piece.fallback, piece.interaction)
                is ChatPiece.Gif -> appendAsset(output, piece.asset, piece.fallback, gifInteraction = piece.interaction)
                is ChatPiece.Cheermote -> {
                    appendAsset(output, piece.asset, piece.bits.toString(), piece.interaction)
                    appendStyled(output, " ${piece.bits}", piece.color)
                }
            }
        }
        row.timestampText?.let { timestamp ->
            output.insert(0, "$timestamp ")
            output.setSpan(
                ForegroundColorSpan(row.timestampColor),
                0,
                timestamp.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        // URL spans must be added after all pieces are assembled so offsets remain correct
        // around badges, emotes, GIFs, and timestamp text.
        Linkify.addLinks(output, Linkify.WEB_URLS)
        redirectClipUrlSpans(output)
        if (row.isAction) output.setSpan(StyleSpan(android.graphics.Typeface.ITALIC), 0, output.length, 0)
        contentDescription = row.accessibilityText
        text = output
    }

    private fun refreshClipPreviewAssets() {
        val nextKeys = boundRow?.clipPreviews.orEmpty().mapNotNull { link ->
            clipPreviews?.peek(link.slug)?.thumbnailUrl?.takeIf { it.isNotBlank() }?.let(::ChatAssetKey)
        }.toSet()
        clipPreviewAssetKeys.filterNot(nextKeys::contains).forEach { assets.removeObserver(it, clipThumbnailInvalidator) }
        if (isAttachedToWindow && renderingActive) {
            nextKeys.filterNot(clipPreviewAssetKeys::contains).forEach { assets.observe(it, clipThumbnailInvalidator) }
        }
        clipPreviewAssetKeys = nextKeys
    }

    private fun resolvedClipPreviews(): List<Pair<ChatClipPreviewLink, ChatClipPreview>> =
        boundRow?.clipPreviews.orEmpty().mapNotNull { link ->
            clipPreviews?.peek(link.slug)?.let { preview -> link to preview }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val count = resolvedClipPreviews().size
        if (count == 0) return
        val extra = count * clipPreviewBlockHeight() + count * clipPreviewGap()
        setMeasuredDimension(measuredWidth, measuredHeight + extra)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val previews = resolvedClipPreviews()
        if (previews.isEmpty()) return
        val left = totalPaddingLeft.toFloat()
        val right = (width - totalPaddingRight).toFloat()
        var top = (paddingTop + layout?.height.orZero() + clipPreviewGap()).toFloat()
        previews.forEach { (link, preview) ->
            drawClipPreview(canvas, left, top, right, link, preview)
            top += clipPreviewBlockHeight() + clipPreviewGap()
        }
    }

    private fun drawClipPreview(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        link: ChatClipPreviewLink,
        preview: ChatClipPreview,
    ) {
        val density = resources.displayMetrics.density
        val height = clipPreviewCardHeight().toFloat()
        val radius = (3 * density)
        val card = RectF(left, top, right, top + height)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = com.google.android.material.color.MaterialColors.getColor(
                this@ChatMessageTextView,
                com.google.android.material.R.attr.colorSurfaceContainerHigh,
            )
        }
        canvas.drawRoundRect(card, radius, radius, fill)
        fill.color = ContextCompat.getColor(context, R.color.chatMessageSpecialAccent)
        canvas.drawRoundRect(RectF(right - 4 * density, top, right, top + height), radius, radius, fill)

        val imageUrl = preview.thumbnailUrl?.takeIf { it.isNotBlank() }
        val thumbLeft = left + 6 * density
        val thumbTop = top + 6 * density
        val thumbRight = thumbLeft + 72 * density
        val thumbBottom = top + height - 6 * density
        if (imageUrl != null) {
            val spec = com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec(
                ChatAssetKey(imageUrl), 16, 9, (thumbBottom - thumbTop).toInt(),
            )
            drawableFor(ChatAssetKey(imageUrl), spec)?.let { drawable ->
                drawable.setBounds(thumbLeft.toInt(), thumbTop.toInt(), thumbRight.toInt(), thumbBottom.toInt())
                drawable.draw(canvas)
            }
        }

        val textLeft = thumbRight + 6 * density
        val textRight = right - 9 * density
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = com.google.android.material.color.MaterialColors.getColor(
                this@ChatMessageTextView,
                com.google.android.material.R.attr.colorOnSurface,
            )
            textSize = paint.textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val secondaryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = com.google.android.material.color.MaterialColors.getColor(
                this@ChatMessageTextView,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
            )
            textSize = paint.textSize
        }
        drawClipLine(canvas, preview.title?.takeIf { it.isNotBlank() } ?: link.slug, titlePaint, textLeft, textRight, top + 18 * density)
        drawClipLine(canvas, clipSubtitle(preview), secondaryPaint, textLeft, textRight, top + 36 * density)
        drawClipLine(canvas, clipAttribution(preview), secondaryPaint, textLeft, textRight, top + 54 * density)
    }

    private fun clipSubtitle(preview: ChatClipPreview): String {
        val broadcaster = preview.broadcasterName?.takeIf { it.isNotBlank() } ?: "Twitch"
        val base = preview.gameName?.takeIf { it.isNotBlank() }?.let { game ->
            context.getString(R.string.chat_clip_playing, broadcaster, game)
        } ?: broadcaster
        return formatClipDuration(preview.durationSeconds)?.let { "$base — $it" } ?: base
    }

    private fun clipAttribution(preview: ChatClipPreview): String {
        val creator = preview.creatorName?.takeIf { it.isNotBlank() } ?: "unknown"
        val base = context.getString(R.string.chat_clip_clipped_by, creator)
        return clipRelativeTime(preview.createdAt)?.let { "$base — $it" } ?: base
    }

    private fun clipRelativeTime(createdAt: String?): String? {
        val epochMs = parseClipTimestamp(createdAt) ?: return null
        if (epochMs > System.currentTimeMillis()) return null
        return DateUtils.getRelativeTimeSpanString(epochMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }

    private fun drawClipLine(canvas: Canvas, value: String, paint: TextPaint, left: Float, right: Float, baseline: Float) {
        canvas.drawText(TextUtils.ellipsize(value, paint, (right - left).coerceAtLeast(0f), TextUtils.TruncateAt.END).toString(), left, baseline, paint)
    }

    private fun clipPreviewCardHeight() = (68 * resources.displayMetrics.density).roundToInt()
    private fun clipPreviewGap() = (4 * resources.displayMetrics.density).roundToInt()
    private fun clipPreviewBlockHeight() = clipPreviewCardHeight()

    private fun hasClipPreviewAt(event: MotionEvent): String? {
        val previews = resolvedClipPreviews()
        if (previews.isEmpty()) return null
        val start = paddingTop + layout?.height.orZero() + clipPreviewGap()
        val index = ((event.y - start) / (clipPreviewBlockHeight() + clipPreviewGap())).toInt()
        if (index !in previews.indices) return null
        val top = start + index * (clipPreviewBlockHeight() + clipPreviewGap())
        return previews[index].first.url.takeIf { event.y >= top && event.y <= top + clipPreviewBlockHeight() && event.x >= totalPaddingLeft && event.x <= width - totalPaddingRight }
    }

    /**
     * Twitch clip links open inside Xtra (player with chat and controls) instead of a
     * browser. The embedded preview card below uses the same entry point.
     */
    private fun redirectClipUrlSpans(output: SpannableStringBuilder) {
        output.getSpans(0, output.length, URLSpan::class.java).forEach { span ->
            val url = span.url ?: return@forEach
            if (!ChatClipPreviewLink.isClipUrl(url)) return@forEach
            val start = output.getSpanStart(span)
            val end = output.getSpanEnd(span)
            val flags = output.getSpanFlags(span)
            output.removeSpan(span)
            output.setSpan(object : ClickableSpan() {
                override fun onClick(widget: android.view.View) {
                    openClipUrl(url)
                }
            }, start, end, flags)
        }
    }

    private fun openClipUrl(url: String) {
        val normalizedUrl = if (url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
        ) url else "https://$url"
        (onClipPreviewClick ?: ::openTwitchUrlInApp)(normalizedUrl)
    }

    private fun openTwitchUrlInApp(url: String) {
        // MainActivity declares a browsable twitch.tv filter and loads the clip in-app,
        // so pin the intent to our own package with a browser fallback.
        val inApp = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(context.packageName)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(inApp) }.isSuccess) return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw && !bindingRow && boundRow?.pieces?.any { it is ChatPiece.Reply } == true) {
            boundRow?.let(::bind)
        }
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
                touchMoved = false
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
                    touchMoved = true
                    longPressRunnable?.let(mainHandler::removeCallbacks)
                    longPressRunnable = null
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                touchMoved = true
                longPressRunnable?.let(mainHandler::removeCallbacks)
                longPressRunnable = null
            }

            MotionEvent.ACTION_UP -> {
                hasClipPreviewAt(event)?.let { url ->
                    if (!touchMoved && !longPressConsumed) {
                        openClipUrl(url)
                        longPressRunnable?.let(mainHandler::removeCallbacks)
                        longPressRunnable = null
                        return true
                    }
                }
                val messageId = boundMessageId
                val shouldOpenProfile = messageId != null &&
                    onMessageClick != null &&
                    !touchMoved &&
                    !longPressConsumed &&
                    !hasClickableSpanAt(event)
                longPressRunnable?.let(mainHandler::removeCallbacks)
                longPressRunnable = null
                if (longPressConsumed) {
                    longPressConsumed = false
                    return true
                }
                if (shouldOpenProfile) {
                    onMessageClick?.invoke(messageId)
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

    private fun appendUsername(output: SpannableStringBuilder, piece: ChatPiece.Username) {
        val start = output.length
        appendStyled(output, piece.value + piece.separator, piece.color, piece.bold)
        piece.paint?.let { paintSpec ->
            output.setSpan(ChatNamePaintSpan(paintSpec, assets), start, start + piece.value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun appendReply(output: SpannableStringBuilder, piece: ChatPiece.Reply, timestampText: String?) {
        val availableWidth = if (width > 0) {
            val lineStart = output.lastIndexOf('\n') + 1
            val prefix = output.substring(lineStart)
            val iconCorrection = (18 * resources.displayMetrics.density).roundToInt() - paint.measureText(".")
            val timestampWidth = timestampText?.let { paint.measureText("$it ") } ?: 0f
            (width - totalPaddingLeft - totalPaddingRight - timestampWidth - paint.measureText(prefix) - iconCorrection)
                .coerceAtLeast(paint.measureText("…"))
        } else {
            Float.POSITIVE_INFINITY
        }
        val value = if (availableWidth.isFinite()) {
            TextUtils.ellipsize(piece.value, paint, availableWidth, TextUtils.TruncateAt.END).toString()
        } else {
            piece.value
        }
        appendStyled(output, value, piece.color)
    }

    private fun hasClickableSpanAt(event: MotionEvent): Boolean {
        val content = text as? Spanned ?: return false
        val textLayout = layout ?: return false
        if (content.isEmpty()) return false
        val x = (event.x - totalPaddingLeft + scrollX).coerceAtLeast(0f)
        val y = (event.y - totalPaddingTop + scrollY).coerceAtLeast(0f)
        val line = textLayout.getLineForVertical(y.toInt())
        val offset = textLayout.getOffsetForHorizontal(line, x)
        val end = (offset + 1).coerceAtMost(content.length)
        return content.getSpans(offset.coerceAtMost(content.length - 1), end, ClickableSpan::class.java).isNotEmpty()
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
        output.setSpan(
            ChatAssetSpan(
                spec,
                fallback,
                { drawableLayersFor(spec) },
                { spec.flatten().all { assets.peek(it.key) is ChatAssetState.Ready } },
            ),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
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
        keys.forEach { assets.removeObserver(it, assetInvalidator) }
        clipPreviewSlugs.forEach { slug -> clipPreviews?.removeObserver(slug, clipMetadataInvalidator) }
        clipPreviewAssetKeys.forEach { assets.removeObserver(it, clipThumbnailInvalidator) }
        drawables.values.forEach { it.stopIfNeeded(); it.callback = null }
        drawables.clear()
        drawableHandles.clear()
        keys = emptySet()
        clipPreviewSlugs = emptySet()
        clipPreviewAssetKeys = emptySet()
        text = null
    }

    /** Stops asset callbacks while the containing chat surface is hidden. */
    fun setRenderingActive(active: Boolean) {
        if (renderingActive == active) return
        renderingActive = active
        if (active) {
            if (isAttachedToWindow) keys.forEach { assets.observe(it, assetInvalidator) }
            if (isAttachedToWindow) clipPreviewSlugs.forEach { slug -> clipPreviews?.observe(slug, clipMetadataInvalidator) }
            if (isAttachedToWindow) clipPreviewAssetKeys.forEach { assets.observe(it, clipThumbnailInvalidator) }
            if (isAttachedToWindow) {
                drawables.values.forEach { drawable ->
                    drawable.callback = this
                    if (animateGifs) drawable.startIfNeeded()
                }
            }
        } else {
            keys.forEach { assets.removeObserver(it, assetInvalidator) }
            clipPreviewSlugs.forEach { slug -> clipPreviews?.removeObserver(slug, clipMetadataInvalidator) }
            clipPreviewAssetKeys.forEach { assets.removeObserver(it, clipThumbnailInvalidator) }
            drawables.values.forEach { drawable -> drawable.stopIfNeeded(); drawable.callback = null }
        }
    }

    override fun onDetachedFromWindow() {
        longPressRunnable?.let(mainHandler::removeCallbacks)
        longPressRunnable = null
        keys.forEach { assets.removeObserver(it, assetInvalidator) }
        clipPreviewSlugs.forEach { slug -> clipPreviews?.removeObserver(slug, clipMetadataInvalidator) }
        clipPreviewAssetKeys.forEach { assets.removeObserver(it, clipThumbnailInvalidator) }
        drawables.values.forEach { it.stopIfNeeded(); it.callback = null }
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (renderingActive) {
            keys.forEach { assets.observe(it, assetInvalidator) }
            clipPreviewSlugs.forEach { slug -> clipPreviews?.observe(slug, clipMetadataInvalidator) }
            clipPreviewAssetKeys.forEach { assets.observe(it, clipThumbnailInvalidator) }
            drawables.values.forEach { drawable ->
                drawable.callback = this
                if (animateGifs) drawable.startIfNeeded()
            }
        }
    }

    override fun verifyDrawable(who: Drawable): Boolean =
        super.verifyDrawable(who) || drawables.values.any { it === who }
}

private fun Int?.orZero(): Int = this ?: 0

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

private class ChatNamePaintSpan(
    private val paintSpec: ChatNamePaint,
    private val assets: ChatAssetRepository,
) : CharacterStyle() {
    private var imageHandle: Any? = null
    private var imageShader: Shader? = null

    override fun updateDrawState(textPaint: TextPaint) {
        textPaint.clearShadowLayer()
        val imageUrl = paintSpec.imageUrl?.takeIf { it.isNotBlank() }
        if (imageUrl != null) {
            val handle = (assets.peek(ChatAssetKey(imageUrl)) as? ChatAssetState.Ready)?.image
            if (handle !== imageHandle) {
                imageHandle = handle
                imageShader = (handle?.newDrawable() as? BitmapDrawable)?.bitmap?.let { bitmap ->
                    android.graphics.BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                }
            }
            imageShader?.let {
                textPaint.shader = it
                applyShadow(textPaint)
                return
            }
        }
        textPaint.shader = when {
            paintSpec.colors.size >= 2 -> {
                val shader = if (paintSpec.type == "RADIAL_GRADIENT") {
                    android.graphics.RadialGradient(
                        140f,
                        14f,
                        140f,
                        paintSpec.colors.toIntArray(),
                        paintSpec.colorPositions.takeIf { it.size == paintSpec.colors.size }?.toFloatArray(),
                        if (paintSpec.repeat) Shader.TileMode.REPEAT else Shader.TileMode.CLAMP,
                    )
                } else {
                    LinearGradient(
                        0f,
                        0f,
                        280f,
                        0f,
                        paintSpec.colors.toIntArray(),
                        paintSpec.colorPositions.takeIf { it.size == paintSpec.colors.size }?.toFloatArray(),
                        if (paintSpec.repeat) Shader.TileMode.REPEAT else Shader.TileMode.CLAMP,
                    ).also { value ->
                        paintSpec.angle?.let { angle ->
                            value.setLocalMatrix(android.graphics.Matrix().apply {
                                setRotate((angle - 90).toFloat(), 140f, 14f)
                            })
                        }
                    }
                }
                shader
            }
            else -> null
        }
        if (paintSpec.colors.size == 1) textPaint.color = paintSpec.colors.single()
        applyShadow(textPaint)
    }

    private fun applyShadow(textPaint: TextPaint) {
        paintSpec.shadows.lastOrNull()?.let { shadow ->
            textPaint.setShadowLayer(shadow.radius, shadow.xOffset, shadow.yOffset, shadow.color)
        }
    }
}

private class ChatAssetSpan(
    private val spec: ChatAssetSpec,
    private val fallback: String,
    private val drawables: () -> List<DrawableLayer>?,
    private val isReady: () -> Boolean,
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
        return if (isReady()) spec.compositionWidth else maxOf(spec.compositionWidth, fallbackWidth(paint))
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val oldColor = paint.color
        val oldStyle = paint.style
        val oldTextSize = paint.textSize
        val oldTextAlign = paint.textAlign
        val centerY = (top + bottom) / 2f
        val label = fallback.trim().ifEmpty { "?" }
        val fallbackPaint = fallbackPaint(paint)
        val images = drawables()
        val reservedWidth = if (images != null) {
            spec.compositionWidth
        } else {
            maxOf(spec.compositionWidth, fallbackPaint.measureText(label).roundToInt())
        }
        val rect = RectF(x, centerY - spec.compositionHeight / 2f, x + reservedWidth, centerY + spec.compositionHeight / 2f)
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
            // Keep the original token readable while the image is loading or retrying. The
            // Reserve enough room for the original token while the image is unavailable. A
            // fake pill suggests that an image exists when it does not and was especially
            // confusing for missing badge URLs.
            paint.color = oldColor
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = fallbackPaint.textSize
            val baseline = rect.centerY() - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(label, rect.centerX(), baseline, paint)
        }
        paint.color = oldColor
        paint.style = oldStyle
        paint.textSize = oldTextSize
        paint.textAlign = oldTextAlign
    }

    private fun fallbackWidth(paint: Paint): Int = fallbackPaint(paint)
        .measureText(fallback.trim().ifEmpty { "?" })
        .roundToInt()

    private fun fallbackPaint(paint: Paint): Paint = Paint(paint).apply {
        textSize = min(
            paint.textSize.takeIf { it > 0f } ?: Float.MAX_VALUE,
            (spec.compositionHeight * 0.5f).coerceAtLeast(8f),
        )
    }
}
