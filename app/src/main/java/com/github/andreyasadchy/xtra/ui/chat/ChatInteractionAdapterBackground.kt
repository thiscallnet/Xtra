package com.github.andreyasadchy.xtra.ui.chat

import android.view.View
import com.github.andreyasadchy.xtra.R
import com.google.android.material.color.MaterialColors

internal fun applyChatInteractionSelectionBackground(view: View) {
    view.setBackgroundColor(MaterialColors.getColor(view, R.attr.chatMessageSelectedColor))
}
