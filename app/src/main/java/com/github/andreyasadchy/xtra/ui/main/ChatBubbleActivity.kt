package com.github.andreyasadchy.xtra.ui.main

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.github.andreyasadchy.xtra.XtraApp

class ChatBubbleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the component so old PendingIntents resolve, but never reopen the retired feature.
        (application as XtraApp).xtraModule.chatBubbleManager.close()
        finish()
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "chat_bubble_channel_id"
        const val EXTRA_CHANNEL_LOGIN = "chat_bubble_channel_login"
        const val EXTRA_CHANNEL_NAME = "chat_bubble_channel_name"
        const val EXTRA_STREAM_ID = "chat_bubble_stream_id"
    }

}
