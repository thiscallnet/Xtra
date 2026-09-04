package com.github.andreyasadchy.xtra.ui.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.format.DateUtils
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.view.View
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.Image
import com.github.andreyasadchy.xtra.model.chat.ImageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreview
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreviewLink
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.formatClipDuration
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.parseClipTimestamp

/** Extracts Twitch clip links from a legacy chat message body. */
internal fun clipLinksOf(message: String?): List<ChatClipPreviewLink> =
    if (message.isNullOrBlank()) emptyList() else ChatClipPreviewLink.parse(message)

/**
 * Routes raw clip URL spans to the in-app clip player (with chat and controls)
 * instead of a browser. Other links keep their default handling.
 */
internal fun installLegacyClipLinkClicks(builder: SpannableStringBuilder) {
    builder.getSpans(0, builder.length, URLSpan::class.java).forEach { span ->
        val url = span.url ?: return@forEach
        if (!ChatClipPreviewLink.isClipUrl(url)) return@forEach
        val start = builder.getSpanStart(span)
        val end = builder.getSpanEnd(span)
        val flags = builder.getSpanFlags(span)
        builder.removeSpan(span)
        builder.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                openClipInApp(widget.context, url)
            }
        }, start, end, flags)
    }
}

/**
 * Opens a Twitch clip URL with the app's own player when possible, falling back
 * to a generic view intent. MainActivity handles twitch.tv clip links in-app.
 */
internal fun openClipInApp(context: Context, url: String) {
    val normalized = if (url.startsWith("http://", ignoreCase = true) ||
        url.startsWith("https://", ignoreCase = true)
    ) url else "https://$url"
    val inApp = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).apply {
        setPackage(context.packageName)
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runCatching { context.startActivity(inApp) }.isSuccess) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}

/**
 * Appends a compact clip unfurl (thumbnail, title, broadcaster/game, clipper)
 * to a legacy chat builder. Thumbnails reuse the regular chat image pipeline
 * through [images]; the whole block opens the clip in-app.
 */
internal fun appendLegacyClipEmbeds(
    context: Context,
    builder: SpannableStringBuilder,
    images: ArrayList<Image>,
    links: List<ChatClipPreviewLink>,
    previews: List<ChatClipPreview?>,
) {
    links.forEachIndexed { index, link ->
        val preview = previews.getOrNull(index) ?: return@forEachIndexed
        builder.append('\n')
        val blockStart = builder.length
        preview.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { thumbnail ->
            val thumbStart = builder.length
            builder.append("￼")
            val thumbEnd = builder.length
            images.add(
                Image(
                    url1x = thumbnail,
                    url2x = thumbnail,
                    url3x = thumbnail,
                    url4x = thumbnail,
                    kind = ImageKind.EMOTE,
                    sourceWidth = 16,
                    sourceHeight = 9,
                    start = thumbStart,
                    end = thumbEnd,
                ),
            )
            builder.append(' ')
        }
        val titleStart = builder.length
        builder.append(preview.title?.takeIf { it.isNotBlank() } ?: link.slug)
        builder.setSpan(StyleSpan(Typeface.BOLD), titleStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.append('\n')
        val subtitleStart = builder.length
        builder.append(legacyClipSubtitle(context, preview))
        builder.setSpan(
            ForegroundColorSpan(LEGACY_CLIP_SECONDARY_COLOR),
            subtitleStart,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.append('\n')
        val attributionStart = builder.length
        builder.append(legacyClipAttribution(context, preview))
        builder.setSpan(
            ForegroundColorSpan(LEGACY_CLIP_SECONDARY_COLOR),
            attributionStart,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                openClipInApp(widget.context, link.url)
            }

            override fun updateDrawState(ds: TextPaint) = Unit
        }, blockStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

internal fun legacyClipSubtitle(context: Context, preview: ChatClipPreview): String {
    val broadcaster = preview.broadcasterName?.takeIf { it.isNotBlank() } ?: "Twitch"
    val base = preview.gameName?.takeIf { it.isNotBlank() }?.let { game ->
        context.getString(R.string.chat_clip_playing, broadcaster, game)
    } ?: broadcaster
    return formatClipDuration(preview.durationSeconds)?.let { "$base — $it" } ?: base
}

internal fun legacyClipAttribution(context: Context, preview: ChatClipPreview): String {
    val creator = preview.creatorName?.takeIf { it.isNotBlank() } ?: "unknown"
    val base = context.getString(R.string.chat_clip_clipped_by, creator)
    return legacyClipRelativeTime(preview.createdAt)?.let { "$base — $it" } ?: base
}

internal fun legacyClipRelativeTime(createdAt: String?): String? {
    val epochMs = parseClipTimestamp(createdAt) ?: return null
    if (epochMs > System.currentTimeMillis()) return null
    return DateUtils.getRelativeTimeSpanString(epochMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}

private const val LEGACY_CLIP_SECONDARY_COLOR = 0xFF999999.toInt()
