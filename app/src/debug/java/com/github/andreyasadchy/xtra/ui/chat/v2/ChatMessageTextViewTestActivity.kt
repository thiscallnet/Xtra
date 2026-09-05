package com.github.andreyasadchy.xtra.ui.chat.v2

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/** A debug-only host used by renderer instrumentation tests. */
class ChatMessageTextViewTestActivity : Activity() {
    lateinit var root: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        setContentView(root)
    }
}
