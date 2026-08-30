package com.github.andreyasadchy.xtra.ui.player.captions.engine

import android.content.Context

object LiveCaptionEngineFactory {
    fun create(context: Context, id: LiveCaptionEngineId): LiveCaptionEngine {
        return when (id) {
            LiveCaptionEngineId.ZIPFORMER_20M -> SherpaZipformerEngine(context)
            LiveCaptionEngineId.MOONSHINE_V2_TINY -> SherpaMoonshineEngine(
                context = context,
                emitPartials = true,
            )
            LiveCaptionEngineId.ZIPFORMER_MOONSHINE_2PASS -> SherpaTwoPassEngine(context)
        }
    }
}
