package com.github.andreyasadchy.xtra.ui.chat

import android.content.Context
import android.graphics.Color
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

private val TRANSLATION_TEXT_COLOR_PATTERN = Regex("#[0-9a-fA-F]{6}")

internal data class ChatTranslationAppearance(
    val italic: Boolean = false,
    val textColor: Int? = null,
)

internal fun resolveChatTranslationAppearance(context: Context): ChatTranslationAppearance {
    val preferences = context.prefs()
    val colorValue = preferences.getString(C.CHAT_TRANSLATION_TEXT_COLOR, null)?.trim()
    val textColor = colorValue
        ?.takeIf(TRANSLATION_TEXT_COLOR_PATTERN::matches)
        ?.let { runCatching { Color.parseColor(it) }.getOrNull() }
    return ChatTranslationAppearance(
        italic = preferences.getBoolean(C.CHAT_TRANSLATION_ITALIC, false),
        textColor = textColor,
    )
}
