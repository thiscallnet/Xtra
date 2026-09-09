package com.github.andreyasadchy.xtra.ui.drops

import com.github.andreyasadchy.xtra.model.ui.TwitchDropImageSource

/** Resize only game box art; reward and campaign artwork is already square and must stay intact. */
internal fun dropsImageUrl(
    url: String,
    source: TwitchDropImageSource = TwitchDropImageSource.ORIGINAL,
): String {
    if (source != TwitchDropImageSource.GAME_BOX_ART ||
        !url.contains("jtvnw.net", ignoreCase = true)
    ) return url
    return url.replace(
        Regex("-\\d+x\\d+(?=\\.[^/?]+(?:\\?.*)?$)"),
        "-600x800",
    )
}
