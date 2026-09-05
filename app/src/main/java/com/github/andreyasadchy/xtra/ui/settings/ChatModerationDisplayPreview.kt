package com.github.andreyasadchy.xtra.ui.settings

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatModerationDisplayMode
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.color.MaterialColors

class ChatModerationDisplayPreviewPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.preference_chat_moderation_display_preview
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.findViewById<ChatModerationDisplayPreviewView>(R.id.chatModerationDisplayPreview)
            .setMode(
                ChatModerationDisplayMode.fromPreference(
                    context.prefs().getString(C.CHAT_MODERATION_DISPLAY, "notice"),
                ),
            )
    }

    fun refreshPreview() = notifyChanged()
}

class ChatModerationDisplayPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val primaryColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val secondaryColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(MaterialColors.getColor(this@ChatModerationDisplayPreviewView, com.google.android.material.R.attr.colorSurfaceContainerHigh))
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setMode(mode: ChatModerationDisplayMode) {
        removeAllViews()
        when (mode) {
            ChatModerationDisplayMode.NOTICE -> {
                addMessage(previewMessage())
                addNotice(moderationNotice())
            }
            ChatModerationDisplayMode.STRIKETHROUGH -> {
                val body = previewMessage()
                val suffix = " ${context.getString(R.string.chat_moderation_messages_cleared)}"
                val text = SpannableString(body + suffix).apply {
                    setSpan(StrikethroughSpan(), 0, body.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(secondaryColor), 0, body.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(primaryColor), body.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                addLine(text, secondary = false)
            }
            ChatModerationDisplayMode.HIDE -> {
                addMessage(context.getString(R.string.chat_moderation_preview_new_message).let { "${previewUsername()}: $it" })
                addNotice(moderationNotice())
            }
        }
        contentDescription = context.getString(R.string.chat_moderation_preview_summary)
    }

    private fun addMessage(value: String) = addLine(value, secondary = false)

    private fun addNotice(value: String) = addLine(value, secondary = true)

    private fun previewUsername(): String = context.getString(R.string.chat_moderation_preview_username)

    private fun previewMessage(): String = context.getString(
        R.string.chat_moderation_preview_message,
    ).let { "${previewUsername()}: $it" }

    private fun moderationNotice(): String = context.getString(
        R.string.chat_clear_user,
        previewUsername(),
    )

    private fun addLine(value: CharSequence, secondary: Boolean) {
        addView(TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(if (secondary) secondaryColor else primaryColor)
            typeface = Typeface.DEFAULT
            setPadding(0, dp(1), 0, dp(1))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        })
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
