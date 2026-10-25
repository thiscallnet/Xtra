package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.ui.chat.v2.ui.ChatMessageTextView

internal fun setChatInteractionSelection(view: ChatMessageTextView, selected: Boolean) {
    view.setInteractionSelected(selected)
}
