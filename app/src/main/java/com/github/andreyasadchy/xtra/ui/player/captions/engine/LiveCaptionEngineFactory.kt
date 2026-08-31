package com.github.andreyasadchy.xtra.ui.player.captions.engine

import android.content.Context

object LiveCaptionEngineFactory {
    fun create(context: Context): LiveCaptionEngine = SherpaMoonshineEngine(context)
}
