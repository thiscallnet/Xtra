package com.github.andreyasadchy.xtra.ui.player.captions.engine

import android.content.Context
import com.github.andreyasadchy.xtra.ui.player.captions.liveCaptionUsesSystemEngine
import com.github.andreyasadchy.xtra.util.prefs

object LiveCaptionEngineFactory {
    fun create(context: Context): LiveCaptionEngine =
        if (context.prefs().liveCaptionUsesSystemEngine()) {
            SystemSpeechCaptionEngine(context)
        } else {
            SherpaMoonshineEngine(context)
        }
}
