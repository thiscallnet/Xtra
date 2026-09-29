package com.github.andreyasadchy.xtra.ui.common

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.view.CenteredImageSpan
import kotlin.math.roundToInt

/** Shows Twitch's Partner mark inline with a streamer name and exposes it to TalkBack. */
internal fun TextView.setVerifiedPartnerName(name: CharSequence?, broadcasterType: String?) {
    if (name.isNullOrEmpty()) {
        text = name
        contentDescription = null
        return
    }

    val displayName = name.toString()
    val isPartner = broadcasterType.equals("partner", ignoreCase = true)
    contentDescription = if (isPartner) {
        "$displayName, ${context.getString(R.string.user_partner)}"
    } else {
        displayName
    }

    if (!isPartner) {
        text = name
        return
    }

    val drawable = AppCompatResources.getDrawable(context, R.drawable.ic_verified_partner)
        ?.let(DrawableCompat::wrap)
        ?.mutate()
        ?: run {
            text = name
            return
        }
    val size = textSize.roundToInt().coerceAtLeast(1)
    drawable.setBounds(0, 0, size, size)

    val markedName = SpannableStringBuilder(name)
        .append('\u00a0')
        .append('\ufffc')
    val iconStart = markedName.length - 1
    markedName.setSpan(
        CenteredImageSpan(drawable, size, size),
        iconStart,
        markedName.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
    text = markedName
}
