package com.github.andreyasadchy.xtra.ui.chat

import android.content.Context
import android.graphics.Color
import android.net.Uri
import androidx.annotation.ColorInt
import com.github.andreyasadchy.xtra.ui.appearance.AppearanceRepository
import com.github.andreyasadchy.xtra.ui.appearance.DEFAULT_BACKGROUND_VISIBILITY
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

internal const val DEFAULT_CHAT_BACKGROUND_VISIBILITY = DEFAULT_BACKGROUND_VISIBILITY

internal data class ChatAppearancePreferenceValues(
    val backgroundUri: Uri? = null,
    val backgroundEnabled: Boolean = false,
    val backgroundVisibility: Int = DEFAULT_CHAT_BACKGROUND_VISIBILITY,
    val messageTextColor: String? = null,
    val metadataTextColor: String? = null,
)

internal data class ResolvedChatAppearance(
    val backgroundUri: Uri?,
    val backgroundEnabled: Boolean,
    val backgroundVisibility: Int,
    @ColorInt val surfaceColor: Int,
    @ColorInt val messageTextColor: Int,
    @ColorInt val metadataTextColor: Int,
    @ColorInt val rowBackgroundColor: Int,
) {
    val hasCustomBackground: Boolean
        get() = backgroundEnabled && backgroundUri != null
}

internal fun resolveChatAppearance(
    context: Context,
    @ColorInt surfaceColor: Int,
    @ColorInt messageDefaultColor: Int,
    @ColorInt metadataDefaultColor: Int,
): ResolvedChatAppearance {
    val preferences = context.prefs()
    val playerBackground = AppearanceRepository(context).resolvedPlayerBackground()
    val values = ChatAppearancePreferenceValues(
        backgroundUri = playerBackground.uri,
        backgroundEnabled = playerBackground.enabled,
        backgroundVisibility = playerBackground.visibility,
        messageTextColor = preferences.getString(C.PLAYER_MESSAGE_TEXT_COLOR, null),
        metadataTextColor = preferences.getString(C.PLAYER_METADATA_TEXT_COLOR, null),
    )
    return resolveChatAppearance(values, surfaceColor, messageDefaultColor, metadataDefaultColor)
}

internal fun resolveChatAppearance(
    values: ChatAppearancePreferenceValues,
    @ColorInt surfaceColor: Int,
    @ColorInt messageDefaultColor: Int,
    @ColorInt metadataDefaultColor: Int,
): ResolvedChatAppearance {
    val uri = values.backgroundUri
    val hasCustomBackground = values.backgroundEnabled && uri != null
    val messageTextColor = values.messageTextColor
        ?.let(::parseOpaqueColor)
        ?: messageDefaultColor
    val metadataTextColor = values.metadataTextColor
        ?.let(::parseOpaqueColor)
        ?: metadataDefaultColor
    return ResolvedChatAppearance(
        backgroundUri = uri,
        backgroundEnabled = values.backgroundEnabled,
        backgroundVisibility = values.backgroundVisibility.coerceIn(0, 100),
        surfaceColor = surfaceColor,
        messageTextColor = messageTextColor,
        metadataTextColor = metadataTextColor,
        rowBackgroundColor = if (hasCustomBackground) Color.TRANSPARENT else surfaceColor,
    )
}

internal fun shouldRenderChatBackground(appearance: ResolvedChatAppearance): Boolean =
    shouldRenderChatBackground(
        backgroundEnabled = appearance.backgroundEnabled,
        hasBackgroundUri = appearance.backgroundUri != null,
        backgroundVisibility = appearance.backgroundVisibility,
    )

internal fun shouldRenderChatBackground(
    backgroundEnabled: Boolean,
    hasBackgroundUri: Boolean,
    backgroundVisibility: Int,
): Boolean = backgroundEnabled && hasBackgroundUri && backgroundVisibility > 0

internal fun parseOpaqueColor(raw: String?): Int? {
    val value = raw?.trim()?.removePrefix("#") ?: return null
    if (!value.matches(Regex("[0-9a-fA-F]{6}"))) return null
    return 0xFF000000.toInt() or
        (value.substring(0, 2).toInt(16) shl 16) or
        (value.substring(2, 4).toInt(16) shl 8) or
        value.substring(4, 6).toInt(16)
}
