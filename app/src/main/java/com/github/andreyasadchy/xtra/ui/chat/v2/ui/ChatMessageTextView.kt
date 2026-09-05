package com.github.andreyasadchy.xtra.ui.chat.v2.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
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
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.text.style.StrikethroughSpan
import android.text.util.Linkify
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView
import android.util.TypedValue
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.appcompat.widget.AppCompatTextView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetState
import com.github.andreyasadchy.xtra.util.ChatRenderDiagnostics
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEmoteInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatGifInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPiece
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowBackground
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatEventVisualStyle
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatEventVisualTokens
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatEventKind
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreviewLink
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreview
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreviewRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreviewState
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.formatClipDuration
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.parseClipTimestamp
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatNamePaint
import com.github.andreyasadchy.xtra.ui.view.CenteredImageSpan
import kotlin.math.min
import kotlin.math.roundToInt

private const val REPLY_TEXT_SCALE = 0.82f
private const val REPLY_ICON_SIZE_DP = 15

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
    private val initialLineSpacingExtra = lineSpacingExtra
    private val initialLineSpacingMultiplier = lineSpacingMultiplier
    private var keys = emptySet<ChatAssetKey>()
    private val drawables = HashMap<ChatAssetKey, Drawable>()
    private val drawableHandles = HashMap<ChatAssetKey, Any>()
    private val assetInvalidator: () -> Unit = {
        post {
            latchTerminalAssetFailures()
            requestLayout()
            postInvalidateOnAnimation()
            maybeRevealInitialAssetFrame()
        }
    }
    private val clipMetadataInvalidator: () -> Unit = {
        post {
            refreshClipPreviewAssets()
            requestLayout()
            postInvalidateOnAnimation()
            maybeRevealInitialAssetFrame()
        }
    }
    private val clipThumbnailInvalidator: () -> Unit = {
        post {
            latchTerminalAssetFailures()
            requestLayout()
            postInvalidateOnAnimation()
            maybeRevealInitialAssetFrame()
        }
    }
    private var renderingActive = true
    private var animateGifs = true
    private var windowAttached = false
    private var animatedAssetKeys = emptySet<ChatAssetKey>()
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
    private var awaitingInitialAssetFrame = false
    private var revealOnPreDrawPosted = false
    private var initialAssetSpecs = emptyList<ChatAssetSpec>()
    private var initialDirectAssetKeys = emptySet<ChatAssetKey>()
    private val latchedFailedCompositionKeys = HashSet<String>()
    private val latchedFailedDirectKeys = HashSet<ChatAssetKey>()
    private val latchedFailedClipMetadataSlugs = HashSet<String>()

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
        updateDrawableAnimations()
    }

    fun bind(row: ChatRowUiModel) {
        boundRow = row
        bindingRow = true
        if (BuildConfig.PERF_DIAGNOSTICS) {
            ChatRenderDiagnostics.recordBind()
            Trace.beginSection("Xtra.ChatV2.bind")
        }
        try {
            bindRow(row)
        } finally {
            if (BuildConfig.PERF_DIAGNOSTICS) Trace.endSection()
            bindingRow = false
        }
    }

    private fun bindRow(row: ChatRowUiModel) {
        val sameRevealedMessage = boundMessageId == row.id && !awaitingInitialAssetFrame
        if (!sameRevealedMessage) {
            awaitingInitialAssetFrame = true
            alpha = 0f
            latchedFailedCompositionKeys.clear()
            latchedFailedDirectKeys.clear()
            latchedFailedClipMetadataSlugs.clear()
        }
        boundMessageId = row.id
        longPressConsumed = false
        longPressRunnable?.let(mainHandler::removeCallbacks)
        longPressRunnable = null
        isLongClickable = onMessageLongClick != null
        val oldKeys = keys
        val oldClipPreviewSlugs = clipPreviewSlugs
        drawables.values.forEach { it.stopIfNeeded(); it.callback = null }
        drawables.clear()
        drawableHandles.clear()
        val newKeys = row.pieces.flatMap { piece ->
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
        oldKeys.filterNot(newKeys::contains).forEach { assets.removeObserver(it, assetInvalidator) }
        newKeys.filterNot(oldKeys::contains).forEach { assets.observe(it, assetInvalidator) }
        keys = newKeys
        animatedAssetKeys = row.pieces.flatMap { piece ->
            val spec = when (piece) {
                is ChatPiece.Emote -> piece.asset.takeIf { piece.animated }
                is ChatPiece.Gif -> piece.asset
                is ChatPiece.Badge -> piece.asset.takeIf { piece.interaction?.animated == true }
                is ChatPiece.Cheermote -> piece.asset.takeIf { piece.interaction?.animated == true }
                else -> null
            }
            spec?.allKeys().orEmpty()
        }.toSet()
        initialAssetSpecs = row.pieces.mapNotNull { piece ->
            when (piece) {
                is ChatPiece.Badge -> piece.asset
                is ChatPiece.RewardIcon -> piece.asset
                is ChatPiece.Emote -> piece.asset
                is ChatPiece.Cheermote -> piece.asset
                is ChatPiece.Gif -> piece.asset
                else -> null
            }
        }
        initialDirectAssetKeys = row.pieces.mapNotNull { piece ->
            (piece as? ChatPiece.Username)?.paint?.imageUrl
                ?.takeIf { it.isNotBlank() }
                ?.let(::ChatAssetKey)
        }.toSet()
        val newClipPreviewSlugs = row.clipPreviews.map { it.slug }.toSet()
        oldClipPreviewSlugs.filterNot(newClipPreviewSlugs::contains).forEach { slug ->
            clipPreviews?.removeObserver(slug, clipMetadataInvalidator)
        }
        newClipPreviewSlugs.filterNot(oldClipPreviewSlugs::contains).forEach { slug ->
            clipPreviews?.observe(slug, clipMetadataInvalidator)
        }
        clipPreviewSlugs = newClipPreviewSlugs
        refreshClipPreviewAssets()
        val density = resources.displayMetrics.density
        val event = row.eventPresentation
        if (event != null) {
            val accentAttribute = if (event.visualStyle == ChatEventVisualStyle.STREAK) {
                R.attr.chatEventStreakAccentColor
            } else {
                R.attr.chatMessageSpecialAccentColor
            }
            val accentColor = com.google.android.material.color.MaterialColors.getColor(this, accentAttribute)
            val baseColor = row.background.takeIf { it != 0 } ?:
                com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
            val tintAlpha = if (event.kind == ChatEventKind.HIGHLIGHT) 0x2A else 0x18
            setBackground(
                ChatEventBackgroundDrawable(
                    surfaceColor = blendColors(baseColor, accentColor, tintAlpha),
                    accentColor = accentColor,
                    railWidthPx = (ChatEventVisualTokens.accentRailWidthDp * density).roundToInt(),
                ),
            )
            setPaddingRelative(
                initialPaddingStart + (ChatEventVisualTokens.contentStartInsetDp * density).roundToInt(),
                initialPaddingTop + (ChatEventVisualTokens.verticalPaddingDp * density).roundToInt(),
                initialPaddingEnd + (ChatEventVisualTokens.endPaddingDp * density).roundToInt(),
                initialPaddingBottom + (ChatEventVisualTokens.verticalPaddingDp * density).roundToInt(),
            )
            setLineSpacing(ChatEventVisualTokens.lineSpacingExtraDp * density, 1f)
        } else {
            when (row.backgroundStyle) {
                ChatRowBackground.NORMAL,
                ChatRowBackground.PERSONAL_HIGHLIGHT,
                ChatRowBackground.EVENT,
                -> setBackgroundColor(row.background)
                ChatRowBackground.FIRST_CHATTER_TINT -> setBackgroundColor(
                    com.google.android.material.color.MaterialColors.getColor(this, R.attr.chatMessageFirstColor),
                )
                ChatRowBackground.REWARD -> setBackgroundColor(
                    com.google.android.material.color.MaterialColors.getColor(this, R.attr.chatMessageRewardColor),
                )
                ChatRowBackground.NOTICE -> setBackgroundColor(
                    com.google.android.material.color.MaterialColors.getColor(this, R.attr.chatMessageNoticeColor),
                )
            }
            setPaddingRelative(initialPaddingStart, initialPaddingTop, initialPaddingEnd, initialPaddingBottom)
            setLineSpacing(initialLineSpacingExtra, initialLineSpacingMultiplier)
        }
        gravity = Gravity.TOP or Gravity.START
        movementMethod = LinkMovementMethod.getInstance()
        linksClickable = true
        highlightColor = Color.TRANSPARENT
        val output = SpannableStringBuilder()
        var moderationStart: Int? = null
        var moderationEnd: Int? = null
        row.pieces.forEachIndexed { index, piece ->
            val pieceStart = output.length
            when (piece) {
                is ChatPiece.Text -> appendStyled(output, piece.value, piece.color, piece.bold)
                is ChatPiece.Reply -> appendReply(output, piece, row.timestampText)
                is ChatPiece.Username -> appendUsername(output, piece)
                is ChatPiece.Source -> appendStyled(output, "[${piece.value}] ", piece.color)
                is ChatPiece.Icon -> appendIcon(output, piece)
                is ChatPiece.Mention -> appendStyled(output, piece.value, null)
                is ChatPiece.Badge -> appendAsset(
                    output,
                    piece.asset,
                    fallback = "",
                    fallbackMode = ChatAssetFallbackMode.NONE,
                    interaction = piece.interaction,
                )
                is ChatPiece.RewardIcon -> appendAsset(
                    output,
                    piece.asset,
                    piece.fallback,
                    fallbackMode = ChatAssetFallbackMode.NONE,
                )
                is ChatPiece.Emote -> appendAsset(
                    output,
                    piece.asset,
                    piece.fallback,
                    fallbackMode = ChatAssetFallbackMode.TEXT_ON_FAILURE,
                    interaction = piece.interaction,
                )
                is ChatPiece.Gif -> appendAsset(
                    output,
                    piece.asset,
                    piece.fallback,
                    fallbackMode = ChatAssetFallbackMode.TEXT_ON_FAILURE,
                    gifInteraction = piece.interaction,
                )
                is ChatPiece.Cheermote -> {
                    appendAsset(
                        output,
                        piece.asset,
                        piece.bits.toString(),
                        fallbackMode = ChatAssetFallbackMode.NONE,
                        interaction = piece.interaction,
                    )
                    appendStyled(output, " ${piece.bits}", piece.color)
                }
            }
            if (row.moderationPieceRange?.contains(index) == true) {
                moderationStart = moderationStart ?: pieceStart
                moderationEnd = output.length
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
        row.moderation?.let {
            val timestampOffset = row.timestampText?.let { it.length + 1 } ?: 0
            val start = moderationStart?.plus(timestampOffset)
            val end = moderationEnd?.plus(timestampOffset)
            if (start != null && end != null && end > start) {
                output.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                row.moderationColor?.let { color ->
                    output.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        if (row.isAction) output.setSpan(StyleSpan(android.graphics.Typeface.ITALIC), 0, output.length, 0)
        contentDescription = row.accessibilityText
        text = output
        maybeRevealInitialAssetFrame()
    }

    private fun maybeRevealInitialAssetFrame() {
        if (!awaitingInitialAssetFrame) return

        latchFailedClipMetadata()

        val hasPendingAsset = (keys + clipPreviewAssetKeys).any { key ->
            when (val state = assets.peek(key)) {
                ChatAssetState.Missing,
                ChatAssetState.Loading -> true

                is ChatAssetState.Ready -> false
                is ChatAssetState.Failed -> !state.isPresentationTerminal
            }
        }
        if (hasPendingAsset) return
        if (clipPreviewSlugs.any { slug ->
                when (clipPreviews?.peekState(slug)) {
                    ChatClipPreviewState.Missing,
                    ChatClipPreviewState.Loading -> true
                    is ChatClipPreviewState.Ready,
                    null -> false
                }
            }
        ) return

        if (!isAttachedToWindow || revealOnPreDrawPosted) return
        revealOnPreDrawPosted = true
        doOnPreDraw {
            revealOnPreDrawPosted = false
            revealInitialAssetFrameIfReady()
        }
    }

    private fun revealInitialAssetFrameIfReady() {
        if (!awaitingInitialAssetFrame) return
        latchFailedClipMetadata()
        val hasPendingAsset = (keys + clipPreviewAssetKeys).any { key ->
            when (val state = assets.peek(key)) {
                ChatAssetState.Missing,
                ChatAssetState.Loading -> true

                is ChatAssetState.Ready -> false
                is ChatAssetState.Failed -> !state.isPresentationTerminal
            }
        }
        if (hasPendingAsset || clipPreviewSlugs.any { slug ->
                when (clipPreviews?.peekState(slug)) {
                    ChatClipPreviewState.Missing,
                    ChatClipPreviewState.Loading -> true
                    is ChatClipPreviewState.Ready,
                    null -> false
                }
            }
        ) return

        latchTerminalAssetFailures()

        awaitingInitialAssetFrame = false
        alpha = 1f
    }

    private fun latchTerminalAssetFailures() {
        initialAssetSpecs
            .filter { assetRenderState(it) == ChatAssetRenderState.FAILED }
            .forEach { latchedFailedCompositionKeys += it.compositionKey }
        (initialDirectAssetKeys + clipPreviewAssetKeys).forEach { key ->
            val state = assets.peek(key)
            if (state is ChatAssetState.Failed && state.isPresentationTerminal) {
                latchedFailedDirectKeys += key
            }
        }
    }

    private fun latchFailedClipMetadata() {
        val failed = clipPreviewSlugs.filter { slug ->
            clipPreviews?.peekState(slug) == ChatClipPreviewState.Ready(null)
        }
        if (latchedFailedClipMetadataSlugs.addAll(failed)) requestLayout()
    }

    private fun refreshClipPreviewAssets() {
        val nextKeys = boundRow?.clipPreviews.orEmpty().mapNotNull { link ->
            clipPreviews?.peek(link.slug)?.thumbnailUrl?.takeIf { it.isNotBlank() }?.let(::ChatAssetKey)
        }.toSet()
        initialDirectAssetKeys = (initialDirectAssetKeys - clipPreviewAssetKeys) + nextKeys
        clipPreviewAssetKeys.filterNot(nextKeys::contains).forEach { assets.removeObserver(it, clipThumbnailInvalidator) }
        if (isAttachedToWindow && renderingActive) {
            nextKeys.filterNot(clipPreviewAssetKeys::contains).forEach { assets.observe(it, clipThumbnailInvalidator) }
        }
        clipPreviewAssetKeys = nextKeys
    }

    private fun resolvedClipPreviews(): List<Pair<ChatClipPreviewLink, ChatClipPreview>> =
        boundRow?.clipPreviews.orEmpty().mapNotNull { link ->
            if (link.slug in latchedFailedClipMetadataSlugs) return@mapNotNull null
            clipPreviews?.peek(link.slug)?.let { preview -> link to preview }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // Reserve parsed clip-card geometry before metadata arrives. The row remains hidden until
        // metadata and its thumbnail reach a terminal state, so metadata cannot reflow a visible row.
        val count = boundRow?.clipPreviews?.count { it.slug !in latchedFailedClipMetadataSlugs } ?: 0
        if (count == 0) return
        val extra = count * clipPreviewBlockHeight() + count * clipPreviewGap()
        setMeasuredDimension(measuredWidth, measuredHeight + extra)
    }

    override fun onDraw(canvas: Canvas) {
        if (BuildConfig.PERF_DIAGNOSTICS) {
            ChatRenderDiagnostics.recordDraw()
            Trace.beginSection("Xtra.ChatV2.onDraw")
        }
        try {
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
        } finally {
            if (BuildConfig.PERF_DIAGNOSTICS) Trace.endSection()
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
        fill.color = com.google.android.material.color.MaterialColors.getColor(this, R.attr.chatMessageSpecialAccentColor)
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
            val key = ChatAssetKey(imageUrl)
            if (!isDirectAssetFailureLatched(key)) drawableFor(key, spec)?.let { drawable ->
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
            output.setSpan(
                ChatNamePaintSpan(paintSpec, assets, ::isDirectAssetFailureLatched),
                start,
                start + piece.value.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun appendReply(output: SpannableStringBuilder, piece: ChatPiece.Reply, timestampText: String?) {
        val replyPaint = TextPaint(paint).apply {
            textSize *= REPLY_TEXT_SCALE
        }
        val replyBoldPaint = TextPaint(replyPaint).apply {
            typeface = Typeface.create(replyPaint.typeface, Typeface.BOLD)
        }
        val availableWidth = if (width > 0) {
            val lineStart = output.lastIndexOf('\n') + 1
            val prefix = output.substring(lineStart)
            val iconCorrection = (REPLY_ICON_SIZE_DP * resources.displayMetrics.density).roundToInt() - replyPaint.measureText(".")
            val timestampWidth = timestampText?.let { paint.measureText("$it ") } ?: 0f
            (width - totalPaddingLeft - totalPaddingRight - timestampWidth - replyPaint.measureText(prefix) - iconCorrection)
                .coerceAtLeast(replyPaint.measureText("…"))
        } else {
            Float.POSITIVE_INFINITY
        }
        val structured = piece.parentUser?.let { parentUser ->
            val userStart = piece.value.indexOf(parentUser)
            if (userStart < 0) {
                null
            } else {
                val userEnd = userStart + parentUser.length
                val body = piece.parentMessage.orEmpty()
                val bodyStart = if (body.isNotEmpty()) {
                    piece.value.lastIndexOf(body).takeIf { it >= userEnd } ?: piece.value.length
                } else {
                    piece.value.length
                }
                ReplyParts(
                    prefix = piece.value.substring(0, userStart),
                    user = parentUser,
                    separator = piece.value.substring(userEnd, bodyStart),
                    body = body.takeIf { bodyStart < piece.value.length }.orEmpty(),
                )
            }
        }
        val rendered = if (structured != null) {
            val fixed = structured.prefix + structured.user + structured.separator
            val fixedWidth = replyPaint.measureText(structured.prefix + structured.separator) +
                replyBoldPaint.measureText(structured.user)
            val bodyWidth = (availableWidth - fixedWidth).coerceAtLeast(
                replyPaint.measureText("…"),
            )
            fixed + if (bodyWidth.isFinite()) {
                TextUtils.ellipsize(
                    structured.body,
                    replyPaint,
                    bodyWidth,
                    TextUtils.TruncateAt.END,
                ).toString()
            } else {
                structured.body
            }
        } else if (availableWidth.isFinite()) {
            TextUtils.ellipsize(piece.value, replyPaint, availableWidth, TextUtils.TruncateAt.END).toString()
        } else {
            piece.value
        }

        val start = output.length
        appendStyled(output, rendered, piece.color)
        output.setSpan(
            RelativeSizeSpan(REPLY_TEXT_SCALE),
            start,
            output.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        structured?.let {
            val userStart = start + it.prefix.length
            output.setSpan(
                StyleSpan(Typeface.BOLD),
                userStart,
                (userStart + it.user.length).coerceAtMost(output.length),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private data class ReplyParts(
        val prefix: String,
        val user: String,
        val separator: String,
        val body: String,
    )

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
        fallbackMode: ChatAssetFallbackMode,
        interaction: ChatEmoteInteraction? = null,
        gifInteraction: ChatGifInteraction? = null,
    ) {
        val layerSpecs = spec.flatten()
        val compositionKey = spec.compositionKey
        val start = output.length
        output.append(" ")
        val end = output.length
        output.setSpan(
            ChatAssetSpan(
                spec,
                fallback,
                layerSpecs,
                { layerSpec -> drawableFor(layerSpec.key, layerSpec) },
                { assetRenderState(compositionKey, layerSpecs) },
                fallbackMode,
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
        updateAnimationState(key, drawable)
        return drawable
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
        animatedAssetKeys = emptySet()
        clipPreviewSlugs = emptySet()
        clipPreviewAssetKeys = emptySet()
        initialAssetSpecs = emptyList()
        initialDirectAssetKeys = emptySet()
        latchedFailedCompositionKeys.clear()
        latchedFailedDirectKeys.clear()
        latchedFailedClipMetadataSlugs.clear()
        text = null
        boundMessageId = null
        boundRow = null
        awaitingInitialAssetFrame = false
        revealOnPreDrawPosted = false
        alpha = 1f
    }

    private fun assetRenderState(spec: ChatAssetSpec): ChatAssetRenderState {
        val flattened = spec.flatten()
        return assetRenderState(spec.compositionKey, flattened)
    }

    private fun assetRenderState(
        compositionKey: String,
        flattened: List<ChatAssetSpec>,
    ): ChatAssetRenderState {
        if (compositionKey in latchedFailedCompositionKeys) return ChatAssetRenderState.FAILED
        for (layerSpec in flattened) {
            when (val state = assets.peek(layerSpec.key)) {
                is ChatAssetState.Ready -> Unit
                ChatAssetState.Missing,
                ChatAssetState.Loading,
                -> return ChatAssetRenderState.PENDING
                is ChatAssetState.Failed -> {
                    if (!state.isPresentationTerminal) return ChatAssetRenderState.PENDING
                    return ChatAssetRenderState.FAILED
                }
            }
        }
        return ChatAssetRenderState.READY
    }

    private fun updateDrawableAnimations() {
        drawables.forEach { (key, drawable) ->
            updateAnimationState(key, drawable)
        }
    }

    private fun updateAnimationState(key: ChatAssetKey, drawable: Drawable) {
        drawable.callback = if (renderingActive && windowAttached) this else null
        val animatable = drawable as? Animatable ?: return
        val shouldRun = animateGifs && renderingActive && windowAttached && key in animatedAssetKeys
        if (shouldRun) {
            if (!animatable.isRunning) {
                animatable.start()
                ChatRenderDiagnostics.recordAnimationStarted()
            }
        } else if (animatable.isRunning) {
            animatable.stop()
            ChatRenderDiagnostics.recordAnimationStopped()
        }
    }

    private fun isDirectAssetFailureLatched(key: ChatAssetKey): Boolean = key in latchedFailedDirectKeys

    /** Stops asset callbacks while the containing chat surface is hidden. */
    fun setRenderingActive(active: Boolean) {
        if (renderingActive == active) return
        renderingActive = active
        if (active) {
            if (isAttachedToWindow) keys.forEach { assets.observe(it, assetInvalidator) }
            if (isAttachedToWindow) clipPreviewSlugs.forEach { slug -> clipPreviews?.observe(slug, clipMetadataInvalidator) }
            if (isAttachedToWindow) clipPreviewAssetKeys.forEach { assets.observe(it, clipThumbnailInvalidator) }
            if (isAttachedToWindow) {
                refreshClipPreviewAssets()
                maybeRevealInitialAssetFrame()
            }
            if (isAttachedToWindow) updateDrawableAnimations()
        } else {
            keys.forEach { assets.removeObserver(it, assetInvalidator) }
            clipPreviewSlugs.forEach { slug -> clipPreviews?.removeObserver(slug, clipMetadataInvalidator) }
            clipPreviewAssetKeys.forEach { assets.removeObserver(it, clipThumbnailInvalidator) }
            drawables.forEach { (key, drawable) -> updateAnimationState(key, drawable) }
        }
    }

    override fun onDetachedFromWindow() {
        windowAttached = false
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
        windowAttached = true
        if (renderingActive) {
            keys.forEach { assets.observe(it, assetInvalidator) }
            clipPreviewSlugs.forEach { slug -> clipPreviews?.observe(slug, clipMetadataInvalidator) }
            clipPreviewAssetKeys.forEach { assets.observe(it, clipThumbnailInvalidator) }
            refreshClipPreviewAssets()
            maybeRevealInitialAssetFrame()
            updateDrawableAnimations()
        }
    }

    override fun verifyDrawable(who: Drawable): Boolean =
        super.verifyDrawable(who) || drawables.values.any { it === who }

    override fun invalidateDrawable(drawable: Drawable) {
        if (BuildConfig.PERF_DIAGNOSTICS && drawable is Animatable) {
            ChatRenderDiagnostics.recordAnimationInvalidation()
        }
        super.invalidateDrawable(drawable)
    }
}

private class ChatEventBackgroundDrawable(
    private val surfaceColor: Int,
    private val accentColor: Int,
    private val railWidthPx: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        paint.color = surfaceColor
        canvas.drawRect(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
            paint,
        )
        paint.color = accentColor
        val railLeft = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            (bounds.right - railWidthPx).coerceAtLeast(bounds.left)
        } else {
            bounds.left
        }
        val railRight = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            bounds.right
        } else {
            (bounds.left + railWidthPx).coerceAtMost(bounds.right)
        }
        canvas.drawRect(
            railLeft.toFloat(),
            bounds.top.toFloat(),
            railRight.toFloat(),
            bounds.bottom.toFloat(),
            paint,
        )
    }

    override fun onLayoutDirectionChanged(layoutDirection: Int): Boolean {
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

    @Deprecated("Drawable opacity is not used by the chat row")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

private fun blendColors(baseColor: Int, overlayColor: Int, overlayAlpha: Int): Int {
    val alpha = overlayAlpha.coerceIn(0, 255)
    fun component(shift: Int): Int {
        val base = baseColor ushr shift and 0xff
        val overlay = overlayColor ushr shift and 0xff
        return (base * (255 - alpha) + overlay * alpha) / 255
    }
    return 0xff000000.toInt() or
        (component(16) shl 16) or
        (component(8) shl 8) or
        component(0)
}

private fun Int?.orZero(): Int = this ?: 0

private fun Drawable.stopIfNeeded() {
    (this as? Animatable)?.let { animatable ->
        if (animatable.isRunning) {
            animatable.stop()
            ChatRenderDiagnostics.recordAnimationStopped()
        }
    }
}

private fun ChatAssetSpec.allKeys(): List<ChatAssetKey> = buildList {
    add(key)
    overlays.forEach { addAll(it.allKeys()) }
}

private fun ChatAssetSpec.flatten(): List<ChatAssetSpec> = buildList {
    add(this@flatten)
    overlays.forEach { addAll(it.flatten()) }
}

private enum class ChatAssetRenderState {
    PENDING,
    READY,
    FAILED,
}

private enum class ChatAssetFallbackMode {
    NONE,
    TEXT_ON_FAILURE,
}

private class ChatNamePaintSpan(
    private val paintSpec: ChatNamePaint,
    private val assets: ChatAssetRepository,
    private val isFailureLatched: (ChatAssetKey) -> Boolean,
) : CharacterStyle() {
    private var imageHandle: Any? = null
    private var imageShader: Shader? = null

    override fun updateDrawState(textPaint: TextPaint) {
        textPaint.clearShadowLayer()
        val imageUrl = paintSpec.imageUrl?.takeIf { it.isNotBlank() }
        if (imageUrl != null) {
            val key = ChatAssetKey(imageUrl)
            val handle = if (isFailureLatched(key)) {
                null
            } else {
                (assets.peek(key) as? ChatAssetState.Ready)?.image
            }
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
    private val layerSpecs: List<ChatAssetSpec>,
    private val drawableFor: (ChatAssetSpec) -> Drawable?,
    private val renderState: () -> ChatAssetRenderState,
    private val fallbackMode: ChatAssetFallbackMode,
) : ReplacementSpan() {
    private val compositionWidth = spec.compositionWidth
    private val compositionHeight = spec.compositionHeight
    private val resolvedDrawables = arrayOfNulls<Drawable>(layerSpecs.size)

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val height = compositionHeight
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
        return if (renderState() == ChatAssetRenderState.FAILED &&
            fallbackMode == ChatAssetFallbackMode.TEXT_ON_FAILURE
        ) {
            maxOf(compositionWidth, fallbackWidth(paint))
        } else {
            compositionWidth
        }
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        when (renderState()) {
            ChatAssetRenderState.PENDING -> return
            ChatAssetRenderState.FAILED if fallbackMode == ChatAssetFallbackMode.NONE -> return
            ChatAssetRenderState.READY -> drawReady(canvas, x, top, bottom)
            ChatAssetRenderState.FAILED -> drawFallback(canvas, x, top, bottom, paint)
        }
    }

    private fun drawReady(canvas: Canvas, x: Float, top: Int, bottom: Int) {
        for (index in layerSpecs.indices) {
            resolvedDrawables[index] = drawableFor(layerSpecs[index]) ?: return
        }
        val centerX = x + compositionWidth / 2f
        val centerY = (top + bottom) / 2f
        for (index in layerSpecs.indices) {
            val layerSpec = layerSpecs[index]
            val drawable = resolvedDrawables[index] ?: return
            val width = layerSpec.computedWidth
            val height = layerSpec.targetHeight
            val left = (centerX - width / 2f).roundToInt()
            val layerTop = (centerY - height / 2f).roundToInt()
            drawable.setBounds(left, layerTop, left + width, layerTop + height)
            drawable.draw(canvas)
        }
    }

    private fun drawFallback(canvas: Canvas, x: Float, top: Int, bottom: Int, paint: Paint) {
        val oldColor = paint.color
        val oldStyle = paint.style
        val oldTextSize = paint.textSize
        val oldTextAlign = paint.textAlign
        val centerY = (top + bottom) / 2f
        val label = fallback.trim().ifEmpty { "?" }
        val fallbackPaint = fallbackPaint(paint)
        val reservedWidth = maxOf(compositionWidth, fallbackPaint.measureText(label).roundToInt())
        paint.color = oldColor
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = fallbackPaint.textSize
        val baseline = centerY - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(label, x + reservedWidth / 2f, baseline, paint)
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
            (compositionHeight * 0.5f).coerceAtLeast(8f),
        )
    }
}
