package com.github.andreyasadchy.xtra.ui.login

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Debug-only launcher for observing authenticated GeckoView Twitch traffic. */
class TwitchGqlCaptureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, TwitchWebLoginActivity::class.java)
                .putExtra(TwitchWebLoginActivity.EXTRA_CAPTURE_GQL, true),
        )
        finish()
    }
}
